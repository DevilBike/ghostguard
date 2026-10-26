package dev.ghostguard.ui

import android.app.Activity
import android.os.Bundle
import dev.ghostguard.GhostGuardApp
import dev.ghostguard.service.GuardService

/**
 * Android only grants USB access without asking ("always open with") to an
 * activity that handles USB_DEVICE_ATTACHED. This one exists for that grant
 * alone: it wakes the guard and closes in the same frame, so plugging the
 * earbuds in never brings the app's screen up.
 */
class UsbAttachActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (GhostGuardApp.from(this).settings.config.value.guardEnabled) {
            GuardService.start(this)
        }
        finish()
    }
}
