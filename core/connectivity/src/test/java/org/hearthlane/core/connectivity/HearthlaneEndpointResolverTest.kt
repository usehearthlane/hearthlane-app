package org.hearthlane.core.connectivity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [HearthlaneEndpointResolver]: single base-domain input produces the
 * canonical Frigate and Relay HTTP endpoints, normalization is centralized, and
 * invalid input is rejected instead of producing a broken URL. The relay
 * endpoint is never prefixed with `/v1` and no path is ever injected — Frigate
 * keeps its native paths and the relay contract (`/devices`) passes through
 * untouched.
 */
class HearthlaneEndpointResolverTest {

    @Test
    fun `normal domain produces frigate and relay endpoints`() {
        val endpoints = HearthlaneEndpointResolver.resolve("hearthlane.example")!!

        assertEquals("http://frigate.hearthlane.example", endpoints.frigateBaseUrl)
        assertEquals("http://relay.hearthlane.example", endpoints.relayBaseUrl)
    }

    @Test
    fun `target domain produces http endpoints without hardcoding`() {
        val endpoints = HearthlaneEndpointResolver.resolve("hearthlane.omni.corp")!!

        assertEquals("http://frigate.hearthlane.omni.corp", endpoints.frigateBaseUrl)
        assertEquals("http://relay.hearthlane.omni.corp", endpoints.relayBaseUrl)
    }

    @Test
    fun `trailing slash is stripped`() {
        val endpoints = HearthlaneEndpointResolver.resolve("hearthlane.example/")!!

        assertEquals("http://frigate.hearthlane.example", endpoints.frigateBaseUrl)
        assertEquals("http://relay.hearthlane.example", endpoints.relayBaseUrl)
    }

    @Test
    fun `surrounding spaces are trimmed`() {
        val endpoints = HearthlaneEndpointResolver.resolve("  hearthlane.example  ")!!

        assertEquals("http://frigate.hearthlane.example", endpoints.frigateBaseUrl)
    }

    @Test
    fun `https scheme prefix is normalized away`() {
        val endpoints = HearthlaneEndpointResolver.resolve("https://hearthlane.example")!!

        assertEquals("http://frigate.hearthlane.example", endpoints.frigateBaseUrl)
        assertEquals("http://relay.hearthlane.example", endpoints.relayBaseUrl)
    }

    @Test
    fun `http scheme prefix is normalized away`() {
        val endpoints = HearthlaneEndpointResolver.resolve("http://hearthlane.example")!!

        assertEquals("http://frigate.hearthlane.example", endpoints.frigateBaseUrl)
        assertEquals("http://relay.hearthlane.example", endpoints.relayBaseUrl)
    }

    @Test
    fun `scheme is never duplicated`() {
        val endpoints = HearthlaneEndpointResolver.resolve("http://http://hearthlane.example")
        assertNull("a scheme remnant after http:// must be rejected", endpoints)
    }

    @Test
    fun `scheme plus trailing slash is normalized`() {
        val endpoints = HearthlaneEndpointResolver.resolve("http://hearthlane.example/")!!

        assertEquals("http://frigate.hearthlane.example", endpoints.frigateBaseUrl)
    }

    @Test
    fun `hostname is lowercased`() {
        val endpoints = HearthlaneEndpointResolver.resolve("Hearthlane.Example")!!

        assertEquals("http://frigate.hearthlane.example", endpoints.frigateBaseUrl)
    }

    @Test
    fun `multi label domain is supported`() {
        val endpoints = HearthlaneEndpointResolver.resolve("hearthlane.omni.corp")!!

        assertEquals("http://frigate.hearthlane.omni.corp", endpoints.frigateBaseUrl)
        assertEquals("http://relay.hearthlane.omni.corp", endpoints.relayBaseUrl)
    }

    @Test
    fun `blank input is rejected`() {
        assertNull(HearthlaneEndpointResolver.normalizeBaseDomain(""))
        assertNull(HearthlaneEndpointResolver.normalizeBaseDomain("   "))
    }

    @Test
    fun `path is rejected`() {
        assertNull("a path must never be part of the base domain", HearthlaneEndpointResolver.normalizeBaseDomain("hearthlane.example/path"))
        assertNull(HearthlaneEndpointResolver.normalizeBaseDomain("http://hearthlane.example/frigate"))
    }

    @Test
    fun `port is rejected`() {
        assertNull("the product never knows service ports", HearthlaneEndpointResolver.normalizeBaseDomain("hearthlane.example:8080"))
    }

    @Test
    fun `whitespace inside the host is rejected`() {
        assertNull(HearthlaneEndpointResolver.normalizeBaseDomain("hearthlane example"))
    }

    @Test
    fun `query fragment and userinfo are rejected`() {
        assertNull(HearthlaneEndpointResolver.normalizeBaseDomain("hearthlane.example?x=1"))
        assertNull(HearthlaneEndpointResolver.normalizeBaseDomain("hearthlane.example#frag"))
        assertNull(HearthlaneEndpointResolver.normalizeBaseDomain("user@hearthlane.example"))
    }

    @Test
    fun `invalid label characters are rejected`() {
        assertNull(HearthlaneEndpointResolver.normalizeBaseDomain("hearthlane_example"))
        assertNull(HearthlaneEndpointResolver.normalizeBaseDomain("hearthlane.example_"))
    }

    @Test
    fun `leading or trailing hyphens in a label are rejected`() {
        assertNull(HearthlaneEndpointResolver.normalizeBaseDomain("-hearthlane.example"))
        assertNull(HearthlaneEndpointResolver.normalizeBaseDomain("hearthlane-.example"))
    }

    @Test
    fun `single label host is accepted`() {
        assertEquals("hearthlane", HearthlaneEndpointResolver.normalizeBaseDomain("hearthlane"))
        val endpoints = HearthlaneEndpointResolver.resolve("hearthlane")!!
        assertEquals("http://frigate.hearthlane", endpoints.frigateBaseUrl)
    }

    @Test
    fun `resolve and single endpoint helpers agree`() {
        val base = "hearthlane.example"
        val endpoints = HearthlaneEndpointResolver.resolve(base)!!

        assertEquals(HearthlaneEndpointResolver.frigateEndpoint(base), endpoints.frigateBaseUrl)
        assertEquals(HearthlaneEndpointResolver.relayEndpoint(base), endpoints.relayBaseUrl)
    }

    @Test
    fun `frigate endpoint carries no path`() {
        val frigate = HearthlaneEndpointResolver.frigateEndpoint("hearthlane.example")!!

        // No path prefix: the URL is exactly scheme + host, so native Frigate
        // paths append cleanly (/api/events) with no /frigate segment.
        assertEquals("http://frigate.hearthlane.example", frigate)
        assertEquals("http://frigate.hearthlane.example/api/events", "$frigate/api/events")
    }

    @Test
    fun `relay endpoint uses devices directly without any prefix`() {
        val relay = HearthlaneEndpointResolver.relayEndpoint("hearthlane.example")!!

        // The relay contract reaches the relay as /devices with no path prefix.
        assertEquals("http://relay.hearthlane.example", relay)
        assertEquals("http://relay.hearthlane.example/devices", "$relay/devices")
    }

    @Test
    fun `relay endpoint never carries a v1 path prefix`() {
        val relay = HearthlaneEndpointResolver.relayEndpoint("hearthlane.example")!!

        assertEquals("http://relay.hearthlane.example", relay)
        assertFalse(relay.contains("/v1"))
    }

    @Test
    fun `derived endpoints carry no path`() {
        val endpoints = HearthlaneEndpointResolver.resolve("hearthlane.example")!!

        // No path prefix: the URL is exactly scheme + host, nothing after it.
        assertEquals("http://frigate.hearthlane.example", endpoints.frigateBaseUrl)
        assertEquals("http://relay.hearthlane.example", endpoints.relayBaseUrl)
    }

    @Test
    fun `resolve honors an environment relay subdomain while Frigate stays shared`() {
        // UAT layout: same base domain as PROD (Frigate is shared), only the
        // relay service label differs.
        val uat = HearthlaneEndpointResolver.resolve("hearthlane.omni.corp", "relay-uat")!!

        assertEquals("http://frigate.hearthlane.omni.corp", uat.frigateBaseUrl)
        assertEquals("http://relay-uat.hearthlane.omni.corp", uat.relayBaseUrl)
    }

    @Test
    fun `relayEndpoint honors an environment relay subdomain`() {
        assertEquals(
            "http://relay-uat.hearthlane.omni.corp",
            HearthlaneEndpointResolver.relayEndpoint("hearthlane.omni.corp", "relay-uat"),
        )
    }

    @Test
    fun `normalizeServiceLabel accepts a hyphenated label and trims case`() {
        assertEquals("relay-uat", HearthlaneEndpointResolver.normalizeServiceLabel("  Relay-UAT  "))
        assertEquals("relay", HearthlaneEndpointResolver.normalizeServiceLabel("relay"))
    }

    @Test
    fun `normalizeServiceLabel rejects schemes paths ports and blanks`() {
        assertNull(HearthlaneEndpointResolver.normalizeServiceLabel(""))
        assertNull(HearthlaneEndpointResolver.normalizeServiceLabel("   "))
        assertNull(HearthlaneEndpointResolver.normalizeServiceLabel("http://relay"))
        assertNull(HearthlaneEndpointResolver.normalizeServiceLabel("relay.example"))
        assertNull(HearthlaneEndpointResolver.normalizeServiceLabel("relay:8080"))
        assertNull(HearthlaneEndpointResolver.normalizeServiceLabel("relay/path"))
        assertNull(HearthlaneEndpointResolver.normalizeServiceLabel("-relay"))
        assertNull(HearthlaneEndpointResolver.normalizeServiceLabel("relay-"))
    }

    @Test
    fun `resolve rejects an invalid relay subdomain`() {
        assertNull(HearthlaneEndpointResolver.resolve("hearthlane.example", "relay/path"))
    }
}