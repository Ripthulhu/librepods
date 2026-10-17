package me.kavishdevar.librepods.shizuku

import org.junit.Assert.*
import org.junit.Test

class CallPcmTest {
    @Test fun preservesSignedMonoSamplesInBothStereoChannels() {
        val mono = byteArrayOf(0, 0, -1, 127, 0, -128, -1, -1, 52, 18)
        val stereo = ByteArray(24) { 99 }
        assertEquals(20, CallPcm.stereoFromMono(mono, mono.size, stereo))
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, -1, 127, -1, 127, 0, -128, 0, -128,
            -1, -1, -1, -1, 52, 18, 52, 18), stereo.copyOf(20))
        assertArrayEquals(ByteArray(4) { 99 }, stereo.copyOfRange(20, 24))
    }

    @Test fun partialBlocksDoNotReuseTrailingCaptureBytes() {
        val output = ByteArray(8)
        assertEquals(4, CallPcm.stereoFromMono(byteArrayOf(1, 2, 3, 4), 2, output))
        assertArrayEquals(byteArrayOf(1, 2, 1, 2, 0, 0, 0, 0), output)
        assertEquals(0, CallPcm.stereoFromMono(byteArrayOf(), 0, output))
    }

    @Test fun refusesPartialSamplesAndUndersizedPlaybackBuffers() {
        assertThrows(IllegalArgumentException::class.java) { CallPcm.stereoFromMono(ByteArray(3), 3, ByteArray(6)) }
        assertThrows(IllegalArgumentException::class.java) { CallPcm.stereoFromMono(ByteArray(4), 4, ByteArray(7)) }
        assertThrows(IllegalArgumentException::class.java) { CallPcm.stereoFromMono(ByteArray(2), 4, ByteArray(8)) }
        assertThrows(IllegalArgumentException::class.java) { CallPcm.stereoFromMono(ByteArray(2), -2, ByteArray(8)) }
    }

    @Test fun selectedRouteRequiresBothA2dpAndExactPairIdentity() {
        assertTrue(CallPcm.isSelectedOutput(8, "12:34:56:78:9a:bc", "12:34:56:78:9A:BC"))
        assertFalse(CallPcm.isSelectedOutput(2, "12:34:56:78:9A:BC", "12:34:56:78:9A:BC"))
        assertFalse(CallPcm.isSelectedOutput(8, "12:34:56:78:9A:BD", "12:34:56:78:9A:BC"))
        assertFalse(CallPcm.isSelectedOutput(8, "", "12:34:56:78:9A:BC"))
    }
}
