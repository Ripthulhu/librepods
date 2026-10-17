package me.kavishdevar.librepods.audio

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmWaveWriterTest {
    @Test fun writesPhysicalClockWithoutChangingDecodedSamples() {
        val file = File.createTempFile("microphone-64k-test-", ".wav")
        try {
            val pcm = ByteArray(128_000) { it.toByte() }
            PcmWaveWriter(file, PcmFormat(AirPodsMicrophoneClock.PHYSICAL_RATE, 1)).use { it.write(pcm) }
            val bytes = file.readBytes()
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(64_000, header.getInt(24))
            assertEquals(128_000, header.getInt(28))
            assertEquals(128_000, header.getInt(40))
            assertArrayEquals(pcm, bytes.copyOfRange(44, bytes.size))
        } finally { file.delete() }
    }

    @Test fun writesActualPcmFormatAndFinalSizes() {
        val file = File.createTempFile("microphone-test-", ".wav")
        try {
            PcmWaveWriter(file, PcmFormat(48_000, 1)).use {
                it.write(ByteArray(960))
                assertEquals(960L, it.bytesWritten)
            }
            val bytes = file.readBytes()
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals("RIFF", String(bytes, 0, 4))
            assertEquals(996, header.getInt(4))
            assertEquals(48_000, header.getInt(24))
            assertEquals(96_000, header.getInt(28))
            assertEquals(960, header.getInt(40))
            assertEquals(1004, bytes.size)
            assertEquals(10L, PcmFormat(48_000, 1).durationMs(960))
            assertEquals(10L, PcmFormat(48_000, 2).durationMs(1920))
        } finally { file.delete() }
    }

    @Test fun rejectsPartialPcmFramesAndClosesOnlyOnce() {
        val file = File.createTempFile("microphone-test-", ".wav")
        try {
            val writer = PcmWaveWriter(file, PcmFormat(48_000, 2))
            try {
                writer.write(byteArrayOf(1, 2))
                fail("Partial stereo frame was accepted")
            } catch (_: IllegalArgumentException) { }
            writer.close()
            writer.close()
            assertEquals(44L, file.length())
        } finally { file.delete() }
    }
}
