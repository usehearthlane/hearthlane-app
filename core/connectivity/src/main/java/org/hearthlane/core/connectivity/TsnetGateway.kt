package org.hearthlane.core.connectivity

/**
 * Application-facing gateway to the embedded Tailscale node. Kept as an
 * interface so the connection strategies can be unit tested without a real
 * node; the production implementation lives in the `native/tailscale` module.
 */
interface TsnetGateway {

    /**
     * Starts the embedded node if needed and blocks until it reaches the
     * connected (Running) state.
     *
     * @throws TailscaleAuthRequired when enrollment is pending.
     * @throws Exception on any other startup/timeout failure.
     */
    suspend fun ensureRunning()

    /**
     * Stops the embedded node if it is currently up. No-op when the node is
     * stopped, disconnected or failed. The connection strategy calls this only
     * after a LOCAL Frigate/relay success, to release a node that a previous
     * remote attempt had started; it is never called while a remote fallback
     * is being decided.
     */
    suspend fun stopIfRunning()

    /**
     * Stops the embedded node and clears its persisted identity, so the next
     * [ensureRunning] re-enrolls interactively. Used by the administrator
     * Settings reset; the enrollment URL is only ever surfaced through the
     * next start's auth-required state.
     */
    suspend fun reset()

    /**
     * Performs an HTTP GET exclusively over the tsnet network. Must never fall
     * back to the normal Android network.
     *
     * @throws Exception on failure.
     */
    suspend fun httpGet(url: String, timeoutMs: Long): String

    /**
     * Performs an HTTP GET exclusively over the tsnet network and returns the
     * full response (status, headers, body). This is the transport primitive
     * used for media/playback requests, which need the body bytes and the
     * final URL rather than a parsed string. Must never fall back to the
     * normal Android network.
     *
     * @throws Exception on transport failure; a non-2xx status is returned,
     *   not thrown.
     */
    suspend fun httpGetBytes(url: String, timeoutMs: Long): HttpBytesResult

    /**
     * Performs an HTTP PUT exclusively over the tsnet network with the given
     * request body, Content-Type and headers. Same transport policy as
     * [httpGetBytes]: never falls back to the normal Android network. A
     * non-2xx status is returned, not thrown.
     *
     * Implementations that do not support writes inherit a failing default,
     * so test doubles of [TsnetGateway] are not forced to model PUT until a
     * test actually exercises it.
     *
     * @throws Exception on transport failure.
     */
    suspend fun httpPut(
        url: String,
        contentType: String,
        body: String,
        headers: Map<String, String>,
        timeoutMs: Long,
    ): HttpBytesResult =
        httpRequest("PUT", url, contentType, body, headers, timeoutMs)

    /**
     * Performs an HTTP request (any method) exclusively over the tsnet network
     * with the given request body, Content-Type and headers. Same transport
     * policy as [httpGetBytes]: never falls back to the normal Android
     * network. A non-2xx status is returned, not thrown. A null [body] sends
     * no request body (GET); a null [contentType] sends no Content-Type.
     *
     * The generic [TsnetGateway] implementations inherit a failing default, so
     * test doubles are not forced to model requests beyond GET until a test
     * actually exercises them.
     *
     * @throws Exception on transport failure.
     */
    suspend fun httpRequest(
        method: String,
        url: String,
        contentType: String?,
        body: String?,
        headers: Map<String, String>,
        timeoutMs: Long,
    ): HttpBytesResult =
        throw UnsupportedOperationException("TsnetGateway.httpRequest is not supported")

    /**
     * Opens an HTTP GET exclusively over the tsnet network as a progressive
     * [HttpStream]. Same transport policy as [httpGetBytes]: never falls back
     * to the normal Android network. A non-2xx status is returned, not thrown.
     *
     * [connectTimeoutMs] bounds establishing the connection and reading the
     * response headers; the body may remain open for the whole playback.
     * [headers] are the request headers Media3 asked to send (for example
     * `Range`, `Accept`, `User-Agent`).
     *
     * Implementations that do not support progressive streams inherit a
     * failing default, so test doubles of [TsnetGateway] are not forced to
     * model streaming until a test actually exercises it.
     */
    suspend fun httpOpenStream(
        url: String,
        connectTimeoutMs: Long,
        headers: Map<String, String> = emptyMap(),
    ): HttpStream =
        throw UnsupportedOperationException("TsnetGateway.httpOpenStream is not supported")
}

/** Raised when the embedded node needs interactive enrollment to proceed. */
class TailscaleAuthRequired(val authUrl: String?) :
    Exception("Tailscale requires authentication")