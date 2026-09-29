package org.hearthlane.core.connectivity

/**
 * Logical endpoints of a Hearthlane environment, derived from a single
 * configured base domain. The app never stores or edits two independent
 * service URLs; it only knows the environment and asks this resolver for the
 * addresses it needs to consume.
 *
 * The Nginx Proxy Manager (or equivalent reverse proxy) is the stable border
 * between Hearthlane and the homelab: it routes each subdomain to the internal
 * service. The environment is reached over private transport (home LAN or the
 * Tailscale path), so the derived endpoints use plain HTTP in this phase —
 * never HTTPS, never path prefixes, ports or LAN addresses.
 */
data class HearthlaneEndpoints(
    val frigateBaseUrl: String,
    val relayBaseUrl: String,
)

/**
 * The single place allowed to turn a base domain into concrete endpoints.
 *
 * All scheme/host normalization and validation live here; no caller
 * concatenates `"http://frigate.$domain"` on its own. The derived URLs are
 * plain HTTP (the environment is private: LAN or Tailscale) and never carry a
 * path prefix, so the relay contract (`/devices`, no `/v1`) and the native
 * Frigate paths (`/api/events`, ...) pass through untouched.
 *
 * The relay subdomain is a per-environment configuration value (for example
 * `relay` in prod and `relay-uat` in UAT) supplied by the flavor, never
 * duplicated across call sites. The default keeps the historical `relay.*`
 * layout; callers that know a different relay label pass it explicitly.
 */
object HearthlaneEndpointResolver {

    private const val FRIGATE_SUBDOMAIN = "frigate"
    private const val RELAY_SUBDOMAIN = "relay"
    private const val SCHEME = "http"

    /**
     * Normalizes user input into the canonical base domain, or returns null
     * when the input cannot be used to derive endpoints. Accepted shapes:
     * `hearthlane.example`, `http://hearthlane.example`, `http://hearthlane.example/`.
     * Rejected: blank input, paths, ports, whitespace, scheme remnants and
     * anything that is not a plain hostname.
     */
    fun normalizeBaseDomain(input: String): String? {
        var s = input.trim().lowercase()
        s = s.removePrefix("https://").removePrefix("http://")
        s = s.trimEnd('/')
        s = s.trim()
        if (s.isEmpty()) return null
        if (s.contains(':') || s.contains('/')) return null
        if (s.any { it.isWhitespace() }) return null
        if (s.any { it in "?#@\\" }) return null
        val labels = s.split('.')
        val valid = labels.isNotEmpty() && labels.all { label ->
            label.isNotEmpty() &&
                label.length <= 63 &&
                label.first().isLetterOrDigit() &&
                label.last().isLetterOrDigit() &&
                label.all { it.isLetterOrDigit() || it == '-' }
        }
        if (!valid) return null
        return s
    }

    /**
     * Normalizes a service label (Frigate/relay subdomain) supplied as
     * configuration. A service label is a single host label (letters, digits
     * and hyphens), for example `relay` or `relay-uat`; it must never carry a
     * scheme, path, port or whitespace.
     */
    fun normalizeServiceLabel(input: String): String? {
        val label = input.trim().lowercase()
        val valid = label.isNotEmpty() &&
            label.length <= 63 &&
            label.first().isLetterOrDigit() &&
            label.last().isLetterOrDigit() &&
            label.all { it.isLetterOrDigit() || it == '-' }
        return label.takeIf { valid }
    }

    /** Derives both endpoints from a canonical base domain (see [normalizeBaseDomain]).
     *  [relaySubdomain] selects which relay service label the environment uses. */
    fun resolve(baseDomain: String, relaySubdomain: String = RELAY_SUBDOMAIN): HearthlaneEndpoints? {
        val normalized = normalizeBaseDomain(baseDomain) ?: return null
        val relayLabel = normalizeServiceLabel(relaySubdomain) ?: return null
        return HearthlaneEndpoints(
            frigateBaseUrl = endpoint(FRIGATE_SUBDOMAIN, normalized),
            relayBaseUrl = endpoint(relayLabel, normalized),
        )
    }

    /** Frigate endpoint for a canonical base domain, or null when invalid. */
    fun frigateEndpoint(baseDomain: String): String? =
        normalizeBaseDomain(baseDomain)?.let { endpoint(FRIGATE_SUBDOMAIN, it) }

    /** Relay endpoint for a canonical base domain, or null when invalid. */
    fun relayEndpoint(baseDomain: String, relaySubdomain: String = RELAY_SUBDOMAIN): String? {
        val normalized = normalizeBaseDomain(baseDomain) ?: return null
        val relayLabel = normalizeServiceLabel(relaySubdomain) ?: return null
        return endpoint(relayLabel, normalized)
    }

    private fun endpoint(service: String, baseDomain: String): String =
        "$SCHEME://$service.$baseDomain"
}