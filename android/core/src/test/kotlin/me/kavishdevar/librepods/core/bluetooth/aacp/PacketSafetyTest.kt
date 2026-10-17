package me.kavishdevar.librepods.core.bluetooth.aacp

import org.junit.Assert.*
import org.junit.Test

class PacketSafetyTest {
    @Test fun rawWritesAndReceivedMessagesRejectShortOrMalformedHeadersWithoutIndexingThem() {
        val message = byteArrayOf(4, 0, 4, 0, 9, 0)
        for (length in 0..5) {
            assertFalse(validRawAacpPacket(message.copyOf(length)))
            assertFalse(validAacpMessage(message.copyOf(length)))
        }
        assertTrue(validRawAacpPacket(message)); assertTrue(validAacpMessage(message))
        for (index in listOf(1, 3, 5)) assertFalse(validRawAacpPacket(message.copyOf().also { it[index] = 1 }))
        for (index in listOf(0, 2)) assertFalse(validAacpMessage(message.copyOf().also { it[index] = 1 }))
        assertFalse(validRawAacpPacket(ByteArray(65_536)))
    }
}
