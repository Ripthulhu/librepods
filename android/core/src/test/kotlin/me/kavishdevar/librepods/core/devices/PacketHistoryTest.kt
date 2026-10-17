package me.kavishdevar.librepods.core.devices

import me.kavishdevar.librepods.core.bluetooth.aacp.packet.AACPPacket
import me.kavishdevar.librepods.core.bluetooth.aacp.types.MessageOpcode
import org.junit.Assert.*
import org.junit.Test

class PacketHistoryTest {
    @Test fun exportedHistoryRetainsOnlyTheLatestBoundedNonPrivateRecords() {
        fun packet(opcode: Int, index: Int = 0) = AACPPacket.createUnknownPacket(
            MessageOpcode.fromByte(opcode.toByte()), byteArrayOf(index.toByte()))
        val normal = (0..299).map { packet(9, it) }
        val retained = retainedPacketHistory(normal + listOf(0x31, 0x58, 0x1d, 0x2e, 0x2f, 0x29, 0x0c, 0x10, 0x11, 0x1a).map { packet(it) })
        assertEquals(256, retained.size)
        assertSame(normal[44], retained.first())
        assertSame(normal.last(), retained.last())
        assertTrue(retained.all { it.opcode.value == 9.toByte() })
    }
}
