package org.hearthlane.core.frigate

import org.hearthlane.core.connectivity.TsnetGateway
import org.hearthlane.core.connectivity.HttpStream
import org.hearthlane.core.connectivity.HttpStreamGetter

/**
 * [HttpStreamGetter] that dials exclusively through the embedded tsnet
 * network. Used only for the Tailscale path; it never falls back to the
 * Android network.
 */
class TsnetStreamGetter(private val gateway: TsnetGateway) : HttpStreamGetter {

    override suspend fun open(
        url: String,
        connectTimeoutMs: Long,
        headers: Map<String, String>,
    ): HttpStream = gateway.httpOpenStream(url, connectTimeoutMs, headers)
}