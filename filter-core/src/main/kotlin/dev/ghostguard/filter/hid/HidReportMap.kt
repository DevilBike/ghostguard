package dev.ghostguard.filter.hid

/** One single-bit control inside an input report. */
data class HidBit(
    val reportId: Int,
    /** Bit offset from the start of the report body, excluding any report id. */
    val bitOffset: Int,
    val usagePage: Int,
    val usage: Int,
)

/**
 * Just enough of a HID report descriptor parser to find the button bits of a
 * headset: the single-bit input fields and the usage each one carries.
 *
 * Multi-bit fields (array indices, absolute values) are skipped rather than
 * misread. If a headset turns out to use one, the app falls back to its learn
 * mode, where the user presses each button and the bit is recorded directly.
 */
object HidReportMap {

    private const val TYPE_MAIN = 0
    private const val TYPE_GLOBAL = 1
    private const val TYPE_LOCAL = 2

    fun parse(descriptor: ByteArray): List<HidBit> {
        val bits = ArrayList<HidBit>()

        var usagePage = 0
        var reportSize = 0
        var reportCount = 0
        var reportId = 0
        val localUsages = ArrayList<Int>()
        val bitOffsets = HashMap<Int, Int>()
        var pendingMin: Int? = null

        var i = 0
        while (i < descriptor.size) {
            val prefix = descriptor[i].toInt() and 0xFF
            i++

            // Long items exist in the spec but no real device emits them.
            if (prefix == 0xFE) {
                if (i >= descriptor.size) break
                val dataSize = descriptor[i].toInt() and 0xFF
                i += 2 + dataSize
                continue
            }

            val sizeCode = prefix and 0x03
            val size = if (sizeCode == 3) 4 else sizeCode
            val type = (prefix shr 2) and 0x03
            val tag = (prefix shr 4) and 0x0F
            if (i + size > descriptor.size) break

            var value = 0
            for (b in 0 until size) {
                value = value or ((descriptor[i + b].toInt() and 0xFF) shl (8 * b))
            }
            i += size

            when (type) {
                TYPE_GLOBAL -> when (tag) {
                    0x0 -> usagePage = value and 0xFFFF
                    0x7 -> reportSize = value
                    0x8 -> reportId = value and 0xFF
                    0x9 -> reportCount = value
                }

                TYPE_LOCAL -> when (tag) {
                    0x0 -> localUsages += if (size == 4) value else (usagePage shl 16) or (value and 0xFFFF)
                    0x1 -> pendingMin = if (size == 4) value else (usagePage shl 16) or (value and 0xFFFF)
                    0x2 -> {
                        val max = if (size == 4) value else (usagePage shl 16) or (value and 0xFFFF)
                        val min = pendingMin
                        if (min != null && max >= min && max - min < 1024) {
                            for (u in min..max) localUsages += u
                        }
                        pendingMin = null
                    }
                }

                TYPE_MAIN -> {
                    if (tag == 0x8) { // Input
                        val constant = (value and 0x01) != 0
                        var offset = bitOffsets.getOrDefault(reportId, 0)
                        if (reportSize == 1 && !constant) {
                            for (n in 0 until reportCount) {
                                val usage = localUsages.getOrNull(n)
                                if (usage != null) {
                                    bits += HidBit(
                                        reportId = reportId,
                                        bitOffset = offset + n,
                                        usagePage = (usage ushr 16) and 0xFFFF,
                                        usage = usage and 0xFFFF,
                                    )
                                }
                            }
                        }
                        offset += reportSize * reportCount
                        bitOffsets[reportId] = offset
                    }
                    // Any main item clears the local item state.
                    localUsages.clear()
                    pendingMin = null
                }
            }
        }

        return bits
    }
}

/**
 * Reads the state of the mapped bits out of a raw input report.
 *
 * @param usesReportIds true when the device prefixes reports with a report id
 *   byte, which is the case whenever the descriptor declares more than one.
 */
class HidReportReader(bits: List<HidBit>, private val usesReportIds: Boolean) {

    private val byReport: Map<Int, List<HidBit>> = bits.groupBy { it.reportId }

    /** Returns the usages currently held down in [report]. */
    fun pressedUsages(report: ByteArray, length: Int): List<HidBit> {
        if (length <= 0) return emptyList()

        val reportId = if (usesReportIds) report[0].toInt() and 0xFF else 0
        val body = if (usesReportIds) 1 else 0
        val candidates = byReport[reportId] ?: return emptyList()

        return candidates.filter { bit ->
            val index = body + bit.bitOffset / 8
            index < length && (report[index].toInt() shr (bit.bitOffset % 8)) and 0x01 == 1
        }
    }
}
