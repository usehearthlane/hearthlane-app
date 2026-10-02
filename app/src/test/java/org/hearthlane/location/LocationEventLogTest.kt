package org.hearthlane.location

import org.hearthlane.core.connectivity.TailscaleAuthRequired
import org.hearthlane.core.relay.RelayException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Tests for the structured event formatter and the failure classifier: stable
 * "event=<name> key=value" rendering, null-field omission and privacy-safe
 * field vocabulary. The Android Log call itself is never asserted.
 */
class LocationEventLogTest {

    @Test
    fun `format renders the event name first`() {
        val text = LocationEventLog.format(
            LocationEvent(LocationEventLog.EVENT_FIX_RECEIVED, listOf("provider" to "network")),
        )
        assertEquals("event=location-fix-received provider=network", text)
    }

    @Test
    fun `format omits null fields`() {
        val text = LocationEventLog.format(
            LocationEvent(
                LocationEventLog.EVENT_PUBLISH_DECISION,
                listOf(
                    "decision" to "SKIP",
                    "reason" to PublishDecisionReason.NO_PENDING.name,
                    "distanceMeters" to null,
                ),
            ),
        )
        assertEquals("event=publish-decision decision=SKIP reason=NO_PENDING", text)
    }

    @Test
    fun `format keeps field order and renders floats with one decimal`() {
        val text = LocationEventLog.format(
            LocationEvent(
                LocationEventLog.EVENT_PUBLISH_SUCCESS,
                listOf("transport" to "TAILSCALE", "elapsedMs" to 2_450L),
            ),
        )
        assertEquals("event=publish-success transport=TAILSCALE elapsedMs=2450", text)

        val floatText = LocationEventLog.format(
            LocationEvent(LocationEventLog.EVENT_FIX_RECEIVED, listOf("accuracyMeters" to 12.5f)),
        )
        assertEquals("event=location-fix-received accuracyMeters=12.5", floatText)
    }

    @Test
    fun `format renders booleans and double distances`() {
        val text = LocationEventLog.format(
            LocationEvent(
                LocationEventLog.EVENT_FIX_RECEIVED,
                listOf("hasAccuracy" to false, "ageMs" to 1_500L),
            ),
        )
        assertEquals("event=location-fix-received hasAccuracy=false ageMs=1500", text)

        val distanceText = LocationEventLog.format(
            LocationEvent(LocationEventLog.EVENT_PUBLISH_DECISION, listOf("distanceMeters" to 123.45)),
        )
        assertEquals("event=publish-decision distanceMeters=123.5", distanceText)
    }

    @Test
    fun `classify failure categories from the exception type and message`() {
        assertEquals("HTTP_4XX", LocationEventLog.classifyFailure(RelayException("publish location failed: HTTP 401")))
        assertEquals("HTTP_5XX", LocationEventLog.classifyFailure(RelayException("publish location failed: HTTP 503")))
        assertEquals("TIMEOUT", LocationEventLog.classifyFailure(RelayException("local relay probe timed out after 2000ms")))
        assertEquals("TIMEOUT", LocationEventLog.classifyFailure(IOException("tailscale did not reach Running within 45000ms")))
        assertEquals("DNS", LocationEventLog.classifyFailure(IOException("Unable to resolve host relay.hearthlane.omni.corp")))
        assertEquals("DNS", LocationEventLog.classifyFailure(IOException("UnknownHostException: relay.hearthlane.omni.corp")))
        assertEquals("RELAY", LocationEventLog.classifyFailure(RelayException("relay connection failed: probe failure")))
        assertEquals("NETWORK", LocationEventLog.classifyFailure(IOException("failed to connect to relay")))
        assertEquals("AUTH_REQUIRED", LocationEventLog.classifyFailure(TailscaleAuthRequired("https://login.tailscale.com/a/abc")))
    }

    @Test
    fun `classify prefers the auth category over the message`() {
        val e = TailscaleAuthRequired(null)
        assertEquals("AUTH_REQUIRED", LocationEventLog.classifyFailure(e))
    }

    @Test
    fun `the event vocabulary never includes coordinate fields`() {
        val names = listOf(
            LocationEventLog.EVENT_FIX_RECEIVED,
            LocationEventLog.EVENT_FIX_REJECTED,
            LocationEventLog.EVENT_PENDING_UPDATED,
            LocationEventLog.EVENT_PUBLISH_DECISION,
            LocationEventLog.EVENT_PUBLISH_START,
            LocationEventLog.EVENT_PUBLISH_SUCCESS,
            LocationEventLog.EVENT_PUBLISH_FAILURE,
            LocationEventLog.EVENT_BACKOFF,
        )
        assertTrue(names.all { it.startsWith("location-") || it.startsWith("pending-") || it.startsWith("publish-") || it == "backoff" })

        val sample = LocationEventLog.format(
            LocationEvent(
                LocationEventLog.EVENT_FIX_RECEIVED,
                listOf(
                    "provider" to "network",
                    "accuracyMeters" to 20f,
                    "ageMs" to 1_000L,
                    "hasAccuracy" to true,
                ),
            ),
        )
        assertFalse("no latitude", sample.contains("latitude"))
        assertFalse("no longitude", sample.contains("longitude"))
        assertFalse("no coordinates", sample.contains("-23."))
    }
}