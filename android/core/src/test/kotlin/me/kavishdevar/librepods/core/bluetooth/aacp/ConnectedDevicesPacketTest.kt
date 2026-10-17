package me.kavishdevar.librepods.core.bluetooth.aacp

import me.kavishdevar.librepods.core.bluetooth.aacp.packet.ConnectedDevicesPacket
import org.junit.Assert.*
import org.junit.Test

class ConnectedDevicesPacketTest {
    private val header = byteArrayOf(4, 0, 4, 0, 0x2e, 0, 0, 0)
    private val host = byteArrayOf(1, 2, 3, 4, 5, 6, 1, 0)

    @Test fun preservesHostAddressOrderWithoutGuessingDeviceTypeFromFlags() {
        val report = ConnectedDevicesPacket.parse(header + byteArrayOf(1) + host)
        assertEquals("01:02:03:04:05:06", report.connectedDevices.single().macAddress.value)
        assertNull(report.connectedDevices.single().type)
        assertTrue(ConnectedDevicesPacket.parse(header + byteArrayOf(0)).connectedDevices.isEmpty())
    }

    @Test fun rejectsPartialDuplicateAndUnsignedOversizedCountsInsteadOfPublishingMissingHosts() {
        for (packet in listOf(header, header + byteArrayOf(2) + host,
            header + byteArrayOf(2) + host + host, header + byteArrayOf(0x80.toByte()))) {
            assertThrows(IllegalArgumentException::class.java) { ConnectedDevicesPacket.parse(packet) }
        }
    }
}
