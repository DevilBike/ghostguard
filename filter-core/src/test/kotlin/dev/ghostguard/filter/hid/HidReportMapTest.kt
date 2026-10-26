package dev.ghostguard.filter.hid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HidReportMapTest {

    /**
     * The shape almost every USB-C headset uses: a consumer control collection
     * under report id 1, six one-bit buttons and two bits of padding.
     */
    private val headsetDescriptor = byteArrayOf(
        0x05, 0x0C,             // Usage Page (Consumer)
        0x09, 0x01,             // Usage (Consumer Control)
        0xA1.toByte(), 0x01,    // Collection (Application)
        0x85.toByte(), 0x01,    //   Report ID (1)
        0x15, 0x00,             //   Logical Minimum (0)
        0x25, 0x01,             //   Logical Maximum (1)
        0x75, 0x01,             //   Report Size (1)
        0x95.toByte(), 0x06,    //   Report Count (6)
        0x09, 0xCD.toByte(),    //   Usage (Play/Pause)
        0x09, 0xB5.toByte(),    //   Usage (Scan Next Track)
        0x09, 0xB6.toByte(),    //   Usage (Scan Previous Track)
        0x09, 0xE9.toByte(),    //   Usage (Volume Increment)
        0x09, 0xEA.toByte(),    //   Usage (Volume Decrement)
        0x09, 0xB7.toByte(),    //   Usage (Stop)
        0x81.toByte(), 0x02,    //   Input (Data, Variable, Absolute)
        0x95.toByte(), 0x02,    //   Report Count (2)
        0x81.toByte(), 0x03,    //   Input (Constant) -- padding
        0xC0.toByte(),          // End Collection
    )

    @Test
    fun `finds every button bit in order`() {
        val bits = HidReportMap.parse(headsetDescriptor)

        assertEquals(6, bits.size)
        assertTrue(bits.all { it.reportId == 1 && it.usagePage == 0x0C })
        assertEquals(listOf(0, 1, 2, 3, 4, 5), bits.map { it.bitOffset })
        assertEquals(
            listOf(0xCD, 0xB5, 0xB6, 0xE9, 0xEA, 0xB7),
            bits.map { it.usage },
        )
    }

    @Test
    fun `padding bits are skipped but still advance the offset`() {
        val withSecondField = headsetDescriptor.dropLast(1).toByteArray() + byteArrayOf(
            0x75, 0x01,             // Report Size (1)
            0x95.toByte(), 0x01,    // Report Count (1)
            0x09, 0xB0.toByte(),    // Usage (Play)
            0x81.toByte(), 0x02,    // Input (Data, Variable, Absolute)
            0xC0.toByte(),          // End Collection
        )

        val bits = HidReportMap.parse(withSecondField)
        val play = bits.single { it.usage == 0xB0 }
        // Six buttons plus two padding bits precede it.
        assertEquals(8, play.bitOffset)
    }

    @Test
    fun `reads pressed buttons out of a report`() {
        val reader = HidReportReader(HidReportMap.parse(headsetDescriptor), usesReportIds = true)

        // Report id 1, bit 1 set: next track held.
        val report = byteArrayOf(0x01, 0x02)
        assertEquals(listOf(0xB5), reader.pressedUsages(report, report.size).map { it.usage })

        // Nothing held.
        val released = byteArrayOf(0x01, 0x00)
        assertTrue(reader.pressedUsages(released, released.size).isEmpty())
    }

    @Test
    fun `a report for another id yields nothing`() {
        val reader = HidReportReader(HidReportMap.parse(headsetDescriptor), usesReportIds = true)
        val other = byteArrayOf(0x02, 0xFF.toByte())

        assertTrue(reader.pressedUsages(other, other.size).isEmpty())
    }

    @Test
    fun `a truncated descriptor does not throw`() {
        for (end in headsetDescriptor.indices) {
            HidReportMap.parse(headsetDescriptor.copyOfRange(0, end))
        }
    }
}
