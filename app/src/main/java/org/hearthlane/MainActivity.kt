package org.hearthlane

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.hearthlane.ui.AppRoot
import java.io.File

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Live video must not be interrupted by the screen timeout while the
        // app is in the foreground. Manual lock still works; the lifecycle
        // observer in the live view re-establishes playback on unlock.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val stateDir = File(filesDir, "tailscale").absolutePath
        setContent {
            AppRoot(
                stateDir = stateDir,
                defaultBaseDomain = BuildConfig.HEARTHLANE_BASE_DOMAIN,
                relaySubdomain = BuildConfig.HEARTHLANE_RELAY_SUBDOMAIN,
            )
        }
    }
}