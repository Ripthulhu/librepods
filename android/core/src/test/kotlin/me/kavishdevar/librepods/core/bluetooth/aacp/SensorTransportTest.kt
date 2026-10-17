package me.kavishdevar.librepods.core.bluetooth.aacp

import com.google.protobuf.ByteString
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.AACPPacket
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.RTBuddyPacket
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorDataWX
import me.kavishdevar.librepods.core.bluetooth.aacp.types.SensorDataWxBuddyPayload
import me.kavishdevar.librepods.core.data.apple.BuddyState
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SensorTransportTest {
    private fun frame(log: Int = 3) = RTBuddyPacket.create(SensorDataWxBuddyPayload(
        SensorDataWX.newBuilder().setSeq(7).setLogType(log)
            .setAnotherSensorStream(ByteString.copyFrom(ByteArray(58))).build())).rawPacket

    @Test fun framesSurviveEveryReadBoundaryAndAdjacentRecordsWithoutAcceptingOversizedLengths() {
        val record = frame()
        for (split in 1 until record.size) {
            val parser = SensorFrames()
            assertTrue(parser.feed(record.copyOfRange(0, split)).isEmpty())
            assertArrayEquals(record, parser.feed(record.copyOfRange(split, record.size)).single())
        }
        val parser = SensorFrames()
        val oversized = record.copyOf(12).apply { this[10] = 0xff.toByte(); this[11] = 0xff.toByte() }
        val output = parser.feed(byteArrayOf(4, 0, 4) + oversized + record + record)
        assertEquals(2, output.size)
        output.forEach { assertArrayEquals(record, it) }
        assertTrue(parser.feed(ByteArray(65_536)).isEmpty())
        assertArrayEquals(record, parser.feed(record).single())
    }

    @Test fun logFiveUsesTheCapturedTrailerIncludedInTheDeclaredLength() {
        val record = frame(5)
        assertEquals(0xff.toByte(), record.last())
        val decoded = RTBuddyPacket.parse(record).rtBuddyPayload as SensorDataWxBuddyPayload
        assertEquals(5, decoded.data.logType)
        val noTrailer = record.copyOf(record.size - 2).apply {
            val size = this.size - 12; this[10] = size.toByte(); this[11] = (size ushr 8).toByte()
        }
        assertThrows(IllegalArgumentException::class.java) { RTBuddyPacket.parse(noTrailer) }
        assertThrows(IllegalArgumentException::class.java) { RTBuddyPacket.parse(record + byteArrayOf(0)) }
    }

    @Test fun sensorIntentSuspendsAndResumesOnlyTheCapturedTransport() {
        val writes = CopyOnWriteArrayList<AACPPacket>()
        var current = true
        val manager = RTBuddyManager({ writes.add(it); true }, { true }, { current }, { 9 }, {}, {}, {}, {})
        try {
            manager.requestDescriptors(); manager.requestDescriptors()
            assertEquals(1, writes.size)
            assertTrue((writes.single() as RTBuddyPacket).let { (it.rtBuddyPayload as SensorDataWxBuddyPayload).data.hasRequestAllDescriptors() })
            assertTrue(manager.setHeadTrackingEnabled(true))
            fun lastInterval() = ((writes.last() as RTBuddyPacket).rtBuddyPayload as SensorDataWxBuddyPayload)
                .data.serviceSettings.configuration.toByteArray().let { data ->
                    (data[1].toInt() and 255) or ((data[2].toInt() and 255) shl 8)
                }
            assertEquals(40_000, lastInterval())
            manager.setSuspended(true)
            assertEquals(0, lastInterval())
            val paused = writes.size
            manager.setSuspended(true)
            assertEquals(paused, writes.size)
            manager.setSuspended(false)
            assertEquals(40_000, lastInterval())
            current = false
            val retired = writes.size
            assertFalse(manager.setHeadTrackingEnabled(false))
            manager.close()
            assertEquals(retired, writes.size)
        } finally { manager.close() }
    }

    @Test fun failedHeartInitializationStopsWaitingAndUnknownFirmwareDoesNotGuessMotionService() {
        val inactive = CountDownLatch(1)
        val writes = CopyOnWriteArrayList<AACPPacket>()
        val manager = RTBuddyManager({ writes.add(it); true }, { false }, { true }, { null }, {}, {}, {},
            { if (it == BuddyState.INACTIVE) inactive.countDown() })
        try {
            assertTrue(manager.setHeadTrackingEnabled(true))
            assertTrue(writes.isEmpty())
            assertTrue(manager.setHeartRateEnabled(true))
            assertTrue("Failed initialization must leave WAITING", inactive.await(2, TimeUnit.SECONDS))
        } finally { manager.close() }
    }
}
