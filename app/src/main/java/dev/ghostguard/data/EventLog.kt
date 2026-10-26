package dev.ghostguard.data

import dev.ghostguard.filter.Button
import dev.ghostguard.filter.Emission
import dev.ghostguard.filter.Reason
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LogEntry(
    val wallClockMs: Long,
    val button: Button,
    val allowed: Boolean,
    val reason: Reason,
    /** Set when observe-only mode let through something it would have blocked. */
    val shadowed: Boolean,
    val holdMs: Long?,
    val source: String,
    val deviceName: String,
    /** Set for connection events, which are not button presses at all. */
    val message: String? = null,
) {
    fun toCsvRow(): String {
        val stamp = TIMESTAMP.format(Date(wallClockMs))
        val verdict = when {
            message != null -> "connection"
            shadowed -> "would-block"
            allowed -> "allowed"
            else -> "blocked"
        }
        return listOf(
            stamp,
            button.name,
            verdict,
            message?.replace(',', ' ') ?: reason.name,
            holdMs?.toString() ?: "",
            source,
            deviceName.replace(',', ' '),
        ).joinToString(",")
    }

    private companion object {
        val TIMESTAMP: SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }
}

/**
 * A bounded, in-memory record of recent decisions, mirrored to a CSV file so a
 * run can be shared or looked at after the fact.
 */
class EventLog(private val file: File, private val capacity: Int = 500) {

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    @Synchronized
    fun record(emission: Emission, source: String, deviceName: String) {
        val entry = when (emission) {
            is Emission.Accepted -> LogEntry(
                wallClockMs = System.currentTimeMillis(),
                button = emission.button,
                allowed = true,
                reason = emission.shadowedReason ?: emission.reason,
                shadowed = emission.shadowedReason != null,
                holdMs = emission.holdMs,
                source = source,
                deviceName = deviceName,
            )

            is Emission.Blocked -> LogEntry(
                wallClockMs = System.currentTimeMillis(),
                button = emission.button,
                allowed = false,
                reason = emission.reason,
                shadowed = false,
                holdMs = emission.holdMs,
                source = source,
                deviceName = deviceName,
            )
        }

        append(entry)
    }

    private fun append(entry: LogEntry) {
        _entries.value = (_entries.value + entry).takeLast(capacity)
        runCatching {
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.writeText("time,button,verdict,reason,hold_ms,source,device\n")
            }
            file.appendText(entry.toCsvRow() + "\n")
        }
    }

    /** Record something that happened to the connection rather than a press. */
    @Synchronized
    fun recordConnection(message: String, deviceName: String) {
        append(
            LogEntry(
                wallClockMs = System.currentTimeMillis(),
                button = Button.UNKNOWN,
                allowed = true,
                reason = Reason.CLEAN,
                shadowed = false,
                holdMs = null,
                source = "connection",
                deviceName = deviceName,
                message = message,
            )
        )
    }

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
        runCatching { file.delete() }
    }

    fun exportFile(): File = file
}
