package dev.ghostguard.service

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the guard is doing right now, and a trace of what it did recently.
 * Exists so a user who sees nothing in the event log can tell *why*: whether
 * the service is alive, which capture path is up, and whether any raw key
 * events are arriving at all before the filter sees them.
 */
object Diagnostics {

    data class State(
        val serviceRunning: Boolean = false,
        val usbStatus: String = "not started",
        val mediaSessionActive: Boolean = false,
        val rawEventsBySource: Map<String, Int> = emptyMap(),
        val trace: List<String> = emptyList(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun note(line: String) {
        val stamped = "${clock.format(Date())}  $line"
        _state.update { it.copy(trace = (it.trace + stamped).takeLast(TRACE_LIMIT)) }
    }

    fun serviceRunning(running: Boolean) {
        _state.update { it.copy(serviceRunning = running) }
        note(if (running) "guard service started" else "guard service stopped")
    }

    fun usbStatus(status: String) {
        if (_state.value.usbStatus == status) return
        _state.update { it.copy(usbStatus = status) }
        note("usb: $status")
    }

    fun mediaSession(active: Boolean) {
        if (_state.value.mediaSessionActive == active) return
        _state.update { it.copy(mediaSessionActive = active) }
        note(if (active) "media session active" else "media session released")
    }

    fun rawEvent(source: String, detail: String) {
        _state.update {
            val counts = it.rawEventsBySource.toMutableMap()
            counts[source] = (counts[source] ?: 0) + 1
            it.copy(rawEventsBySource = counts)
        }
        note("raw $source: $detail")
    }

    private const val TRACE_LIMIT = 200
}
