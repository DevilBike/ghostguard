package dev.ghostguard.service

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dev.ghostguard.core.ButtonMapping
import dev.ghostguard.filter.Button
import dev.ghostguard.filter.hid.HidBit
import dev.ghostguard.filter.hid.HidReportMap
import dev.ghostguard.filter.hid.HidReportReader

/**
 * The deterministic capture path.
 *
 * When an app claims a USB HID interface with `force`, Android detaches its
 * own driver from it, so the system stops turning that headset's reports into
 * key events altogether. From then on nothing reaches the media stack unless
 * GhostGuard puts it there, which means a ghost press can be dropped outright
 * rather than raced against.
 *
 * Only works for digital USB-C headsets, the ones with a DAC in the plug. A
 * passive adapter presents an analog headset whose buttons never appear on the
 * USB bus; those need [MediaCapture].
 */
class UsbHidCapture(
    private val context: Context,
    private val onEvent: (button: Button, down: Boolean, uptimeMs: Long, deviceName: String) -> Unit,
    private val onStatus: (Status) -> Unit,
) {

    sealed interface Status {
        data object Stopped : Status
        data object NoDeviceFound : Status
        data class AwaitingPermission(val deviceName: String) : Status
        data class Claimed(val deviceName: String, val buttons: List<Button>) : Status
        /** Found a headset but cannot safely take it over; use the media path. */
        data class Unsupported(val deviceName: String, val why: String) : Status
        data class Failed(val why: String) : Status
    }

    private val usbManager = context.getSystemService(UsbManager::class.java)

    private var connection: UsbDeviceConnection? = null
    private var claimed: UsbInterface? = null
    private var readerThread: Thread? = null
    @Volatile private var running = false

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_PERMISSION) return
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            if (device != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                open(device)
            } else {
                onStatus(Status.Failed("USB access was declined"))
            }
        }
    }

    fun start() {
        ContextCompat.registerReceiver(
            context,
            permissionReceiver,
            IntentFilter(ACTION_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        scan()
    }

    /** Look for a headset and take it over, asking for USB access if needed. */
    fun scan() {
        if (running) return

        val device = usbManager.deviceList.values.firstOrNull { it.hidInterface() != null }
        if (device == null) {
            onStatus(Status.NoDeviceFound)
            return
        }

        if (!hasRecordPermission(context)) {
            // Android would show "not granted access to record" and refuse.
            onStatus(Status.Failed("needs the microphone permission to open ${device.displayName()}"))
            return
        }

        if (usbManager.hasPermission(device)) {
            open(device)
        } else {
            onStatus(Status.AwaitingPermission(device.displayName()))
            val intent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_PERMISSION).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            usbManager.requestPermission(device, intent)
        }
    }

    fun stop() {
        running = false
        readerThread?.join(500)
        readerThread = null

        connection?.let { conn ->
            claimed?.let { conn.releaseInterface(it) }
            conn.close()
        }
        connection = null
        claimed = null

        runCatching { context.unregisterReceiver(permissionReceiver) }
        onStatus(Status.Stopped)
    }

    private fun open(device: UsbDevice) {
        val name = device.displayName()
        val iface = device.hidInterface() ?: run {
            onStatus(Status.Unsupported(name, "no HID interface"))
            return
        }
        val endpoint = iface.inEndpoint() ?: run {
            onStatus(Status.Unsupported(name, "no input endpoint"))
            return
        }

        val conn = usbManager.openDevice(device) ?: run {
            onStatus(Status.Failed("could not open $name"))
            return
        }

        // force = true detaches Android's own HID driver, which is the whole
        // point: it stops the system seeing these buttons behind our back.
        if (!conn.claimInterface(iface, true)) {
            conn.close()
            onStatus(Status.Failed("another driver holds $name"))
            return
        }

        val descriptor = readReportDescriptor(conn, iface)
        val bits = descriptor?.let { HidReportMap.parse(it) }.orEmpty()
        val known = bits.filter { it.toButton() != Button.UNKNOWN }
        Diagnostics.note(
            "$name: interface ${iface.id} claimed, report descriptor " +
                (descriptor?.let { "${it.size} bytes: ${it.toHex()}" } ?: "unreadable") +
                "; ${bits.size} one-bit inputs, recognised: " +
                known.joinToString { "page %02x usage %02x".format(it.usagePage, it.usage) }
        )
        if (known.isEmpty()) {
            // Taking over an interface whose buttons we cannot decode would
            // leave the user with no working buttons at all. Hand it back.
            conn.releaseInterface(iface)
            conn.close()
            onStatus(Status.Unsupported(name, "button layout not recognised"))
            return
        }

        connection = conn
        claimed = iface
        running = true
        onStatus(Status.Claimed(name, known.map { it.toButton() }.distinct()))

        val reader = HidReportReader(known, usesReportIds = known.any { it.reportId != 0 })
        readerThread = Thread({ readLoop(conn, endpoint, reader, name) }, "ghostguard-hid").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun readLoop(
        conn: UsbDeviceConnection,
        endpoint: UsbEndpoint,
        reader: HidReportReader,
        deviceName: String,
    ) {
        val buffer = ByteArray(maxOf(endpoint.maxPacketSize, 8))
        var held = emptySet<Button>()

        while (running) {
            val read = conn.bulkTransfer(endpoint, buffer, buffer.size, READ_TIMEOUT_MS)
            if (read <= 0) continue // timeout: normal, nothing was pressed

            val now = SystemClock.uptimeMillis()
            val pressed = reader.pressedUsages(buffer, read)
                .map { it.toButton() }
                .filterTo(HashSet()) { it != Button.UNKNOWN }

            for (button in pressed - held) onEvent(button, true, now, deviceName)
            for (button in held - pressed) onEvent(button, false, now, deviceName)
            held = pressed
        }
    }

    /** Standard GET_DESCRIPTOR request for the interface's report descriptor. */
    private fun readReportDescriptor(conn: UsbDeviceConnection, iface: UsbInterface): ByteArray? {
        val buffer = ByteArray(1024)
        val length = conn.controlTransfer(
            UsbConstants.USB_DIR_IN or REQ_TYPE_STANDARD_INTERFACE,
            REQUEST_GET_DESCRIPTOR,
            DESCRIPTOR_TYPE_REPORT shl 8,
            iface.id,
            buffer,
            buffer.size,
            CONTROL_TIMEOUT_MS,
        )
        return if (length > 0) buffer.copyOf(length) else null
    }

    private fun HidBit.toButton(): Button = when (usagePage) {
        USAGE_PAGE_CONSUMER -> ButtonMapping.fromConsumerUsage(usage)
        USAGE_PAGE_TELEPHONY -> ButtonMapping.fromTelephonyUsage(usage)
        else -> Button.UNKNOWN
    }

    private fun UsbDevice.hidInterface(): UsbInterface? =
        (0 until interfaceCount)
            .map { getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_HID && it.inEndpoint() != null }

    private fun UsbInterface.inEndpoint(): UsbEndpoint? =
        (0 until endpointCount)
            .map { getEndpoint(it) }
            .firstOrNull {
                it.direction == UsbConstants.USB_DIR_IN &&
                    (it.type == UsbConstants.USB_ENDPOINT_XFER_INT ||
                        it.type == UsbConstants.USB_ENDPOINT_XFER_BULK)
            }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it) }

    private fun UsbDevice.displayName(): String =
        productName ?: "USB device ${String.format("%04x:%04x", vendorId, productId)}"

    companion object {
        /**
         * Android will not grant USB access to a device with an audio input
         * unless the app holds RECORD_AUDIO, even though we never record.
         */
        fun hasRecordPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        private const val ACTION_PERMISSION = "dev.ghostguard.USB_PERMISSION"
        private const val READ_TIMEOUT_MS = 250
        private const val CONTROL_TIMEOUT_MS = 2_000
        private const val REQ_TYPE_STANDARD_INTERFACE = 0x01
        private const val REQUEST_GET_DESCRIPTOR = 0x06
        private const val DESCRIPTOR_TYPE_REPORT = 0x22
        private const val USAGE_PAGE_CONSUMER = 0x0C
        private const val USAGE_PAGE_TELEPHONY = 0x0B
    }
}
