package tsembed

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	urlpkg "net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/miekg/dns"
	"tailscale.com/tsnet"
	"tailscale.com/types/dnstype"
	"tailscale.com/util/dnsname"
)

func TestStartFailureAllowsRetry(t *testing.T) {
	var calls atomic.Int32
	startServer = func(*tsnet.Server) error {
		calls.Add(1)
		return errors.New("simulated start failure")
	}
	defer func() { startServer = func(s *tsnet.Server) error { return s.Start() } }()
	defer Stop()

	dir := t.TempDir()
	if err := Start("test-host", "", dir); err != nil {
		t.Fatalf("first Start returned unexpected error: %v", err)
	}

	if st := waitForState(t, stateFailed); !strings.Contains(st.Error, "simulated start failure") {
		t.Fatalf("Status did not surface the start error, got: %s", st.Error)
	}

	// Status polling must never re-invoke start.
	for i := 0; i < 10; i++ {
		waitForState(t, stateFailed)
	}
	if calls.Load() != 1 {
		t.Fatalf("Status polling re-triggered start: startServer called %d times, want 1", calls.Load())
	}

	if err := Start("test-host", "", dir); err != nil {
		t.Fatalf("retry Start returned error, lifecycle was not reset: %v", err)
	}
	if st := waitForState(t, stateFailed); !strings.Contains(st.Error, "simulated start failure") {
		t.Fatalf("Status did not surface the retried start error, got: %s", st.Error)
	}
	if calls.Load() != 2 {
		t.Fatalf("startServer called %d times, want 2", calls.Load())
	}
}

// lifecycleServer swaps startServer to a no-op "success", letting the lifecycle
// globals (server/startDone/started) be exercised without a real tailnet. The
// underlying tsnet.Server is never started, so its Close is a safe no-op.
func lifecycleServer(t *testing.T) func() {
	t.Helper()
	orig := startServer
	startServer = func(*tsnet.Server) error { return nil }
	return func() { startServer = orig }
}

func TestLifecycleStopThenStartCreatesFreshNode(t *testing.T) {
	defer lifecycleServer(t)()
	defer Stop()

	dir := t.TempDir()
	if err := Start("test-host", "", dir); err != nil {
		t.Fatalf("first Start: %v", err)
	}
	if err := Stop(); err != nil {
		t.Fatalf("first Stop: %v", err)
	}

	// The second Start must create a fresh node, not fail with "already started":
	// Stop must have fully reset the global lifecycle.
	if err := Start("test-host", "", dir); err != nil {
		t.Fatalf("second Start returned %v; lifecycle not reset after Stop", err)
	}
	if err := Stop(); err != nil {
		t.Fatalf("second Stop: %v", err)
	}
}

func TestLifecycleResetIdentityThenStart(t *testing.T) {
	defer lifecycleServer(t)()
	defer Stop()

	dir := t.TempDir()
	if err := Start("test-host", "", dir); err != nil {
		t.Fatalf("first Start: %v", err)
	}
	if err := Stop(); err != nil {
		t.Fatalf("Stop: %v", err)
	}
	// Reset identity: wipe the state dir after Stop (the Kotlin reset() does the
	// same). A subsequent Start must start cleanly, never SIGABRT.
	if err := os.RemoveAll(dir); err != nil {
		t.Fatalf("RemoveAll state dir: %v", err)
	}
	if err := Start("test-host", "", dir); err != nil {
		t.Fatalf("Start after identity reset returned %v", err)
	}
	if err := Stop(); err != nil {
		t.Fatalf("final Stop: %v", err)
	}
}

func TestStopIsIdempotentAcrossFullStop(t *testing.T) {
	defer lifecycleServer(t)()
	defer Stop()

	dir := t.TempDir()
	if err := Start("test-host", "", dir); err != nil {
		t.Fatalf("Start: %v", err)
	}
	if err := Stop(); err != nil {
		t.Fatalf("first Stop: %v", err)
	}
	// A second Stop on an already-stopped node reports a controlled error and
	// never crashes or touches native state.
	if err := Stop(); err == nil {
		t.Fatal("second Stop must report a controlled not-started error")
	}
}

// TestLogStateDirRecreatedAcrossResetAndRestart covers the "no safe place found
// to store log state" panic: the log directory is configured before the node
// starts, the Kotlin reset removes the identity state but preserves logs/, and a
// second Start in the same process must recreate a usable log directory (the
// TS_LOGS_DIR env persists, so Start must not skip recreating the directory).
func TestLogStateDirRecreatedAcrossResetAndRestart(t *testing.T) {
	defer lifecycleServer(t)()
	defer Stop()

	dir := t.TempDir()
	logsDir := filepath.Join(dir, "logs")

	// First Start: the log dir is created and TS_LOGS_DIR is set before the
	// node starts.
	if err := Start("test-host", "", dir); err != nil {
		t.Fatalf("first Start: %v", err)
	}
	if fi, err := os.Stat(logsDir); err != nil || !fi.IsDir() {
		t.Fatalf("log dir %s not created by first Start: %v", logsDir, err)
	}
	if os.Getenv("TS_LOGS_DIR") != logsDir {
		t.Fatalf("TS_LOGS_DIR = %q, want %q", os.Getenv("TS_LOGS_DIR"), logsDir)
	}

	if err := Stop(); err != nil {
		t.Fatalf("Stop: %v", err)
	}

	// Simulate the Kotlin reset: remove the identity state but preserve logs/.
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatalf("ReadDir: %v", err)
	}
	for _, e := range entries {
		if e.Name() != "logs" {
			if err := os.RemoveAll(filepath.Join(dir, e.Name())); err != nil {
				t.Fatalf("remove %s: %v", e.Name(), err)
			}
		}
	}

	// Second Start in the same process: the log dir must exist and be usable
	// before the node starts, so logpolicy never hits "no safe place".
	if err := Start("test-host", "", dir); err != nil {
		t.Fatalf("second Start: %v", err)
	}
	if fi, err := os.Stat(logsDir); err != nil || !fi.IsDir() {
		t.Fatalf("log dir %s not usable after restart: %v", logsDir, err)
	}
	if os.Getenv("TS_LOGS_DIR") != logsDir {
		t.Fatalf("TS_LOGS_DIR after restart = %q, want %q", os.Getenv("TS_LOGS_DIR"), logsDir)
	}
	if err := Stop(); err != nil {
		t.Fatalf("final Stop: %v", err)
	}
}

// TestLifecycleStartPanicIsControlled covers the exact crash class observed in
// "Reconfigure remote access -> Test connection": a panic inside the native
// tsnet lifecycle must become a controlled Failed state (process survives), not
// an unrecovered panic that SIGABRTs the runtime.
func TestLifecycleStartPanicIsControlled(t *testing.T) {
	orig := startServer
	startServer = func(*tsnet.Server) error {
		panic("simulated native tsnet panic")
	}
	defer func() { startServer = orig }()
	defer Stop()

	dir := t.TempDir()
	if err := Start("test-host", "", dir); err != nil {
		t.Fatalf("Start: %v", err)
	}

	st := waitForState(t, stateFailed)
	if st.Error == "" || !strings.Contains(st.Error, "panic") {
		t.Fatalf("panic must surface as a controlled Failed state, got error=%q", st.Error)
	}
	// Stop after the recovered panic must be safe (the globals were cleared).
	if err := Stop(); err == nil {
		t.Fatalf("Stop after a recovered panic must report not-started, got nil")
	}
	// And a retry Start must be allowed again (the lifecycle is fully reset).
	if err := Start("test-host", "", dir); err != nil {
		t.Fatalf("retry Start after recovered panic: %v", err)
	}
	if err := Stop(); err != nil {
		t.Fatalf("final Stop: %v", err)
	}
}

func TestHttpGetRequiresRunningNode(t *testing.T) {
	if _, err := HttpGet("http://frigate:5000/api/version", 1000); err == nil {
		t.Fatal("HttpGet must fail when the node is not running")
	}
}

func TestHttpGetBytesRequiresRunningNode(t *testing.T) {
	if _, err := HttpGetBytes("http://frigate:5000/api/version", 1000); err == nil {
		t.Fatal("HttpGetBytes must fail when the node is not running")
	}
}

func TestHTTPResultFromResponse(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/ok", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
		w.WriteHeader(http.StatusOK)
		w.Write([]byte("#EXTM3U\n"))
	})
	mux.HandleFunc("/missing", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		w.Write([]byte("nope"))
	})
	mux.HandleFunc("/redirect", func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "/ok", http.StatusFound)
	})
	srv := httptest.NewServer(mux)
	defer srv.Close()

	client := newClient(func(ctx context.Context, network, addr string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, network, addr)
	})

	t.Run("captures status content type and body", func(t *testing.T) {
		u, err := urlpkg.Parse(srv.URL + "/ok")
		if err != nil {
			t.Fatal(err)
		}
		resp, err := doGet(context.Background(), client, u, u.Hostname())
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		res, err := httpResultFromResponse(resp)
		if err != nil {
			t.Fatal(err)
		}
		if res.StatusCode != http.StatusOK {
			t.Fatalf("StatusCode = %d, want 200", res.StatusCode)
		}
		if res.ContentType != "application/vnd.apple.mpegurl" {
			t.Fatalf("ContentType = %q, want application/vnd.apple.mpegurl", res.ContentType)
		}
		if string(res.Body) != "#EXTM3U\n" {
			t.Fatalf("Body = %q, want %q", string(res.Body), "#EXTM3U\n")
		}
		if res.FinalURL != srv.URL+"/ok" {
			t.Fatalf("FinalURL = %q, want %q", res.FinalURL, srv.URL+"/ok")
		}
	})

	t.Run("non-2xx is not an error and preserves the status", func(t *testing.T) {
		u, err := urlpkg.Parse(srv.URL + "/missing")
		if err != nil {
			t.Fatal(err)
		}
		resp, err := doGet(context.Background(), client, u, u.Hostname())
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		res, err := httpResultFromResponse(resp)
		if err != nil {
			t.Fatalf("non-2xx must not be an error: %v", err)
		}
		if res.StatusCode != http.StatusNotFound {
			t.Fatalf("StatusCode = %d, want 404", res.StatusCode)
		}
	})

	t.Run("redirects are followed and FinalURL is the final location", func(t *testing.T) {
		u, err := urlpkg.Parse(srv.URL + "/redirect")
		if err != nil {
			t.Fatal(err)
		}
		resp, err := doGet(context.Background(), client, u, u.Hostname())
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		res, err := httpResultFromResponse(resp)
		if err != nil {
			t.Fatal(err)
		}
		if res.StatusCode != http.StatusOK {
			t.Fatalf("StatusCode after redirect = %d, want 200", res.StatusCode)
		}
		if res.FinalURL != srv.URL+"/ok" {
			t.Fatalf("FinalURL after redirect = %q, want %q", res.FinalURL, srv.URL+"/ok")
		}
	})
}

// TestHTTPEmptyBodyIsNeverNull exercises the full request pipeline
// (httpRequestInternal with an injected dial to a local server) for responses
// without content. gomobile maps a Go byte slice with a nil data pointer to
// Java null, so an empty body used to surface as a null getBody() and NPE on
// the Kotlin side; the boundary must always yield a non-nil empty Body.
func TestHTTPEmptyBodyIsNeverNull(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/no-content", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	})
	mux.HandleFunc("/empty-ok", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	})
	mux.HandleFunc("/with-body", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte("hello"))
	})
	mux.HandleFunc("/error-empty", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	})
	srv := httptest.NewServer(mux)
	defer srv.Close()

	dial := func(ctx context.Context, network, addr string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, network, addr)
	}
	client := newClient(dial)
	// s is nil: resolveHost only touches the server for hostname lookups, and
	// the test URL uses a literal IP (127.0.0.1), which passes through.
	run := func(method, path, body string) (*HttpResult, error) {
		return httpRequestInternal(method, srv.URL+path, "application/json", "", body, 2000, client)
	}

	t.Run("204 has a non-nil empty body", func(t *testing.T) {
		res, err := run(http.MethodPut, "/no-content", `{}`)
		if err != nil {
			t.Fatal(err)
		}
		if res.StatusCode != http.StatusNoContent {
			t.Fatalf("StatusCode = %d, want 204", res.StatusCode)
		}
		if res.Body == nil {
			t.Fatal("Body must never be nil for a 204 response")
		}
		if len(res.Body) != 0 {
			t.Fatalf("Body length = %d, want 0", len(res.Body))
		}
	})

	t.Run("200 with empty body has a non-nil empty body", func(t *testing.T) {
		res, err := run(http.MethodPut, "/empty-ok", `{}`)
		if err != nil {
			t.Fatal(err)
		}
		if res.StatusCode != http.StatusOK {
			t.Fatalf("StatusCode = %d, want 200", res.StatusCode)
		}
		if res.Body == nil || len(res.Body) != 0 {
			t.Fatalf("Body = %v, want non-nil empty", res.Body)
		}
	})

	t.Run("200 with body preserves the content", func(t *testing.T) {
		res, err := run(http.MethodGet, "/with-body", "")
		if err != nil {
			t.Fatal(err)
		}
		if string(res.Body) != "hello" {
			t.Fatalf("Body = %q, want %q", string(res.Body), "hello")
		}
	})

	t.Run("error status with empty body preserves status and non-nil body", func(t *testing.T) {
		res, err := run(http.MethodPut, "/error-empty", `{}`)
		if err != nil {
			t.Fatal(err)
		}
		if res.StatusCode != http.StatusInternalServerError {
			t.Fatalf("StatusCode = %d, want 500", res.StatusCode)
		}
		if res.Body == nil || len(res.Body) != 0 {
			t.Fatalf("Body = %v, want non-nil empty", res.Body)
		}
	})
}

func waitForState(t *testing.T, want string) status {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		var st status
		if err := json.Unmarshal([]byte(Status()), &st); err == nil && st.State == want {
			return st
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for state %q, last Status: %s", want, Status())
	return status{}
}

// --- DNS cache and transport policy tests ---

const testUpstreamAddr = "192.168.10.2"

func testUpstream() *dnstype.Resolver { return &dnstype.Resolver{Addr: testUpstreamAddr} }

// resetDNSCache replaces the shared cache with a fresh one so tests do not leak
// entries into each other.
func resetDNSCache(t *testing.T) {
	t.Helper()
	prev := dnsCacheInstance
	dnsCacheInstance = &dnsCache{entries: make(map[string]dnsCacheEntry)}
	t.Cleanup(func() { dnsCacheInstance = prev })
}

// installDNSStubs replaces the resolveHost dependencies with stubs and restores
// the production functions when the test ends. The nil *tsnet.Server is never
// touched because both stubs ignore it.
func installDNSStubs(
	t *testing.T,
	upstreams func(fqdn dnsname.FQDN) ([]*dnstype.Resolver, error),
	query func(ctx context.Context, upstream netip.AddrPort) (dnsAnswer, error),
) {
	t.Helper()
	prevUp := dnsUpstreamsFor
	prevQuery := dnsQuery
	dnsUpstreamsFor = func(_ *tsnet.Server, fqdn dnsname.FQDN) ([]*dnstype.Resolver, error) {
		return upstreams(fqdn)
	}
	dnsQuery = func(ctx context.Context, _ *tsnet.Server, upstream netip.AddrPort, _ []byte) (dnsAnswer, error) {
		return query(ctx, upstream)
	}
	t.Cleanup(func() {
		dnsUpstreamsFor = prevUp
		dnsQuery = prevQuery
	})
}

// captureLogs redirects the standard logger used by logf to a buffer.
func captureLogs(t *testing.T) *bytes.Buffer {
	t.Helper()
	var buf bytes.Buffer
	prev := log.Writer()
	log.SetOutput(&buf)
	t.Cleanup(func() { log.SetOutput(prev) })
	return &buf
}

func mustAddr(s string) netip.Addr { return netip.MustParseAddr(s) }

func TestResolveHostFirstLookupQueriesAndCaches(t *testing.T) {
	resetDNSCache(t)
	var queries int
	installDNSStubs(t,
		func(dnsname.FQDN) ([]*dnstype.Resolver, error) { return []*dnstype.Resolver{testUpstream()}, nil },
		func(context.Context, netip.AddrPort) (dnsAnswer, error) {
			queries++
			return dnsAnswer{ips: []netip.Addr{mustAddr("192.168.10.13")}, ttl: 60}, nil
		},
	)

	ip, err := resolveHost(context.Background(), nil, "site.omni.corp")
	if err != nil {
		t.Fatalf("first resolution failed: %v", err)
	}
	if ip != mustAddr("192.168.10.13") {
		t.Fatalf("first resolution IP = %v, want 192.168.10.13", ip)
	}
	if queries != 1 {
		t.Fatalf("first resolution performed %d queries, want 1", queries)
	}

	ip2, err := resolveHost(context.Background(), nil, "site.omni.corp")
	if err != nil {
		t.Fatalf("cached resolution failed: %v", err)
	}
	if ip2 != ip {
		t.Fatalf("cached resolution IP = %v, want %v", ip2, ip)
	}
	if queries != 1 {
		t.Fatalf("cached resolution must not query again, performed %d queries", queries)
	}
}

func TestResolveHostLogsCacheMissHitAndSuccess(t *testing.T) {
	resetDNSCache(t)
	SetDebugDNS(true)
	t.Cleanup(func() { SetDebugDNS(false) })
	buf := captureLogs(t)
	installDNSStubs(t,
		func(dnsname.FQDN) ([]*dnstype.Resolver, error) { return []*dnstype.Resolver{testUpstream()}, nil },
		func(context.Context, netip.AddrPort) (dnsAnswer, error) {
			return dnsAnswer{ips: []netip.Addr{mustAddr("192.168.10.13")}, ttl: 60}, nil
		},
	)

	if _, err := resolveHost(context.Background(), nil, "site.omni.corp"); err != nil {
		t.Fatal(err)
	}
	if _, err := resolveHost(context.Background(), nil, "site.omni.corp"); err != nil {
		t.Fatal(err)
	}

	got := buf.String()
	for _, want := range []string{
		`dns cache miss for "site.omni.corp"`,
		`dns cache hit for "site.omni.corp"`,
		`dns resolution succeeded: "site.omni.corp" -> [192.168.10.13] via 192.168.10.2:53 (ttl=60s)`,
	} {
		if !strings.Contains(got, want) {
			t.Errorf("logs missing %q; got:\n%s", want, got)
		}
	}
}

func TestResolveHostExpiredEntryQueriesAgain(t *testing.T) {
	resetDNSCache(t)
	var queries int
	installDNSStubs(t,
		func(dnsname.FQDN) ([]*dnstype.Resolver, error) { return []*dnstype.Resolver{testUpstream()}, nil },
		func(context.Context, netip.AddrPort) (dnsAnswer, error) {
			queries++
			return dnsAnswer{ips: []netip.Addr{mustAddr("192.168.10.13")}, ttl: 1}, nil
		},
	)

	for i := 0; i < 2; i++ {
		if _, err := resolveHost(context.Background(), nil, "site.omni.corp"); err != nil {
			t.Fatal(err)
		}
	}
	if queries != 1 {
		t.Fatalf("resolution inside the TTL performed %d queries, want 1", queries)
	}

	time.Sleep(1100 * time.Millisecond)
	if _, err := resolveHost(context.Background(), nil, "site.omni.corp"); err != nil {
		t.Fatal(err)
	}
	if queries != 2 {
		t.Fatalf("resolution after TTL expiry performed %d queries, want 2", queries)
	}
}

func TestResolveHostFailureIsNotCached(t *testing.T) {
	resetDNSCache(t)
	var queries int
	installDNSStubs(t,
		func(dnsname.FQDN) ([]*dnstype.Resolver, error) { return []*dnstype.Resolver{testUpstream()}, nil },
		func(context.Context, netip.AddrPort) (dnsAnswer, error) {
			queries++
			return dnsAnswer{}, errors.New("dial dns upstream over tcp: timeout")
		},
	)

	for i := 0; i < 2; i++ {
		if _, err := resolveHost(context.Background(), nil, "site.omni.corp"); err == nil {
			t.Fatal("failed resolution must return an error")
		}
	}
	if queries != 2 {
		t.Fatalf("failed resolutions must not be cached, performed %d queries, want 2", queries)
	}
}

func TestResolveHostEmptyAnswerIsNotCached(t *testing.T) {
	resetDNSCache(t)
	var queries int
	installDNSStubs(t,
		func(dnsname.FQDN) ([]*dnstype.Resolver, error) { return []*dnstype.Resolver{testUpstream()}, nil },
		func(context.Context, netip.AddrPort) (dnsAnswer, error) {
			queries++
			return dnsAnswer{}, nil
		},
	)

	for i := 0; i < 2; i++ {
		if _, err := resolveHost(context.Background(), nil, "site.omni.corp"); err == nil {
			t.Fatal("empty answer must surface an error")
		}
	}
	if queries != 2 {
		t.Fatalf("empty answers must not be cached, performed %d queries, want 2", queries)
	}
}

func TestResolveHostZeroTTLUsesFallbackTTL(t *testing.T) {
	resetDNSCache(t)
	var queries int
	installDNSStubs(t,
		func(dnsname.FQDN) ([]*dnstype.Resolver, error) { return []*dnstype.Resolver{testUpstream()}, nil },
		func(context.Context, netip.AddrPort) (dnsAnswer, error) {
			queries++
			return dnsAnswer{ips: []netip.Addr{mustAddr("192.168.10.13")}, ttl: 0}, nil
		},
	)

	for i := 0; i < 2; i++ {
		if _, err := resolveHost(context.Background(), nil, "site.omni.corp"); err != nil {
			t.Fatal(err)
		}
	}
	// The homelab resolver advertises TTL 0; the fallback must cache with the
	// conservative fixed TTL instead of disabling the cache.
	if queries != 1 {
		t.Fatalf("a zero-TTL answer must fall back to the fixed cache TTL, performed %d queries, want 1", queries)
	}
}

func TestResolveHostConcurrentMissesQueryOnce(t *testing.T) {
	resetDNSCache(t)
	var queries int
	installDNSStubs(t,
		func(dnsname.FQDN) ([]*dnstype.Resolver, error) { return []*dnstype.Resolver{testUpstream()}, nil },
		func(context.Context, netip.AddrPort) (dnsAnswer, error) {
			queries++
			return dnsAnswer{ips: []netip.Addr{mustAddr("192.168.10.13")}, ttl: 60}, nil
		},
	)

	start := make(chan struct{})
	const n = 8
	errCh := make(chan error, n)
	var wg sync.WaitGroup
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			if _, err := resolveHost(context.Background(), nil, "site.omni.corp"); err != nil {
				errCh <- err
			}
		}()
	}
	close(start)
	wg.Wait()
	close(errCh)
	for err := range errCh {
		t.Fatal(err)
	}
	if queries != 1 {
		t.Fatalf("concurrent misses for the same hostname performed %d queries, want 1 (collapsed)", queries)
	}
}

func TestResolveHostCacheEntriesArePerHostname(t *testing.T) {
	resetDNSCache(t)
	queries := map[string]int{}
	var mu sync.Mutex
	answers := map[string]dnsAnswer{
		"192.168.10.2:53": {ips: []netip.Addr{mustAddr("192.168.10.11")}, ttl: 60},
		"192.168.10.3:53": {ips: []netip.Addr{mustAddr("192.168.10.12")}, ttl: 60},
	}
	upstreamsForHost := map[string]string{
		"a.omni.corp.": "192.168.10.2",
		"b.omni.corp.": "192.168.10.3",
	}
	installDNSStubs(t,
		func(fqdn dnsname.FQDN) ([]*dnstype.Resolver, error) {
			addr, ok := upstreamsForHost[string(fqdn)]
			if !ok {
				return nil, fmt.Errorf("no upstream for %q", fqdn)
			}
			return []*dnstype.Resolver{{Addr: addr}}, nil
		},
		func(_ context.Context, up netip.AddrPort) (dnsAnswer, error) {
			mu.Lock()
			queries[up.String()]++
			mu.Unlock()
			ans, ok := answers[up.String()]
			if !ok {
				return dnsAnswer{}, fmt.Errorf("no answer for %s", up)
			}
			return ans, nil
		},
	)

	ipA, err := resolveHost(context.Background(), nil, "a.omni.corp")
	if err != nil {
		t.Fatal(err)
	}
	ipB, err := resolveHost(context.Background(), nil, "b.omni.corp")
	if err != nil {
		t.Fatal(err)
	}
	if ipA != mustAddr("192.168.10.11") || ipB != mustAddr("192.168.10.12") {
		t.Fatalf("unexpected IPs: a=%v b=%v", ipA, ipB)
	}

	// Case-insensitive, trailing-dot variants share a.omni.corp's entry.
	ipA2, err := resolveHost(context.Background(), nil, "A.Omni.Corp.")
	if err != nil {
		t.Fatal(err)
	}
	if ipA2 != ipA {
		t.Fatalf("normalized hostname returned %v, want %v", ipA2, ipA)
	}

	mu.Lock()
	defer mu.Unlock()
	if queries["192.168.10.2:53"] != 1 {
		t.Fatalf("hostname a resolved %d times, want 1 (cached)", queries["192.168.10.2:53"])
	}
	if queries["192.168.10.3:53"] != 1 {
		t.Fatalf("hostname b resolved %d times, want 1", queries["192.168.10.3:53"])
	}
}

func TestResolveHostLiteralIPSkipsResolver(t *testing.T) {
	resetDNSCache(t)
	prevUp := dnsUpstreamsFor
	prevQuery := dnsQuery
	dnsUpstreamsFor = func(*tsnet.Server, dnsname.FQDN) ([]*dnstype.Resolver, error) {
		t.Fatal("literal IP must not consult the upstream resolver")
		return nil, nil
	}
	dnsQuery = func(context.Context, *tsnet.Server, netip.AddrPort, []byte) (dnsAnswer, error) {
		t.Fatal("literal IP must not query DNS")
		return dnsAnswer{}, nil
	}
	t.Cleanup(func() {
		dnsUpstreamsFor = prevUp
		dnsQuery = prevQuery
	})

	ip, err := resolveHost(context.Background(), nil, "192.168.10.13")
	if err != nil {
		t.Fatal(err)
	}
	if ip != mustAddr("192.168.10.13") {
		t.Fatalf("IP = %v, want 192.168.10.13", ip)
	}
}

func TestDNSCacheConcurrentAccess(t *testing.T) {
	resetDNSCache(t)
	ip := mustAddr("192.168.10.13")
	c := dnsCacheInstance
	c.put("site.omni.corp", []netip.Addr{ip}, time.Minute)

	var wg sync.WaitGroup
	errCh := make(chan error, 8)
	for i := 0; i < 4; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < 500; j++ {
				got, ok := c.get("site.omni.corp")
				if !ok || got != ip {
					errCh <- fmt.Errorf("corrupted read: ok=%v got=%v", ok, got)
					return
				}
			}
		}()
	}
	wg.Add(1)
	go func() {
		defer wg.Done()
		for j := 0; j < 500; j++ {
			c.put(fmt.Sprintf("host-%d.omni.corp", j%16), []netip.Addr{mustAddr("192.168.10.20")}, time.Minute)
		}
	}()
	wg.Wait()
	close(errCh)
	for err := range errCh {
		t.Fatal(err)
	}
}

func TestQueryTunnelDNSTransportPolicy(t *testing.T) {
	ctx := context.Background()
	query := buildQuery(t, "site.omni.corp")
	resp := cannedAAnswer(t, query, "192.168.10.13", 60)

	replaceTransports := func(t *testing.T, udp, tcp func(context.Context, *tsnet.Server, netip.AddrPort, []byte) ([]byte, error)) {
		t.Helper()
		prevU, prevT := udpQueryFn, tcpQueryFn
		udpQueryFn = udp
		tcpQueryFn = tcp
		t.Cleanup(func() { udpQueryFn, tcpQueryFn = prevU, prevT })
	}

	t.Run("private upstream uses tcp first and does not touch udp when it succeeds", func(t *testing.T) {
		var order []string
		replaceTransports(t,
			func(context.Context, *tsnet.Server, netip.AddrPort, []byte) ([]byte, error) {
				order = append(order, "udp")
				t.Fatal("udp must not be attempted when tcp succeeds")
				return nil, nil
			},
			func(context.Context, *tsnet.Server, netip.AddrPort, []byte) ([]byte, error) {
				order = append(order, "tcp")
				return resp, nil
			},
		)
		got, err := queryTunnelDNS(ctx, nil, mustAddrPort("192.168.10.2:53"), query)
		if err != nil {
			t.Fatal(err)
		}
		if len(got.ips) != 1 || got.ips[0] != mustAddr("192.168.10.13") {
			t.Fatalf("answer IPs = %v", got.ips)
		}
		if strings.Join(order, ",") != "tcp" {
			t.Fatalf("transport order = %v, want [tcp]", order)
		}
	})

	t.Run("private upstream falls back to udp when tcp fails", func(t *testing.T) {
		var order []string
		replaceTransports(t,
			func(context.Context, *tsnet.Server, netip.AddrPort, []byte) ([]byte, error) {
				order = append(order, "udp")
				return resp, nil
			},
			func(context.Context, *tsnet.Server, netip.AddrPort, []byte) ([]byte, error) {
				order = append(order, "tcp")
				return nil, errors.New("tcp failed")
			},
		)
		got, err := queryTunnelDNS(ctx, nil, mustAddrPort("192.168.10.2:53"), query)
		if err != nil {
			t.Fatal(err)
		}
		if got.ips[0] != mustAddr("192.168.10.13") {
			t.Fatalf("answer IPs = %v", got.ips)
		}
		if strings.Join(order, ",") != "tcp,udp" {
			t.Fatalf("transport order = %v, want [tcp udp]", order)
		}
	})

	t.Run("public upstream keeps udp first", func(t *testing.T) {
		var order []string
		replaceTransports(t,
			func(context.Context, *tsnet.Server, netip.AddrPort, []byte) ([]byte, error) {
				order = append(order, "udp")
				return resp, nil
			},
			func(context.Context, *tsnet.Server, netip.AddrPort, []byte) ([]byte, error) {
				order = append(order, "tcp")
				t.Fatal("tcp must not be attempted when udp succeeds")
				return nil, nil
			},
		)
		if _, err := queryTunnelDNS(ctx, nil, mustAddrPort("8.8.8.8:53"), query); err != nil {
			t.Fatal(err)
		}
		if strings.Join(order, ",") != "udp" {
			t.Fatalf("transport order = %v, want [udp]", order)
		}
	})

	t.Run("public upstream falls back to tcp when udp fails", func(t *testing.T) {
		var order []string
		replaceTransports(t,
			func(context.Context, *tsnet.Server, netip.AddrPort, []byte) ([]byte, error) {
				order = append(order, "udp")
				return nil, errors.New("udp failed")
			},
			func(context.Context, *tsnet.Server, netip.AddrPort, []byte) ([]byte, error) {
				order = append(order, "tcp")
				return resp, nil
			},
		)
		if _, err := queryTunnelDNS(ctx, nil, mustAddrPort("8.8.8.8:53"), query); err != nil {
			t.Fatal(err)
		}
		if strings.Join(order, ",") != "udp,tcp" {
			t.Fatalf("transport order = %v, want [udp tcp]", order)
		}
	})
}

// buildQuery packs an A query for host.
func buildQuery(t *testing.T, host string) []byte {
	t.Helper()
	m := new(dns.Msg)
	m.SetQuestion(dns.Fqdn(host), dns.TypeA)
	bs, err := m.Pack()
	if err != nil {
		t.Fatalf("pack query: %v", err)
	}
	return bs
}

// cannedAAnswer builds a successful DNS response matching the given query with
// one A record at ip with ttl.
func cannedAAnswer(t *testing.T, query []byte, ip string, ttl uint32) []byte {
	t.Helper()
	var req dns.Msg
	if err := req.Unpack(query); err != nil {
		t.Fatalf("unpack query: %v", err)
	}
	resp := new(dns.Msg)
	resp.SetReply(&req)
	resp.Answer = append(resp.Answer, &dns.A{
		Hdr: dns.RR_Header{Name: req.Question[0].Name, Rrtype: dns.TypeA, Class: dns.ClassINET, Ttl: ttl},
		A:   net.ParseIP(ip).To4(),
	})
	bs, err := resp.Pack()
	if err != nil {
		t.Fatalf("pack answer: %v", err)
	}
	return bs
}

func mustAddrPort(s string) netip.AddrPort { return netip.MustParseAddrPort(s) }

func TestIsPrivateUpstream(t *testing.T) {
	cases := []struct {
		addr string
		want bool
	}{
		{"192.168.10.2", true},
		{"10.0.0.1", true},
		{"172.16.0.1", true},
		{"172.31.255.254", true},
		{"127.0.0.1", true},
		{"100.64.0.1", true},
		{"100.127.255.254", true},
		{"fd7a:115c:a1e0::1", true},
		{"100.63.0.1", false},
		{"100.128.0.1", false},
		{"8.8.8.8", false},
		{"2001:4860:4860::8888", false},
	}
	for _, tc := range cases {
		got := isPrivateUpstream(mustAddr(tc.addr))
		if got != tc.want {
			t.Errorf("isPrivateUpstream(%s) = %v, want %v", tc.addr, got, tc.want)
		}
	}
}

func TestParseDNSAnswerCollectsAllARecordsAndMinTTL(t *testing.T) {
	var req dns.Msg
	req.SetQuestion(dns.Fqdn("site.omni.corp"), dns.TypeA)
	query, err := req.Pack()
	if err != nil {
		t.Fatal(err)
	}
	resp := new(dns.Msg)
	resp.SetReply(&req)
	resp.Answer = []dns.RR{
		&dns.A{Hdr: dns.RR_Header{Name: "site.omni.corp.", Rrtype: dns.TypeA, Class: dns.ClassINET, Ttl: 30}, A: net.ParseIP("192.168.10.11").To4()},
		&dns.A{Hdr: dns.RR_Header{Name: "site.omni.corp.", Rrtype: dns.TypeA, Class: dns.ClassINET, Ttl: 60}, A: net.ParseIP("192.168.10.12").To4()},
	}
	out, err := resp.Pack()
	if err != nil {
		t.Fatal(err)
	}
	ans, err := parseDNSAnswer(query, out, mustAddrPort("192.168.10.2:53"))
	if err != nil {
		t.Fatal(err)
	}
	if len(ans.ips) != 2 {
		t.Fatalf("answer IPs = %v, want 2 records", ans.ips)
	}
	if ans.ips[0] != mustAddr("192.168.10.11") || ans.ips[1] != mustAddr("192.168.10.12") {
		t.Fatalf("answer IPs = %v", ans.ips)
	}
	if ans.ttl != 30 {
		t.Fatalf("answer TTL = %d, want 30 (minimum across records)", ans.ttl)
	}
}

func TestCacheTTLCap(t *testing.T) {
	if got := (dnsAnswer{ttl: 3600}).cacheTTL(); got != dnsCacheTTL {
		t.Fatalf("cacheTTL(3600) = %v, want capped %v", got, dnsCacheTTL)
	}
	if got := (dnsAnswer{ttl: 30}).cacheTTL(); got != 30*time.Second {
		t.Fatalf("cacheTTL(30) = %v, want 30s", got)
	}
	if got := (dnsAnswer{ttl: 0}).cacheTTL(); got != dnsCacheTTL {
		t.Fatalf("cacheTTL(0) = %v, want %v (fallback to fixed TTL)", got, dnsCacheTTL)
	}
}

func TestCacheKeyNormalization(t *testing.T) {
	if cacheKey("SITE.OMNI.CORP.") != "site.omni.corp" {
		t.Fatalf("cacheKey uppercase = %q", cacheKey("SITE.OMNI.CORP."))
	}
	if cacheKey("site.omni.corp") != "site.omni.corp" {
		t.Fatalf("cacheKey plain = %q", cacheKey("site.omni.corp"))
	}
}

// --- Streaming HTTP stream tests ---

// dialReal connects over the host network; used to drive openHttpStream against
// httptest servers without a running node.
func dialReal(ctx context.Context, network, addr string) (net.Conn, error) {
	var d net.Dialer
	return d.DialContext(ctx, network, addr)
}

// realDialClient builds a client whose transport dials the host network, for
// tests that exercise the request flow against httptest servers.
func realDialClient() *http.Client {
	return newClient(dialReal)
}

// openTestStream opens a stream against a live httptest server using the real
// host dialer, so the whole request/stream flow is exercised.
func openTestStream(t *testing.T, url string) *HttpStreamInfo {
	t.Helper()
	info, err := openHttpStream(url, "", 2000, realDialClient())
	if err != nil {
		t.Fatalf("openHttpStream: %v", err)
	}
	return info
}

func TestOpenHttpStreamPreservesMetadataWithoutReadingBody(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/clip", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "video/mp4")
		w.WriteHeader(http.StatusOK)
		w.Write([]byte("moov"))
	})
	srv := httptest.NewServer(mux)
	defer srv.Close()

	info := openTestStream(t, srv.URL+"/clip")
	defer CloseStream(info.Id)

	if info.StatusCode != http.StatusOK {
		t.Fatalf("StatusCode = %d, want 200", info.StatusCode)
	}
	if info.ContentType != "video/mp4" {
		t.Fatalf("ContentType = %q, want video/mp4", info.ContentType)
	}
	if info.FinalURL != srv.URL+"/clip" {
		t.Fatalf("FinalURL = %q, want %q", info.FinalURL, srv.URL+"/clip")
	}

	// The body must not have been consumed by Open: the first read returns it.
	res, err := ReadChunk(info.Id, 1024)
	if err != nil {
		t.Fatalf("first ReadChunk: %v", err)
	}
	if string(res.Data) != "moov" {
		t.Fatalf("first chunk = %q, want %q", res.Data, "moov")
	}
}

func TestStreamReadChunksAndEOF(t *testing.T) {
	const chunkSize = 1024
	const chunks = 5
	mux := http.NewServeMux()
	mux.HandleFunc("/big", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		for i := 0; i < chunks; i++ {
			w.Write(bytes.Repeat([]byte{byte(i)}, chunkSize))
			w.(http.Flusher).Flush() // chunked: no Content-Length
		}
	})
	srv := httptest.NewServer(mux)
	defer srv.Close()

	info := openTestStream(t, srv.URL+"/big")
	defer CloseStream(info.Id)

	var total []byte
	var reads int
	for {
		res, err := ReadChunk(info.Id, 2048)
		if err != nil {
			t.Fatalf("ReadChunk: %v", err)
		}
		if res.EOF {
			break // EOF
		}
		if len(res.Data) > 2048 {
			t.Fatalf("chunk of %d bytes exceeds the requested max", len(res.Data))
		}
		reads++
		total = append(total, res.Data...)
	}
	want := chunks * chunkSize
	if len(total) != want {
		t.Fatalf("total bytes = %d, want %d", len(total), want)
	}
	if reads < 3 {
		t.Fatalf("body was read in %d chunk(s), expected incremental reads", reads)
	}
}

func TestStreamChunkSmallerThanRequested(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/tiny", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte("abcde"))
	})
	srv := httptest.NewServer(mux)
	defer srv.Close()

	info := openTestStream(t, srv.URL+"/tiny")
	defer CloseStream(info.Id)

	res, err := ReadChunk(info.Id, 4096)
	if err != nil {
		t.Fatalf("ReadChunk: %v", err)
	}
	if string(res.Data) != "abcde" {
		t.Fatalf("chunk = %q, want abcde", res.Data)
	}
	res, err = ReadChunk(info.Id, 4096)
	if err != nil {
		t.Fatalf("ReadChunk after EOF: %v", err)
	}
	if !res.EOF {
		t.Fatalf("expected EOF after the final bytes, got %q", res.Data)
	}
	if len(res.Data) != 0 {
		t.Fatalf("expected an empty EOF read, got %q", res.Data)
	}
}

// TestReadChunkDeliversFinalBytesWithEOF exercises the Go io.Reader edge case
// where a reader delivers the FINAL bytes together with io.EOF in a single
// read (n > 0, err == io.EOF): a response without Content-Length and without
// chunked framing (Connection: close) makes net/http return the remaining
// bytes plus io.EOF on the last read. ReadChunk must deliver those bytes and
// only report EOF on the following read — never surface the trailing io.EOF
// as an error across the gomobile boundary.
func TestReadChunkDeliversFinalBytesWithEOF(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer ln.Close()

	body := bytes.Repeat([]byte{0x5A}, 70_000)
	serverDone := make(chan struct{})
	go func() {
		defer close(serverDone)
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		head := make([]byte, 2048)
		conn.Read(head) // request head
		conn.Write([]byte("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n"))
		conn.Write(body)
		// Close WITHOUT Content-Length, chunked framing or a terminator: the
		// client reader must treat the clean close as the end of the body.
	}()

	client := newClient(func(ctx context.Context, network, addr string) (net.Conn, error) {
		return net.Dial("tcp", ln.Addr().String())
	})
	info, err := openHttpStream("http://127.0.0.1:1/clip", "", 2000, client)
	if err != nil {
		t.Fatalf("openHttpStream: %v", err)
	}
	defer CloseStream(info.Id)

	var total []byte
	for {
		res, err := ReadChunk(info.Id, 32*1024)
		if err != nil {
			t.Fatalf("ReadChunk: %v", err)
		}
		if res.EOF {
			break // EOF
		}
		total = append(total, res.Data...)
	}
	<-serverDone
	if !bytes.Equal(total, body) {
		t.Fatalf("received %d bytes, want %d; final bytes must be delivered", len(total), len(body))
	}
}

func TestStreamReadsLargeBodyIncrementally(t *testing.T) {
	const bodySize = 4 * 1024 * 1024
	mux := http.NewServeMux()
	mux.HandleFunc("/large", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		block := bytes.Repeat([]byte{0xAB}, 64*1024)
		for written := 0; written < bodySize; written += len(block) {
			w.Write(block)
		}
	})
	srv := httptest.NewServer(mux)
	defer srv.Close()

	info := openTestStream(t, srv.URL+"/large")
	defer CloseStream(info.Id)

	// Read with a small bounded buffer; the body must be consumed in pieces,
	// never accumulated into a single []byte.
	const maxChunk = 64 * 1024
	var total int
	seen := map[int]bool{}
	for {
		res, err := ReadChunk(info.Id, maxChunk)
		if err != nil {
			t.Fatalf("ReadChunk: %v", err)
		}
		if res.EOF {
			break
		}
		if len(res.Data) > maxChunk {
			t.Fatalf("chunk of %d bytes exceeds the requested max", len(res.Data))
		}
		seen[len(res.Data)] = true
		total += len(res.Data)
	}
	if total != bodySize {
		t.Fatalf("total bytes = %d, want %d", total, bodySize)
	}
	if len(seen) != 1 {
		t.Fatalf("expected uniformly sized chunks, saw sizes %v", seen)
	}
}

func TestStreamHTTPErrorPreservesStatus(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/missing", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		w.Write([]byte("nope"))
	})
	mux.HandleFunc("/broken", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		w.Write([]byte("boom"))
	})
	srv := httptest.NewServer(mux)
	defer srv.Close()

	for _, tc := range []struct {
		path string
		want int
	}{
		{"/missing", http.StatusNotFound},
		{"/broken", http.StatusInternalServerError},
	} {
		info := openTestStream(t, srv.URL+tc.path)
		if info.StatusCode != tc.want {
			t.Fatalf("%s: StatusCode = %d, want %d", tc.path, info.StatusCode, tc.want)
		}
		if err := CloseStream(info.Id); err != nil {
			t.Fatalf("CloseStream: %v", err)
		}
	}
}

func TestStreamTransportErrorIsReturned(t *testing.T) {
	// A dial to a closed port must surface as a transport error on Open.
	_, err := openHttpStream("http://127.0.0.1:1/clip", "", 500, realDialClient())
	if err == nil {
		t.Fatal("openHttpStream to a closed port must return an error")
	}
}

func TestOpenHttpStreamRequiresRunningNode(t *testing.T) {
	if _, err := OpenHttpStream("http://frigate:5000/api/events/x/clip.mp4", "", 1000); err == nil {
		t.Fatal("OpenHttpStream must fail when the node is not running")
	}
}

func TestReadChunkInvalidArguments(t *testing.T) {
	if _, err := ReadChunk(12345, 100); err == nil {
		t.Fatal("ReadChunk with an unknown id must error")
	}
	if _, err := ReadChunk(0, -1); err == nil {
		t.Fatal("ReadChunk with a negative max must error")
	}
}

func TestCloseStreamIsIdempotent(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/clip", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte("x"))
	})
	srv := httptest.NewServer(mux)
	defer srv.Close()

	info := openTestStream(t, srv.URL+"/clip")
	if err := CloseStream(info.Id); err != nil {
		t.Fatalf("first CloseStream: %v", err)
	}
	if err := CloseStream(info.Id); err != nil {
		t.Fatalf("second CloseStream must be idempotent: %v", err)
	}
	if err := CloseStream(999999); err != nil {
		t.Fatalf("CloseStream of an unknown stream must be a no-op: %v", err)
	}
}

func TestStreamReadAfterCloseErrorsControlled(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/clip", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte("x"))
	})
	srv := httptest.NewServer(mux)
	defer srv.Close()

	info := openTestStream(t, srv.URL+"/clip")
	if err := CloseStream(info.Id); err != nil {
		t.Fatal(err)
	}
	// After Close the stream is deregistered; a read reports a controlled error
	// rather than blocking or reading garbage.
	if _, err := ReadChunk(info.Id, 100); err == nil {
		t.Fatal("ReadChunk after CloseStream must report a controlled error")
	}
}

func TestStreamCloseUnblocksPendingRead(t *testing.T) {
	release := make(chan struct{})
	mux := http.NewServeMux()
	mux.HandleFunc("/block", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte("first"))
		w.(http.Flusher).Flush()
		<-release // hold the connection open without sending more data
	})
	srv := httptest.NewServer(mux)
	defer func() { close(release) }()

	info := openTestStream(t, srv.URL+"/block")
	res, err := ReadChunk(info.Id, 1024)
	if err != nil || string(res.Data) != "first" {
		t.Fatalf("first chunk = %q, err = %v", res.Data, err)
	}

	done := make(chan error, 1)
	go func() {
		_, err := ReadChunk(info.Id, 1024) // blocks on the server
		done <- err
	}()
	time.Sleep(100 * time.Millisecond) // let the read block

	if err := CloseStream(info.Id); err != nil {
		t.Fatal(err)
	}
	select {
	case <-done:
		// The pending read must have been unblocked by CloseStream.
	case <-time.After(5 * time.Second):
		t.Fatal("pending read was not unblocked by CloseStream")
	}
}

func TestStreamConcurrentReadsAreSerialized(t *testing.T) {
	const bodySize = 64 * 1024
	mux := http.NewServeMux()
	mux.HandleFunc("/data", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write(bytes.Repeat([]byte{0x42}, bodySize))
	})
	srv := httptest.NewServer(mux)
	defer srv.Close()

	info := openTestStream(t, srv.URL+"/data")
	defer CloseStream(info.Id)

	const readers = 4
	var total atomic.Int64
	var wg sync.WaitGroup
	for i := 0; i < readers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for {
				res, err := ReadChunk(info.Id, 4096)
				if err != nil {
					return // stream closed concurrently is out of scope here
				}
				if res.EOF {
					return
				}
				for _, b := range res.Data {
					if b != 0x42 {
						t.Error("corrupted byte in concurrent read")
						return
					}
				}
				total.Add(int64(len(res.Data)))
			}
		}()
	}
	wg.Wait()
	if total.Load() != bodySize {
		t.Fatalf("concurrent reads produced %d bytes, want %d", total.Load(), bodySize)
	}
}

// --- Shared transport / connection reuse tests ---

// TestSharedClientReusesConnectionsAcrossStreams proves the fix for HLS: with a
// shared http.Client, sequential streams to the same host reuse the pooled
// connection instead of dialing again. This is exactly what the Live HLS path
// needs (one request per playlist/init/segment) and what httpClientFor provides
// in production.
func TestSharedClientReusesConnectionsAcrossStreams(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/a", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte("alpha"))
	})
	mux.HandleFunc("/b", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte("beta"))
	})
	srv := httptest.NewServer(mux)
	defer srv.Close()

	var dials atomic.Int32
	client := newClient(func(ctx context.Context, network, addr string) (net.Conn, error) {
		dials.Add(1)
		return dialReal(ctx, network, addr)
	})

	// First stream: fully read and closed, so the connection returns to the
	// pool.
	infoA, err := openHttpStream(srv.URL+"/a", "", 2000, client)
	if err != nil {
		t.Fatalf("open /a: %v", err)
	}
	for {
		res, err := ReadChunk(infoA.Id, 1024)
		if err != nil {
			t.Fatalf("read /a: %v", err)
		}
		if res.EOF {
			break
		}
	}
	if err := CloseStream(infoA.Id); err != nil {
		t.Fatalf("close /a: %v", err)
	}
	if got := dials.Load(); got != 1 {
		t.Fatalf("first stream dialed %d times, want 1", got)
	}

	// Second stream: the pooled connection is reused, no new dial.
	infoB, err := openHttpStream(srv.URL+"/b", "", 2000, client)
	if err != nil {
		t.Fatalf("open /b: %v", err)
	}
	for {
		res, err := ReadChunk(infoB.Id, 1024)
		if err != nil {
			t.Fatalf("read /b: %v", err)
		}
		if res.EOF {
			break
		}
	}
	if err := CloseStream(infoB.Id); err != nil {
		t.Fatalf("close /b: %v", err)
	}
	if got := dials.Load(); got != 1 {
		t.Fatalf("second stream dialed %d times, want 1 (connection reused)", got)
	}
}

// TestHttpClientForIsPerNodeAndCleared proves the shared client is scoped to
// the exact node: repeated use reuses it, a different node gets a fresh one,
// and clearHTTPClient forces a fresh one (so a restarted node never reuses a
// transport bound to a previous node's netstack).
func TestHttpClientForIsPerNodeAndCleared(t *testing.T) {
	s1 := &tsnet.Server{}
	s2 := &tsnet.Server{}

	c1 := httpClientFor(s1)
	c2 := httpClientFor(s1)
	if c1 != c2 {
		t.Fatal("the same node must reuse the same shared client")
	}

	c3 := httpClientFor(s2)
	if c3 == c1 {
		t.Fatal("a different node must get a fresh client/transport")
	}

	clearHTTPClient()
	c4 := httpClientFor(s1)
	if c4 == c1 {
		t.Fatal("after clearHTTPClient a fresh client must be created")
	}
}

// TestOpenHttpStreamPropagatesHeadersAndContentLength proves the streaming open
// accepts request headers and surfaces Content-Length when the server provides
// it — required for HLS segments and for the DataSource to report a known
// length to Media3.
func TestOpenHttpStreamPropagatesHeadersAndContentLength(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/clip", func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Diag") != "abc" {
			t.Errorf("X-Diag header = %q, want abc", r.Header.Get("X-Diag"))
		}
		w.Header().Set("Content-Length", "4")
		w.WriteHeader(http.StatusOK)
		w.Write([]byte("moov"))
	})
	srv := httptest.NewServer(mux)
	defer srv.Close()

	info, err := openHttpStream(srv.URL+"/clip", `{"X-Diag":"abc"}`, 2000, realDialClient())
	if err != nil {
		t.Fatalf("openHttpStream: %v", err)
	}
	defer CloseStream(info.Id)
	if info.ContentLength != 4 {
		t.Fatalf("ContentLength = %d, want 4", info.ContentLength)
	}
}
