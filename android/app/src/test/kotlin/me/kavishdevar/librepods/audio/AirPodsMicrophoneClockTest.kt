package me.kavishdevar.librepods.audio

import org.junit.Assert.*
import org.junit.Test

class AirPodsMicrophoneClockTest {
    @Test fun physicalClockHasSevenAndAHalfMillisecondUnits() {
        assertEquals(48_000, AirPodsMicrophoneClock.CODING_RATE)
        assertEquals(7_500L, AirPodsMicrophoneClock.presentationTimeUs(480))
        assertEquals(30_000L, AirPodsMicrophoneClock.presentationTimeUs(4 * 480L))
        assertEquals(1_000_000L, AirPodsMicrophoneClock.presentationTimeUs(64_000))
        assertEquals(1_000L, PcmFormat(AirPodsMicrophoneClock.PHYSICAL_RATE, 1).durationMs(128_000))
    }

    @Test fun measuresDecodedRateAfterWarmupUsingPacketArrivalInsteadOfWorkerTime() {
        val clock = MicrophoneClockStats(0)
        for (packet in 0L..200) {
            clock.packet((0L..3).map { (packet * 4 + it) * 180 },
                300_000_000L + packet * 30_000_000, (packet + 1) * 4 * 480)
        }
        val stats = checkNotNull(clock.snapshot())
        assertEquals(300L, stats.firstPacketMs)
        assertEquals(1_020L, stats.warmupMs)
        assertEquals(4_980L, stats.steadyMs)
        assertEquals(664L, stats.accessUnits)
        assertEquals(318_720L, stats.decodedFrames)
        assertEquals(64_000L, stats.sourceHz)
        assertEquals(119_520L, stats.timestampTicks)
        assertEquals(664L, stats.timestampIntervals)
    }

    @Test fun countsCounterWrapsRepeatsAndResetsWithoutAssigningTimestampUnits() {
        val clock = MicrophoneClockStats(0)
        clock.packet(listOf(1), 0, 0)
        clock.packet(listOf(0xffff_fff5), 1_000_000_000, 480)
        assertNull(clock.snapshot())
        clock.packet(listOf(169, 349, 349, 100, 280), 2_000_000_000, 2880)
        val stats = checkNotNull(clock.snapshot())
        assertEquals(540L, stats.timestampTicks)
        assertEquals(3L, stats.timestampIntervals)
        assertEquals(1L, stats.repeats)
        assertEquals(1L, stats.backwards)
    }
}
