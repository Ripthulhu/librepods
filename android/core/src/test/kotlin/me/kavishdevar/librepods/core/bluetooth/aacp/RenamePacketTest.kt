package me.kavishdevar.librepods.core.bluetooth.aacp

import me.kavishdevar.librepods.core.bluetooth.aacp.packet.RenamePacket
import org.junit.Assert.*
import org.junit.Test

class RenamePacketTest {
    @Test fun renameUsesUtf8ByteCountAndReservedByteBeforeName() {
        val name = "Dylan’s AirPods 🎧"
        val bytes = name.toByteArray()
        assertArrayEquals(byteArrayOf(1, bytes.size.toByte(), 0) + bytes, RenamePacket.create(name).payload)
    }

    @Test fun rejectsEmptyOverlongControlAndBrokenUnicodeNames() {
        for (name in listOf("", "   ", "a\u0000b", "a\nb", "é".repeat(33), "bad\uD800")) {
            assertTrue(name, runCatching { RenamePacket.create(name) }.isFailure)
        }
        assertEquals(67, RenamePacket.create("é".repeat(32)).payload.size)
    }
}
