package dev.ghostguard.service

import android.view.KeyEvent

/**
 * The accessibility service has to answer "should this key be consumed?"
 * synchronously, so it needs a direct line to the running guard rather than a
 * binder round trip. [GuardService] publishes a sink here while it runs.
 */
object GuardBridge {

    /** Returns true when the guard consumed the event. */
    @Volatile
    var volumeKeySink: ((KeyEvent) -> Boolean)? = null
}
