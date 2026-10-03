package org.hearthlane.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Boot receiver: restarts the location foreground service after a reboot
 * when the platform still allows it.
 *
 * Platform background: Android 14+ allows BOOT_COMPLETED as a background-start
 * exemption and the `location` FGS type is not part of the Android 15+
 * BOOT_COMPLETED-restricted types, but a location-type FGS started from the
 * background requires ACCESS_BACKGROUND_LOCATION to be granted and the device
 * location switch to be on; otherwise [android.app.Service.startForeground]
 * throws SecurityException. The receiver therefore checks [LocationFgsGate]
 * before touching the service — a blocked start is skipped, never
 * crash-inducing — and force-stop prevents any restart. Behavior must be
 * confirmed on a physical device.
 *
 * The location-sharing opt-in is NOT re-checked here: the start cannot be
 * aborted safely once dispatched, so the decision to stop for an opted-out
 * installation belongs to the service, where it happens after
 * [android.app.Service.startForeground] and therefore cannot crash.
 */
class LocationBootReceiver private constructor(
    private val gate: (Context) -> LocationFgsGate.Evaluation,
) : BroadcastReceiver() {

    constructor() : this(LocationFgsGate::evaluate)

    /** Test-only constructor: fixes the gate evaluation to [fixed]. */
    internal constructor(fixed: LocationFgsGate.Evaluation) : this({ fixed })

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!gate(context).ready) return
        ContextCompat.startForegroundService(
            context,
            LocationForegroundService.intent(
                context,
                PublisherMode.BACKGROUND,
            ),
        )
    }
}