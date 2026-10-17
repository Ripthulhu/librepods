package me.kavishdevar.librepods.audio

import org.junit.Assert.*
import org.junit.Test

class MicrophonePacketTest {
    private fun packet(vararg records: ByteArray) = ByteArray(22).also {
        byteArrayOf(4, 0, 4, 0, 0x58, 0, 1, 0).copyInto(it)
    } + records.fold(byteArrayOf()) { bytes, record -> bytes + record }

    private fun record(timestamp: Long, bytes: ByteArray) = ByteArray(5).also {
        for (index in 0..3) it[index] = (timestamp ushr (index * 8)).toByte()
        it[4] = bytes.size.toByte()
    } + bytes

    @Test fun parsesVariableCountsUnsignedLengthsAndCounterWrap() {
        val result = MicrophonePacket.parse(packet(
            record(0xffff_ffffL, ByteArray(255) { it.toByte() }),
            record(0, byteArrayOf(1, 2)),
            record(0, byteArrayOf(3)),
        ))
        assertEquals(3, result.size)
        assertEquals(0xffff_ffffL, result[0].timestamp)
        assertEquals(255, result[0].bytes.size)
        assertArrayEquals(byteArrayOf(1, 2), result[1].bytes)
        assertEquals(0L, result[2].timestamp)
    }

    @Test fun rejectsEveryTruncatedSingleRecord() {
        val complete = packet(record(123, byteArrayOf(1, 2, 3)))
        for (length in 0 until complete.size) assertInvalid(complete.copyOf(length))
        assertEquals(1, MicrophonePacket.parse(complete).size)
    }

    @Test fun aBadTailRejectsTheWholeSdu() {
        val good = packet(record(10, byteArrayOf(1)))
        for (tail in listOf(byteArrayOf(1), ByteArray(5), byteArrayOf(0, 0, 0, 0, 2, 1)))
            assertInvalid(good + tail)
    }

    @Test fun microphoneControlsArePrivateButAreNotAudio() {
        val stop = byteArrayOf(4, 0, 4, 0, 0x58, 0, 0, 0, 2, 0, 3, 1)
        assertTrue(MicrophonePacket.isMicrophoneMessage(stop))
        assertFalse(MicrophonePacket.isAudio(stop))
        assertInvalid(stop)
        val wrongHeader = packet(record(0, byteArrayOf(1))).apply { this[5] = 1 }
        assertFalse(MicrophonePacket.isMicrophoneMessage(wrongHeader))
        assertInvalid(wrongHeader)
    }

    private fun assertInvalid(bytes: ByteArray) {
        try {
            MicrophonePacket.parse(bytes)
            fail("Accepted malformed microphone packet of ${bytes.size} bytes")
        } catch (_: IllegalArgumentException) { }
    }
}
