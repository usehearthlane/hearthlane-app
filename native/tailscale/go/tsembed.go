// Package tsembed embeds a single Tailscale node (tsnet) behind a minimal
// API that can be exposed to Android through gomobile bind.
//
// The public surface is intentionally tiny: Start, Stop, Status. Android/Kotlin
// code must not depend on Tailscale internals, and Tailscale code must not be
// mixed with Frigate or playback concerns.
package tsembed

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/netip"
	urlpkg "net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/miekg/dns"
	"tailscale.com/tsnet"
	"tailscale.com/types/dnstype"
	"tailscale.com/util/dnsname"
	netstack "tailscale.com/wgengine/netstack"
)

// Connectivity states reported by Status, matching the application-oriented
// states described in docs/PLAN.md (core/connectivity).
const (
	stateDisconnected   = "Disconnected"
	stateAuthenticating = "Authenticating"
	stateConnecting     = "Connecting"
	stateConnected      = "Connected"
	stateFailed         = "Failed"
	stateStopped        = "Stopped"
)

// status is the JSON payload returned by Status.
type status struct {
	State   string `json:"state"`
	AuthURL string `json:"authUrl,omitempty"`
	Error   string `json:"error,omitempty"`
}

var (
	mu        sync.Mutex
	server    *tsnet.Server
	cancel    context.CancelFunc
	startDone chan struct{}
	started   bool
	lastErr   error

	// Node-scoped HTTP transport/client: shared across requests so HLS (which
	// issues many small requests: playlists, init, one per media segment) gets
	// normal net/http connection pooling/keep-alive over the tunnel instead of
	// a fresh netstack dial + TLS setup per request. Tied to the exact node
	// ([httpServer]) and cleared on Stop, so a restarted node never reuses a
	// transport bound to a previous node's netstack.
	httpTransport *http.Transport
	httpClient    *http.Client
	httpServer    *tsnet.Server

	// startServer is swapped in tests to simulate tsnet startup outcomes
	// without requiring a real tailnet connection.
	startServer = func(s *tsnet.Server) error { return s.Start() }

	// debugDNS gates debug-level DNS logs (cache hit/miss). See SetDebugDNS.
	debugDNS atomic.Bool

	// dnsResolutionMu serializes tailnet DNS misses. Concurrent segment
	// fetches would otherwise each resolve the hostname on a cache miss;
	// holding the mutex around the query and re-checking the cache after
	// acquiring it collapses those misses into a single query.
	dnsResolutionMu sync.Mutex

	// dnsUpstreamsFor returns the tailnet resolvers configured for an FQDN.
	// Production reads them from the tsnet DNS manager; tests replace it.
	dnsUpstreamsFor = tailnetUpstreamsFor

	// dnsQuery sends a DNS query to an upstream over the tunnel and returns
	// the parsed answer. Production uses queryTunnelDNS; tests replace it with
	// a recorder.
	dnsQuery = queryTunnelDNS

	// udpQueryFn and tcpQueryFn are the transport primitives selected by
	// queryTunnelDNS; tests replace them to assert transport ordering.
	udpQueryFn = udpDNSQuery
	tcpQueryFn = tcpDNSQuery
)

// dnsCacheTTL caps how long a resolved hostname is reused without a new query,
// regardless of the TTL advertised by the resolver. The real answer TTL is used
// when present, clamped to this maximum so split-DNS reconfiguration is picked
// up within a bounded window.
const dnsCacheTTL = 60 * time.Second

// dnsAnswer is the parsed result of a tailnet DNS query: the resolved A records
// and the smallest TTL advertised across them.
type dnsAnswer struct {
	ips []netip.Addr
	ttl uint32
}

// cacheTTL returns how long answer should be cached. The homelab resolver
// (192.168.10.2) advertises TTL 0, which would disable caching entirely and
// bring back per-segment re-resolution, so a zero answer TTL falls back to the
// conservative fixed dnsCacheTTL. Any TTL is clamped to dnsCacheTTL.
func (a dnsAnswer) cacheTTL() time.Duration {
	ttl := time.Duration(a.ttl) * time.Second
	if ttl <= 0 {
		ttl = dnsCacheTTL
	}
	if ttl > dnsCacheTTL {
		ttl = dnsCacheTTL
	}
	return ttl
}

// dnsCacheEntry holds resolved addresses for a hostname until expires.
type dnsCacheEntry struct {
	ips     []netip.Addr
	expires time.Time
}

// dnsCache is a small thread-safe in-memory cache for tailnet DNS
// resolutions. It lives for the process lifetime and is never persisted.
// Failures and empty answers are never stored (see put).
type dnsCache struct {
	mu      sync.Mutex
	entries map[string]dnsCacheEntry
}

var dnsCacheInstance = &dnsCache{entries: make(map[string]dnsCacheEntry)}

// get returns the first cached address for key when present and not expired.
// Expired entries are removed lazily.
func (c *dnsCache) get(key string) (netip.Addr, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	e, ok := c.entries[key]
	if !ok || time.Now().After(e.expires) || len(e.ips) == 0 {
		delete(c.entries, key)
		return netip.Addr{}, false
	}
	return e.ips[0], true
}

// put stores addresses for key, expiring after ttl. Empty address lists and
// non-positive TTLs are ignored so failures and empty answers never enter the
// cache.
func (c *dnsCache) put(key string, ips []netip.Addr, ttl time.Duration) {
	if len(ips) == 0 || ttl <= 0 {
		return
	}
	// Copy so callers cannot mutate a cached entry through the returned slice.
	ips = append([]netip.Addr(nil), ips...)
	c.mu.Lock()
	defer c.mu.Unlock()
	c.entries[key] = dnsCacheEntry{ips: ips, expires: time.Now().Add(ttl)}
}

// SetDebugDNS enables or disables debug-level DNS logs (cache hit/miss).
// These are off by default; enable them while diagnosing tailnet DNS issues on
// a device. Only hostnames and upstream addresses are logged, never secrets.
func SetDebugDNS(enabled bool) {
	debugDNS.Store(enabled)
}

func debugLogf(format string, args ...any) {
	if debugDNS.Load() {
		logf(format, args...)
	}
}

// cacheKey derives the cache key from a normalized FQDN: lowercase, without the
// trailing dot, so "Site.Omni.Corp." and "site.omni.corp" share one entry.
func cacheKey(fqdn string) string {
	return strings.ToLower(strings.TrimSuffix(fqdn, "."))
}

// Start launches an embedded Tailscale node in the background and returns
// immediately. hostname is the tailnet hostname, authKey may be empty to use
// the interactive login URL flow, and stateDir must be a persistent
// application directory that stores the node identity across launches.
//
// Start is only ever called by the user (via the Start/Retry button). It is
// never triggered by Status polling or by the UI loop. On a failed startup the
// partial instance is released and the node returns to the Failed state; a new
// attempt requires another explicit Start call.
func Start(hostname, authKey, stateDir string) error {
	mu.Lock()
	defer mu.Unlock()
	if server != nil {
		return errors.New("tsembed: already started")
	}
	if err := os.MkdirAll(stateDir, 0o700); err != nil {
		return fmt.Errorf("tsembed: create state dir: %w", err)
	}
	// On Android there is no usable default location for Tailscale's log
	// state: no $TS_LOGS_DIR/$HOME/cache, cwd is "/", and /tmp does not exist.
	// logpolicy.LogsDir panics in that situation, so point it at a writable
	// subdirectory of the node state. This runs on EVERY Start: the env
	// variable may already be set from an earlier Start in the same process,
	// but the directory may have been removed (identity reset), so the log
	// directory is (re)created deterministically before the node starts.
	logsDir := filepath.Join(stateDir, "logs")
	if err := os.MkdirAll(logsDir, 0o700); err != nil {
		return fmt.Errorf("tsembed: create logs dir: %w", err)
	}
	if err := os.Setenv("TS_LOGS_DIR", logsDir); err != nil {
		return fmt.Errorf("tsembed: set TS_LOGS_DIR: %w", err)
	}
	logf("log state dir set to %q", logsDir)

	ctx, cancelFn := context.WithCancel(context.Background())
	s := &tsnet.Server{
		Hostname: hostname,
		AuthKey:  authKey,
		Dir:      stateDir,
		Logf:     logf,
	}
	server = s
	cancel = cancelFn
	startDone = make(chan struct{})
	started = false
	lastErr = nil
	logf("start requested: hostname=%q stateDir=%q", hostname, stateDir)
	go func() {
		defer close(startDone)
		// A panic anywhere in the tsnet lifecycle (Start or Close) must become a
		// controlled Failed state, never an unrecovered panic that SIGABRTs the
		// process. This is the crash class observed when a second Start follows
		// a Stop + identity reset in the same Android process.
		defer func() {
			if r := recover(); r != nil {
				logf("lifecycle panic recovered: %v", r)
				mu.Lock()
				if server == s {
					server, cancel, startDone = nil, nil, nil
					started = false
					lastErr = fmt.Errorf("tsembed: lifecycle panicked: %v", r)
				}
				mu.Unlock()
				clearHTTPClient()
				cancelFn()
			}
		}()
		if err := startServer(s); err != nil {
			// Release every reference to the partial instance so the node
			// reports Failed and a later Start can begin from a clean slate.
			mu.Lock()
			if server == s {
				server, cancel, startDone = nil, nil, nil
				started = false
				lastErr = err
			}
			mu.Unlock()
			cancelFn()
			logf("start failed: %v", err)
			logf("cleanup after failed start")
			cleanupFailedServer(s)
			clearHTTPClient()
			return
		}
		mu.Lock()
		if server == s {
			started = true
		}
		mu.Unlock()
		logf("start succeeded")
		<-ctx.Done()
		logf("shutting down node")
		if err := s.Close(); err != nil {
			logf("close error: %v", err)
		}
		mu.Lock()
		if server == s {
			server, cancel, startDone = nil, nil, nil
			started = false
		}
		mu.Unlock()
	}()
	return nil
}

// cleanupFailedServer releases the partially-created tsnet instance after a
// failed Start. tsnet's Close is generally safe once Start has returned, but
// it is wrapped in a recover because very early failures can leave internal
// fields unset.
func cleanupFailedServer(s *tsnet.Server) {
	defer func() {
		if r := recover(); r != nil {
			logf("cleanup after failed start: close panicked: %v", r)
		}
	}()
	if err := s.Close(); err != nil {
		logf("cleanup after failed start: close error: %v", err)
	}
}

// Stop shuts the embedded node down and waits for the background goroutine to
// finish.
func Stop() error {
	mu.Lock()
	s, done, cancelFn := server, startDone, cancel
	mu.Unlock()
	if s == nil {
		return errors.New("tsembed: not started")
	}
	logf("stop requested")
	if cancelFn != nil {
		cancelFn()
	}
	if done != nil {
		<-done
	}
	// The node is fully closed: release the shared transport so a restarted
	// node never reuses a transport bound to the previous node's netstack.
	clearHTTPClient()
	mu.Lock()
	server, cancel, startDone = nil, nil, nil
	started = false
	lastErr = nil
	mu.Unlock()
	logf("stopped")
	return nil
}

// Status reports the current node state as a JSON string. Polling is cheap and
// is intended for a UI loop. It never starts the node: when no node is present
// it reports Failed/Disconnected/Stopped, and while startup is in progress it
// reports Connecting without touching the tsnet instance.
func Status() string {
	mu.Lock()
	s, last, isStarted := server, lastErr, started
	mu.Unlock()
	st := status{State: stateDisconnected}
	if s == nil {
		if last != nil {
			st.State = stateFailed
			st.Error = last.Error()
		}
		return mustJSON(st)
	}

	if !isStarted {
		st.State = stateConnecting
		return mustJSON(st)
	}

	lc, err := s.LocalClient()
	if err != nil {
		st.State = stateConnecting
		st.Error = fmt.Sprintf("local client unavailable: %v", err)
		return mustJSON(st)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	ipnStatus, err := lc.Status(ctx)
	if err != nil {
		st.State = stateConnecting
		st.Error = fmt.Sprintf("status unavailable: %v", err)
		return mustJSON(st)
	}
	switch ipnStatus.BackendState {
	case "NeedsLogin", "NeedsMachineAuth":
		st.State = stateAuthenticating
		st.AuthURL = ipnStatus.AuthURL
	case "Running":
		st.State = stateConnected
	case "Stopped":
		st.State = stateStopped
	default:
		st.State = stateConnecting
	}
	return mustJSON(st)
}

func logf(format string, args ...any) {
	log.Printf("tsembed: "+format, args...)
}

// HttpResult carries a completed HTTP GET response. The status code, content
// type, final URL and body are all preserved so callers can react to non-2xx
// responses: a request that reached the server never returns an error for a
// non-2xx status.
type HttpResult struct {
	StatusCode  int
	ContentType string
	FinalURL    string
	Body        []byte
}

// HttpGet performs an HTTP GET over the embedded tailnet. The request is made
// exclusively with a client whose dialer routes through the tailscale engine
// (tsdial.UserDial / netstack), so it can never fall back to the OS network.
// The node must already be Running (see Start/Status).
//
// DNS resolution for hostnames is performed over the tunnel against the
// nameservers configured in the Tailscale admin console (see resolveHost):
// tsnet's dialer only understands MagicDNS names and otherwise falls back to
// the system resolver, which on Android does not know split-DNS domains such
// as a homelab "omni.corp".
func HttpGet(url string, timeoutMs int64) (string, error) {
	res, err := HttpGetBytes(url, timeoutMs)
	if err != nil {
		return "", err
	}
	if res.StatusCode != http.StatusOK {
		return "", fmt.Errorf("tsembed: GET failed: HTTP %d", res.StatusCode)
	}
	return string(res.Body), nil
}

// HttpGetBytes is the generic transport primitive: an HTTP GET over the
// embedded tailnet that preserves the status code, content type, final URL
// (after redirects) and the full response body. It is intentionally free of
// any video, HLS or Frigate concerns. The node must already be Running.
//
// The whole response is buffered in memory. This is acceptable for the Phase 4
// HLS spike because every request is a bounded response (a manifest or a media
// segment); a streaming consumer would need a different primitive.
func HttpGetBytes(url string, timeoutMs int64) (*HttpResult, error) {
	s, running := currentServer()
	if s == nil || !running {
		return nil, errors.New("tsembed: node not running")
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()

	u, err := urlpkg.Parse(url)
	if err != nil {
		return nil, fmt.Errorf("tsembed: parse url: %w", err)
	}
	resp, err := doGet(ctx, httpClientFor(s), u, u.Hostname())
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	return httpResultFromResponse(resp)
}

// HttpPut performs an HTTP PUT over the embedded tailnet with the given
// request body and Content-Type. Same transport policy as HttpGetBytes: the
// request is made exclusively through the tunnel (never the OS network) and
// the node must be Running. A non-2xx status is returned, not thrown.
func HttpPut(url, contentType, body string, timeoutMs int64) (*HttpResult, error) {
	return HttpRequest(http.MethodPut, url, contentType, "", body, timeoutMs)
}

// HttpRequest performs an HTTP request over the embedded tailnet with the
// given method, request body, Content-Type and headers.
//
// headersJSON is a flat JSON object mapping header names to values, for
// example `{"Authorization":"Bearer <token>"}`; an empty string sends no
// headers. Content-Type is set only when provided, so GET requests that carry
// no body pass an empty body and contentType.
//
// Same transport policy as HttpGetBytes: the request is made exclusively
// through the tunnel (never the OS network) and the node must be Running. A
// non-2xx status is returned, not thrown.
func HttpRequest(method, url, contentType, headersJSON, body string, timeoutMs int64) (*HttpResult, error) {
	s, running := currentServer()
	if s == nil || !running {
		return nil, errors.New("tsembed: node not running")
	}
	return httpRequestInternal(method, url, contentType, headersJSON, body, timeoutMs, httpClientFor(s))
}

// httpRequestInternal performs the request through the given client. The
// client is injectable so tests exercise the whole flow against a local server
// without a running node; production passes the node-scoped shared client
// ([httpClientFor]) so connections are pooled across requests.
func httpRequestInternal(method, url, contentType, headersJSON, body string, timeoutMs int64, client *http.Client) (*HttpResult, error) {
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()

	u, err := urlpkg.Parse(url)
	if err != nil {
		return nil, fmt.Errorf("tsembed: parse url: %w", err)
	}
	host := u.Hostname()

	headers, err := parseHeadersJSON(headersJSON)
	if err != nil {
		return nil, err
	}

	resp, err := doRequest(ctx, client, u, host, method, contentType, headers, body)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	return httpResultFromResponse(resp)
}

// doRequest performs an HTTP request on an already-parsed URL, preserving the
// Host header (same policy as doGet) and setting the request Content-Type and
// headers when provided.
func doRequest(ctx context.Context, client *http.Client, u *urlpkg.URL, host, method, contentType string, headers http.Header, body string) (*http.Response, error) {
	var reader io.Reader
	if method != http.MethodGet && body != "" {
		reader = strings.NewReader(body)
	}
	req, err := http.NewRequestWithContext(ctx, method, u.String(), reader)
	if err != nil {
		return nil, fmt.Errorf("tsembed: build request: %w", err)
	}
	req.Host = host
	if contentType != "" {
		req.Header.Set("Content-Type", contentType)
	}
	for name, values := range headers {
		for _, value := range values {
			req.Header.Add(name, value)
		}
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("tsembed: %s failed: %w", method, err)
	}
	return resp, nil
}

// parseHeadersJSON decodes a flat JSON header object. Empty input yields an
// empty header set; malformed JSON is an error.
func parseHeadersJSON(headersJSON string) (http.Header, error) {
	headers := http.Header{}
	if strings.TrimSpace(headersJSON) == "" {
		return headers, nil
	}
	var raw map[string]string
	if err := json.Unmarshal([]byte(headersJSON), &raw); err != nil {
		return nil, fmt.Errorf("tsembed: parse headers JSON: %w", err)
	}
	for name, value := range raw {
		headers.Set(name, value)
	}
	return headers, nil
}

// HttpStreamInfo describes an open HTTP stream. The caller keeps the [Id] and
// reads the body incrementally with [ReadChunk] until [CloseStream]; the body
// is never buffered whole.
type HttpStreamInfo struct {
	Id            int64
	StatusCode    int
	ContentType   string
	FinalURL      string
	ContentLength int64
}

// httpStream is one open streaming response. Reads are serialized by a mutex
// so concurrent ReadChunk calls cannot interleave or corrupt each other;
// Close cancels the request context and closes the body WITHOUT holding the
// mutex, so a pending Read unblocks and returns an error instead of
// deadlocking.
type httpStream struct {
	mu     sync.Mutex
	resp   *http.Response
	cancel context.CancelFunc
	closed atomic.Bool
}

var (
	streamsMu    sync.Mutex
	streams      = map[int64]*httpStream{}
	nextStreamID atomic.Int64
)

// OpenHttpStream performs an HTTP GET over the embedded tailnet and returns a
// handle to the open response without reading the body. The body stays
// incremental for the whole playback: a connect timeout bounds the dial, but
// the request context carries no deadline, so a large clip can be consumed
// progressively. The node must already be Running.
func OpenHttpStream(url, headersJSON string, connectTimeoutMs int64) (*HttpStreamInfo, error) {
	s, running := currentServer()
	if s == nil || !running {
		return nil, errors.New("tsembed: node not running")
	}
	return openHttpStream(url, headersJSON, connectTimeoutMs, httpClientFor(s))
}

// openHttpStream performs the request and registers the stream. The client is
// injectable so tests exercise the whole flow against a local server without a
// running node; production passes the node-scoped shared client so the
// connection stays pooled after the stream closes.
func openHttpStream(url, headersJSON string, connectTimeoutMs int64, client *http.Client) (*HttpStreamInfo, error) {
	// Cancel-only context: the response body may remain open for the whole
	// playback. The connect timeout is enforced at the dial layer (see
	// netstackDialContext), and a shorter per-request deadline is subsumed by
	// the request context when a caller provides one.
	ctx, cancel := context.WithCancel(context.Background())
	fail := func(err error) (*HttpStreamInfo, error) {
		cancel()
		return nil, err
	}

	u, err := urlpkg.Parse(url)
	if err != nil {
		return fail(fmt.Errorf("tsembed: parse url: %w", err))
	}
	host := u.Hostname()
	headers, err := parseHeadersJSON(headersJSON)
	if err != nil {
		return fail(err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
	if err != nil {
		return fail(fmt.Errorf("tsembed: build request: %w", err))
	}
	req.Host = host
	for name, values := range headers {
		for _, value := range values {
			req.Header.Add(name, value)
		}
	}

	resp, err := client.Do(req)
	if err != nil {
		return fail(err)
	}

	st := &httpStream{resp: resp, cancel: cancel}
	id := nextStreamID.Add(1)
	streamsMu.Lock()
	streams[id] = st
	streamsMu.Unlock()

	finalURL := ""
	if resp.Request != nil && resp.Request.URL != nil {
		finalURL = resp.Request.URL.String()
	}
	return &HttpStreamInfo{
		Id:            id,
		StatusCode:    resp.StatusCode,
		ContentType:   resp.Header.Get("Content-Type"),
		FinalURL:      finalURL,
		ContentLength: resp.ContentLength,
	}, nil
}

// ReadResult carries one read from an open stream. [Data] holds the bytes read
// (empty when no bytes were available) and [EOF] reports that the stream has
// reached its natural end.
//
// Errors are never encoded in the result: a failing read returns a Go error
// instead, so the gomobile boundary never has to interpret a nil/empty byte
// slice as an error. This matters because gomobile maps an empty Go slice
// (len == 0, even non-nil) to a Java null byte[], which would be ambiguous on
// the Kotlin side; the explicit [EOF] flag removes that ambiguity.
type ReadResult struct {
	Data []byte
	EOF  bool
}

// ReadChunk reads up to max bytes from the stream identified by [id]. EOF is
// reported explicitly through [ReadResult.EOF] (never as an error and never as
// an ambiguous empty slice): a final read that also carries bytes (Go readers
// may return n > 0 together with io.EOF) delivers the bytes with EOF still
// false, and EOF is only reported on the following read.
func ReadChunk(id int64, max int) (*ReadResult, error) {
	if max < 0 {
		return nil, errors.New("tsembed: invalid chunk size")
	}
	st := lookupStream(id)
	if st == nil {
		return nil, errors.New("tsembed: unknown stream")
	}
	if max == 0 {
		return &ReadResult{Data: []byte{}}, nil
	}
	buf := make([]byte, max)
	n, err := st.read(buf)
	if n > 0 {
		// Return whatever was read even if the read also reported EOF: the
		// final bytes must be delivered before EOF is observed.
		return &ReadResult{Data: buf[:n]}, nil
	}
	if err == io.EOF {
		return &ReadResult{Data: []byte{}, EOF: true}, nil
	}
	if err != nil {
		return nil, err
	}
	return &ReadResult{Data: []byte{}}, nil
}

// CloseStream closes the stream identified by [id] and releases its resources.
// Idempotent: closing an unknown or already-closed stream is a no-op.
func CloseStream(id int64) error {
	streamsMu.Lock()
	st, ok := streams[id]
	if ok {
		delete(streams, id)
	}
	streamsMu.Unlock()
	if !ok {
		return nil
	}
	st.close()
	return nil
}

func lookupStream(id int64) *httpStream {
	streamsMu.Lock()
	st, ok := streams[id]
	streamsMu.Unlock()
	if !ok {
		return nil
	}
	return st
}

func (st *httpStream) read(buf []byte) (int, error) {
	st.mu.Lock()
	defer st.mu.Unlock()
	if st.closed.Load() {
		return 0, io.EOF
	}
	return st.resp.Body.Read(buf)
}

func (st *httpStream) close() {
	if st.closed.CompareAndSwap(false, true) {
		st.cancel()
		if st.resp != nil {
			st.resp.Body.Close()
		}
	}
}

// newClient builds a per-request client whose transport dials with the given
// DialContext. Used by tests to inject a standard dialer; production requests
// use the node-scoped shared [httpClientFor] instead, so connections are pooled
// across the many small requests HLS issues.
func newClient(dial func(ctx context.Context, network, addr string) (net.Conn, error)) *http.Client {
	return &http.Client{
		Transport: &http.Transport{DialContext: dial},
	}
}

// currentServer returns the running node under the lifecycle mutex.
func currentServer() (*tsnet.Server, bool) {
	mu.Lock()
	defer mu.Unlock()
	return server, started
}

// httpClientFor returns the node-scoped shared HTTP client, creating its
// transport on first use for the exact node [s]. Every tunnel request (HTTP
// API calls and OpenHttpStream) shares this client, so net/http keeps the
// connection open and reuses it for the next request instead of paying a fresh
// netstack dial + TLS setup each time. A restarted node (different [s]) always
// gets a fresh transport; the previous one is closed by [clearHTTPClient] on
// Stop.
func httpClientFor(s *tsnet.Server) *http.Client {
	mu.Lock()
	defer mu.Unlock()
	if httpClient == nil || httpServer != s {
		httpTransport = &http.Transport{
			DialContext:        netstackDialContext(s),
			IdleConnTimeout:    90 * time.Second,
			MaxIdleConnsPerHost: 8,
		}
		httpClient = &http.Client{Transport: httpTransport}
		httpServer = s
	}
	return httpClient
}

// clearHTTPClient releases the node-scoped client and its idle connections.
// Called when the node stops or its lifecycle fails, so an old transport bound
// to a previous node's netstack is never left behind.
func clearHTTPClient() {
	mu.Lock()
	defer mu.Unlock()
	if httpClient != nil {
		httpClient.CloseIdleConnections()
	}
	httpTransport = nil
	httpClient = nil
	httpServer = nil
}

// netstackDialContext returns the DialContext used by the shared transport: it
// resolves the hostname over the tunnel (cached) and dials through the node's
// netstack, so every connection stays inside the tsnet tunnel. The dial is
// bounded by a fixed connect timeout; a request context with a shorter
// deadline (used by the bounded HTTP API calls) cancels the dial first.
func netstackDialContext(s *tsnet.Server) func(ctx context.Context, network, addr string) (net.Conn, error) {
	return func(ctx context.Context, network, addr string) (net.Conn, error) {
		host, portStr, err := net.SplitHostPort(addr)
		if err != nil {
			return nil, fmt.Errorf("tsembed: split %q: %w", addr, err)
		}
		port, err := strconv.Atoi(portStr)
		if err != nil {
			return nil, fmt.Errorf("tsembed: invalid port %q", portStr)
		}
		dialCtx, dialCancel := context.WithTimeout(ctx, defaultDialTimeout)
		defer dialCancel()
		ip, err := resolveHost(dialCtx, s, host)
		if err != nil {
			return nil, err
		}
		return dialNetstackTCP(dialCtx, s, ip, port)
	}
}

// doGet performs a GET on an already-parsed URL, preserving the Host header so
// the server builds absolute redirect/segment URLs against the requested
// hostname rather than the resolved IP.
func doGet(ctx context.Context, client *http.Client, u *urlpkg.URL, host string) (*http.Response, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
	if err != nil {
		return nil, fmt.Errorf("tsembed: build request: %w", err)
	}
	req.Host = host
	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("tsembed: GET failed: %w", err)
	}
	return resp, nil
}

// httpResultFromResponse converts an HTTP response into an [HttpResult],
// capturing the final URL after any redirects followed by the client. Non-2xx
// responses are not treated as errors.
func httpResultFromResponse(resp *http.Response) (*HttpResult, error) {
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("tsembed: read response: %w", err)
	}
	if len(body) == 0 {
		// gomobile maps a Go byte slice with a nil data pointer to Java null
		// (see go_seq_to_java_bytearray), so an empty HTTP body would surface
		// as a null getBody() on the Kotlin side and NPE. Make empty bodies
		// explicitly non-nil so a 204/empty 200 is a ByteArray(0), never null.
		body = make([]byte, 0)
	}
	finalURL := ""
	if resp.Request != nil && resp.Request.URL != nil {
		finalURL = resp.Request.URL.String()
	}
	return &HttpResult{
		StatusCode:  resp.StatusCode,
		ContentType: resp.Header.Get("Content-Type"),
		FinalURL:    finalURL,
		Body:        body,
	}, nil
}

// resolveHost resolves host to an IP. Literal IPs pass through; hostnames are
// resolved against the nameservers configured in the Tailscale admin console
// so that split-DNS domains served by a homelab DNS (such as "omni.corp") work
// over the tunnel.
//
// The query is sent over a netstack socket ([tsnet.Server.ListenPacket]) that
// routes through the tunnel, rather than through the tailnet resolver's
// forwarder: on Android the forwarder reaches upstream resolvers with OS
// sockets (SystemDial), which cannot reach a LAN DNS IP from outside the home
// network unless the OS routing table sends it through a TUN.
//
// Successful tailnet resolutions are cached in memory for the answer TTL
// (clamped to dnsCacheTTL) so HLS playback does not re-resolve the hostname on
// every segment request. Failures and empty answers are never cached.
func resolveHost(ctx context.Context, s *tsnet.Server, host string) (netip.Addr, error) {
	if ip, err := netip.ParseAddr(host); err == nil {
		return ip, nil
	}
	fqdn, err := dnsname.ToFQDN(host)
	if err != nil {
		return netip.Addr{}, fmt.Errorf("tsembed: invalid hostname %q: %w", host, err)
	}
	key := cacheKey(string(fqdn))
	if ip, ok := dnsCacheInstance.get(key); ok {
		debugLogf("dns cache hit for %q", host)
		return ip, nil
	}
	debugLogf("dns cache miss for %q", host)

	// Serialize misses: another goroutine may be resolving the same hostname
	// right now, so after acquiring the lock the cache is checked again and a
	// freshly populated entry is reused instead of querying again.
	dnsResolutionMu.Lock()
	defer dnsResolutionMu.Unlock()
	if ip, ok := dnsCacheInstance.get(key); ok {
		debugLogf("dns cache hit after wait for %q", host)
		return ip, nil
	}

	upstreams, err := dnsUpstreamsFor(s, fqdn)
	if err != nil {
		return netip.Addr{}, err
	}
	if len(upstreams) == 0 {
		return resolveHostSystem(ctx, host)
	}

	q := new(dns.Msg)
	q.SetQuestion(dns.Fqdn(host), dns.TypeA)
	bs, err := q.Pack()
	if err != nil {
		return netip.Addr{}, fmt.Errorf("tsembed: pack dns query: %w", err)
	}

	var lastErr error
	for _, up := range upstreams {
		if err := ctx.Err(); err != nil {
			return netip.Addr{}, fmt.Errorf("tsembed: tailnet DNS lookup for %q: %w", host, err)
		}
		ipp, err := parseUpstream(up.Addr)
		if err != nil {
			lastErr = err
			continue
		}
		answer, err := dnsQuery(ctx, s, ipp, bs)
		if err != nil {
			lastErr = err
			continue
		}
		if len(answer.ips) == 0 {
			lastErr = fmt.Errorf("tsembed: no A record from upstream %s", ipp)
			continue
		}
		logf("dns resolution succeeded: %q -> %v via %s (ttl=%ds)", host, answer.ips, ipp, answer.ttl)
		dnsCacheInstance.put(key, answer.ips, answer.cacheTTL())
		return answer.ips[0], nil
	}
	if lastErr == nil {
		lastErr = errors.New("no usable upstream resolvers")
	}
	logf("dns resolution failed for %q: %v", host, lastErr)
	return netip.Addr{}, fmt.Errorf("tsembed: tailnet DNS lookup for %q: %w", host, lastErr)
}

// tailnetUpstreamsFor reads the tailnet resolvers configured for an FQDN.
func tailnetUpstreamsFor(s *tsnet.Server, fqdn dnsname.FQDN) ([]*dnstype.Resolver, error) {
	mgr := s.Sys().DNSManager.Get()
	if mgr == nil {
		return nil, errors.New("tsembed: tailnet DNS manager unavailable")
	}
	res := mgr.Resolver()
	if res == nil {
		return nil, errors.New("tsembed: tailnet DNS resolver unavailable")
	}
	return res.GetUpstreamResolvers(fqdn), nil
}

// parseUpstream converts a tailnet DNS resolver address to an IP:port. Plain
// IPs imply port 53; DoH/DoH-over-WireGuard schemes are not supported here.
func parseUpstream(addr string) (netip.AddrPort, error) {
	if strings.Contains(addr, "://") {
		return netip.AddrPort{}, fmt.Errorf("unsupported DNS upstream %q", addr)
	}
	host, port := addr, "53"
	if h, p, err := net.SplitHostPort(addr); err == nil {
		host, port = h, p
	}
	ip, err := netip.ParseAddr(host)
	if err != nil {
		return netip.AddrPort{}, fmt.Errorf("parse DNS upstream %q: %w", addr, err)
	}
	portNum, err := strconv.Atoi(port)
	if err != nil || portNum < 1 || portNum > 65535 {
		return netip.AddrPort{}, fmt.Errorf("invalid DNS upstream port %q", port)
	}
	return netip.AddrPortFrom(ip, uint16(portNum)), nil
}

// queryTunnelDNS sends an A query to upstream over the tunnel and returns the
// parsed answer. The netstack socket routes subnet destinations through the
// tailnet, so a homelab DNS reachable only inside the LAN still answers.
//
// Transport policy: private split-DNS upstreams (RFC1918, loopback, IPv6 ULA,
// tailnet CGNAT) are queried TCP-first because the homelab resolver ignores UDP
// over the subnet route (docs/PLAN.md, 2026-08-11); UDP remains a fallback for
// resolvers that only answer UDP. Public upstreams keep the standard
// UDP-then-TCP order, mirroring the official client's forwarder.
func queryTunnelDNS(ctx context.Context, s *tsnet.Server, upstream netip.AddrPort, query []byte) (dnsAnswer, error) {
	var out []byte
	var err error
	if isPrivateUpstream(upstream.Addr()) {
		logf("dns transport for %s: tcp-first (private upstream)", upstream)
		out, err = tcpQueryFn(ctx, s, upstream, query)
		if err != nil {
			debugLogf("dns tcp query to %s failed, falling back to udp: %v", upstream, err)
			out, err = udpQueryFn(ctx, s, upstream, query)
			if err != nil {
				return dnsAnswer{}, err
			}
		}
	} else {
		out, err = udpQueryFn(ctx, s, upstream, query)
		if err != nil {
			debugLogf("dns udp query to %s failed, falling back to tcp: %v", upstream, err)
			out, err = tcpQueryFn(ctx, s, upstream, query)
			if err != nil {
				return dnsAnswer{}, err
			}
		}
	}
	return parseDNSAnswer(query, out, upstream)
}

// isPrivateUpstream reports whether a DNS upstream address is private: RFC1918,
// loopback or IPv6 ULA (netip.IsPrivate), or a tailnet CGNAT address
// (100.64.0.0/10, which netip does not classify as private).
func isPrivateUpstream(ip netip.Addr) bool {
	if ip.IsPrivate() || ip.IsLoopback() {
		return true
	}
	if !ip.Is4() {
		return false
	}
	a := ip.As4()
	return a[0] == 100 && a[1]&0xC0 == 0x40
}

// udpDNSQuery sends a raw DNS query over a netstack UDP socket bound to the
// node's tailnet IP, so the packet travels through the tunnel.
func udpDNSQuery(ctx context.Context, s *tsnet.Server, upstream netip.AddrPort, query []byte) ([]byte, error) {
	network := "udp4"
	ip4, ip6 := s.TailscaleIPs()
	bindAddr := ip4
	if upstream.Addr().Is6() {
		network = "udp6"
		bindAddr = ip6
	}
	if !bindAddr.IsValid() || bindAddr.IsUnspecified() {
		return nil, errors.New("tsembed: node has no tailnet IP to bind the dns socket")
	}
	bindHost := net.JoinHostPort(bindAddr.String(), "0")
	pc, err := s.ListenPacket(network, bindHost)
	if err != nil {
		return nil, fmt.Errorf("tsembed: open tunnel dns socket: %w", err)
	}
	defer pc.Close()

	queryCtx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	if dl, ok := queryCtx.Deadline(); ok {
		if err := pc.SetDeadline(dl); err != nil {
			return nil, fmt.Errorf("tsembed: set dns deadline: %w", err)
		}
	}

	if _, err := pc.WriteTo(query, net.UDPAddrFromAddrPort(upstream)); err != nil {
		return nil, fmt.Errorf("tsembed: write dns query to %s: %w", upstream, err)
	}
	buf := make([]byte, 4096)
	n, _, err := pc.ReadFrom(buf)
	if err != nil {
		return nil, fmt.Errorf("tsembed: read dns answer from %s over udp: %w", upstream, err)
	}
	return buf[:n], nil
}

// tcpDNSQuery sends the query over DNS-over-TCP through the tunnel (2-byte
// length prefix framing), using the same netstack dial path proven for HTTP.
func tcpDNSQuery(ctx context.Context, s *tsnet.Server, upstream netip.AddrPort, query []byte) ([]byte, error) {
	queryCtx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	conn, err := dialNetstackTCP(queryCtx, s, upstream.Addr(), int(upstream.Port()))
	if err != nil {
		return nil, fmt.Errorf("tsembed: dial dns upstream %s over tcp: %w", upstream, err)
	}
	defer conn.Close()

	framed := make([]byte, 2+len(query))
	binary.BigEndian.PutUint16(framed, uint16(len(query)))
	copy(framed[2:], query)
	if _, err := conn.Write(framed); err != nil {
		return nil, fmt.Errorf("tsembed: write dns query to %s over tcp: %w", upstream, err)
	}
	var length uint16
	if err := binary.Read(conn, binary.BigEndian, &length); err != nil {
		return nil, fmt.Errorf("tsembed: read dns length from %s over tcp: %w", upstream, err)
	}
	out := make([]byte, length)
	if _, err := io.ReadFull(conn, out); err != nil {
		return nil, fmt.Errorf("tsembed: read dns answer from %s over tcp: %w", upstream, err)
	}
	return out, nil
}

// parseDNSAnswer extracts all A records from a DNS response, verifying that the
// response matches the query id, and records the smallest advertised TTL.
func parseDNSAnswer(query, out []byte, upstream netip.AddrPort) (dnsAnswer, error) {
	var resp dns.Msg
	if err := resp.Unpack(out); err != nil {
		return dnsAnswer{}, fmt.Errorf("tsembed: parse dns response: %w", err)
	}
	if resp.Id != binary.BigEndian.Uint16(query[:2]) {
		return dnsAnswer{}, fmt.Errorf("tsembed: dns response id mismatch")
	}
	if resp.Rcode != dns.RcodeSuccess {
		return dnsAnswer{}, fmt.Errorf("tsembed: dns response from %s: %s", upstream, dns.RcodeToString[resp.Rcode])
	}
	var ans dnsAnswer
	for _, rr := range resp.Answer {
		a, ok := rr.(*dns.A)
		if !ok {
			continue
		}
		ip, ok := netip.AddrFromSlice(a.A)
		if !ok {
			continue
		}
		ans.ips = append(ans.ips, ip.Unmap())
		if ans.ttl == 0 || a.Hdr.Ttl < ans.ttl {
			ans.ttl = a.Hdr.Ttl
		}
	}
	if len(ans.ips) == 0 {
		return dnsAnswer{}, errors.New("tsembed: no A record in dns answer")
	}
	return ans, nil
}

// dialNetstackTCP dials a TCP connection through the tunnel. The netstack
// dialer routes both tailnet IPs and subnet-routed destinations through the
// tunnel, unlike tsdial.UserDial which on Android falls back to the system
// dialer for non-tailnet IPs unless the node has the "user-dial-routes"
// attribute.
func dialNetstackTCP(ctx context.Context, s *tsnet.Server, ip netip.Addr, port int) (net.Conn, error) {
	ns, ok := s.Sys().Netstack.Get().(*netstack.Impl)
	if !ok || ns == nil {
		return nil, errors.New("tsembed: netstack unavailable")
	}
	return ns.DialContextTCP(ctx, netip.AddrPortFrom(ip, uint16(port)))
}

// resolveHostSystem is a fallback for hostnames not covered by the tailnet DNS
// configuration.
func resolveHostSystem(ctx context.Context, host string) (netip.Addr, error) {
	ips, err := net.DefaultResolver.LookupNetIP(ctx, "ip4", host)
	if err != nil {
		return netip.Addr{}, fmt.Errorf("tsembed: system DNS lookup for %q: %w", host, err)
	}
	if len(ips) == 0 {
		return netip.Addr{}, fmt.Errorf("tsembed: system DNS lookup for %q: no A record", host)
	}
	return ips[0].Unmap(), nil
}

func mustJSON(st status) string {
	b, err := json.Marshal(st)
	if err != nil {
		return fmt.Sprintf(`{"state":%q,"error":%q}`, stateFailed, err.Error())
	}
	return string(b)
}

// ---------------------------------------------------------------------------
// SPIKE (Phase 9.2): HTTP endpoint on the tailnet.
//
// Temporary, DEBUG-only capability: lets the embedded node act as an HTTP
// endpoint on the tailnet so device-to-device request/response flows (for
// example on-demand location) can be validated. It is additive: it does not
// change the client primitives (HttpGet/HttpGetBytes/OpenHttpStream) and is
// not part of the product transport design.
//
// Model: the Kotlin side drives one accept loop per endpoint:
//
//	id, _  := ListenHTTP(port)      // one-time
//	loop:  info, _ := AcceptHTTP(id) // blocks until a request head arrives
//	       RespondHTTP(id, info.Id, status, contentType, body)
//	CloseHTTPEndpoint(id)            // unblocks AcceptHTTP and frees resources
//
// Each request is an independent HTTP exchange; there is no persistent stream.
// ---------------------------------------------------------------------------

// HTTPRequestInfo describes one accepted HTTP request head.
type HTTPRequestInfo struct {
	Id     int64
	Method string
	Path   string
}

// spikeEndpoint is one tailnet HTTP listener plus its in-flight connections.
type spikeEndpoint struct {
	ln     net.Listener
	mu     sync.Mutex
	conns  map[int64]net.Conn
	nextID atomic.Int64
	closed atomic.Bool
}

var (
	spikeEndpointsMu sync.Mutex
	spikeEndpoints   = map[int64]*spikeEndpoint{}
	spikeNextEpID    atomic.Int64
)

// ListenHTTP opens a TCP listener on the tailnet at the given port and
// registers it as an HTTP endpoint. The node must be Running. Returns an
// endpoint id for use with AcceptHTTP/RespondHTTP/CloseHTTPEndpoint.
func ListenHTTP(port int64) (int64, error) {
	mu.Lock()
	s, running := server, started
	mu.Unlock()
	if s == nil || !running {
		return 0, errors.New("tsembed: node not running")
	}
	ln, err := s.Listen("tcp", fmt.Sprintf(":%d", port))
	if err != nil {
		return 0, fmt.Errorf("tsembed: listen on tailnet: %w", err)
	}
	ep := &spikeEndpoint{ln: ln, conns: map[int64]net.Conn{}}
	id := spikeNextEpID.Add(1)
	spikeEndpointsMu.Lock()
	spikeEndpoints[id] = ep
	spikeEndpointsMu.Unlock()
	logf("spike endpoint listening on tailnet port %d (endpoint=%d)", port, id)
	return id, nil
}

// AcceptHTTP blocks until a connection is accepted or the endpoint is closed,
// then reads the HTTP request head (bounded by timeoutMs) and returns it.
// Closing the endpoint with CloseHTTPEndpoint unblocks a pending AcceptHTTP.
func AcceptHTTP(endpointID int64, timeoutMs int64) (*HTTPRequestInfo, error) {
	ep := lookupSpikeEndpoint(endpointID)
	if ep == nil {
		return nil, errors.New("tsembed: unknown endpoint")
	}
	conn, err := ep.ln.Accept()
	if err != nil {
		return nil, err // endpoint closed
	}
	if timeoutMs <= 0 {
		timeoutMs = defaultSpikeHeadTimeoutMs
	}
	if err := conn.SetReadDeadline(time.Now().Add(time.Duration(timeoutMs) * time.Millisecond)); err != nil {
		conn.Close()
		return nil, err
	}
	method, path, err := readSpikeRequestHead(conn)
	if err != nil {
		conn.Close()
		return nil, fmt.Errorf("tsembed: read request head: %w", err)
	}
	info := &HTTPRequestInfo{Id: ep.nextID.Add(1), Method: method, Path: path}
	ep.mu.Lock()
	ep.conns[info.Id] = conn
	ep.mu.Unlock()
	return info, nil
}

// RespondHTTP writes an HTTP response for a previously accepted request and
// closes the connection.
func RespondHTTP(endpointID int64, reqID int64, status int, contentType string, body string) error {
	ep := lookupSpikeEndpoint(endpointID)
	if ep == nil {
		return errors.New("tsembed: unknown endpoint")
	}
	ep.mu.Lock()
	conn, ok := ep.conns[reqID]
	delete(ep.conns, reqID)
	ep.mu.Unlock()
	if !ok {
		return errors.New("tsembed: unknown request")
	}
	defer conn.Close()
	_ = conn.SetWriteDeadline(time.Now().Add(defaultSpikeResponseTimeout))
	reason := spikeStatusText(status)
	if _, err := fmt.Fprintf(conn,
		"HTTP/1.1 %d %s\r\nContent-Type: %s\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s",
		status, reason, contentType, len(body), body); err != nil {
		return fmt.Errorf("tsembed: write response: %w", err)
	}
	return nil
}

// CloseHTTPEndpoint closes the listener and every in-flight connection,
// unblocking any pending AcceptHTTP. Idempotent.
func CloseHTTPEndpoint(endpointID int64) error {
	spikeEndpointsMu.Lock()
	ep, ok := spikeEndpoints[endpointID]
	delete(spikeEndpoints, endpointID)
	spikeEndpointsMu.Unlock()
	if !ok {
		return nil
	}
	ep.close()
	return nil
}

func lookupSpikeEndpoint(id int64) *spikeEndpoint {
	spikeEndpointsMu.Lock()
	defer spikeEndpointsMu.Unlock()
	return spikeEndpoints[id]
}

func (ep *spikeEndpoint) close() {
	if !ep.closed.CompareAndSwap(false, true) {
		return
	}
	ep.ln.Close()
	ep.mu.Lock()
	for _, c := range ep.conns {
		c.Close()
	}
	ep.conns = map[int64]net.Conn{}
	ep.mu.Unlock()
	logf("spike endpoint closed")
}

const (
	defaultSpikeHeadTimeoutMs   = int64(10_000)
	defaultSpikeResponseTimeout = 10 * time.Second
	maxSpikeRequestHeadBytes    = 8192

	// defaultDialTimeout bounds every new connection over the tunnel. Long
	// streaming bodies are unaffected (the dial context is released once the
	// connection is established); short API requests cancel the dial earlier
	// through their own context deadline.
	defaultDialTimeout = 10 * time.Second
)

func readSpikeRequestHead(conn net.Conn) (method, path string, err error) {
	buf := make([]byte, 0, 1024)
	tmp := make([]byte, 4096)
	for {
		n, rerr := conn.Read(tmp)
		if n > 0 {
			buf = append(buf, tmp[:n]...)
			if idx := strings.Index(string(buf), "\r\n\r\n"); idx >= 0 {
				buf = buf[:idx]
				break
			}
			if len(buf) > maxSpikeRequestHeadBytes {
				return "", "", errors.New("request head too large")
			}
		}
		if rerr != nil {
			return "", "", rerr
		}
	}
	firstLine := strings.SplitN(string(buf), "\r\n", 2)[0]
	parts := strings.SplitN(firstLine, " ", 3)
	if len(parts) != 3 {
		return "", "", errors.New("malformed request line")
	}
	return parts[0], parts[1], nil
}

func spikeStatusText(status int) string {
	switch status {
	case 200:
		return "OK"
	case 403:
		return "Forbidden"
	case 404:
		return "Not Found"
	case 500:
		return "Internal Server Error"
	case 503:
		return "Service Unavailable"
	case 504:
		return "Gateway Timeout"
	default:
		return "Status"
	}
}
