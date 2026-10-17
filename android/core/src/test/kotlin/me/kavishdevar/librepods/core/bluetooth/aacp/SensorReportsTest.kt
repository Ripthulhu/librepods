package me.kavishdevar.librepods.core.bluetooth.aacp

import com.google.protobuf.ByteString
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorCommand
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorDataWX
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorDescriptor
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class SensorReportsTest {
    private fun word(value: Int) = ByteArray(4) { (value ushr (it * 8)).toByte() }
    private fun dictionary(name: String = "devmotion6", vendor: Long = 1452,
                           descriptor: ByteArray = SensorDescriptorLayout.MOTION_DESCRIPTOR,
                           maximum: Long = 191): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xd3.toByte(), 0, 0, 0, 4, 0, 0, 0x81.toByte()))
        fun item(kind: Int, count: Int, bytes: ByteArray) {
            out.write(word((kind shl 24) or count))
            out.write(bytes)
            while (out.size() % 4 != 0) out.write(0)
        }
        fun number(value: Long) = ByteArray(8) { (value ushr (it * 8)).toByte() }
        for ((key, kind, value) in listOf(
            Triple("VendorID", 4, number(vendor)),
            Triple(if (name == "HeartRate") "HeartRateService" else "AccessoryService", 9, (name + '\u0000').toByteArray()),
            Triple("MaxReportSize", 4, number(maximum)),
            Triple("ReportDescriptor", 10, descriptor)
        )) {
            val keyBytes = (key + '\u0000').toByteArray()
            item(9, keyBytes.size, keyBytes)
            item(kind, if (kind == 4) 64 else value.size, value)
        }
        return out.toByteArray()
    }
    private fun announcement(sequence: Int, service: Int, descriptor: ByteArray) = SensorDataWX.newBuilder()
        .setSeq(sequence).setLogType(3).addSensorDescriptor(SensorDescriptor.newBuilder()
            .setServiceValue(service).setSensorDescriptor(ByteString.copyFrom(descriptor))).build()
    private fun command(service: Int, bytes: ByteArray, log: Int = 3) = SensorDataWX.newBuilder()
        .setSeq(10).setLogType(log).setCommand(SensorCommand.newBuilder().setServiceValue(service)
            .setPayload(ByteString.copyFrom(bytes))).build()
    private fun movement(size: Int = 58) = ByteArray(size).apply {
        this[0] = 1; this[28] = 0xfe.toByte(); this[29] = 0xff.toByte()
        this[30] = 0; this[31] = 0x80.toByte()
    }

    @Test fun exactDescriptorDictionariesRejectUnknownVendorsNamesLayoutsAndEveryTruncation() {
        val motion = dictionary()
        assertEquals(SensorDescriptorLayout.Kind.MOTION, SensorDescriptorLayout.parse(motion))
        assertEquals(SensorDescriptorLayout.Kind.HEART_RATE, SensorDescriptorLayout.parse(
            dictionary("HeartRate", descriptor = SensorDescriptorLayout.HEART_DESCRIPTOR, maximum = 601)))
        for (length in motion.indices) assertNull(SensorDescriptorLayout.parse(motion.copyOf(length)))
        for (bad in listOf(dictionary(vendor = 1), dictionary(name = "SPL0"), dictionary(maximum = 190),
            dictionary(descriptor = SensorDescriptorLayout.HEART_DESCRIPTOR),
            dictionary(descriptor = SensorDescriptorLayout.MOTION_DESCRIPTOR.copyOf().also { it[0] = 0 }),
            motion + byteArrayOf(0), motion.copyOf().also { it[4] = 0xff.toByte() })) {
            assertNull(SensorDescriptorLayout.parse(bad))
        }
    }

    @Test fun dynamicServicesAndRestartRetireOldMappingsWithoutRearmingLegacyFallback() {
        val parser = SensorReports()
        assertTrue(parser.receive(announcement(3, 27, dictionary()), 16).mappingChanged)
        assertEquals(27, parser.motionService)
        assertNotNull(parser.receive(command(27, movement()), 16).motion)
        assertNull(parser.receive(command(16, movement()), 16).motion)
        assertFalse(parser.receive(announcement(4, 28, dictionary(vendor = 1)), 16).mappingChanged)
        assertEquals(27, parser.motionService)
        val restart = announcement(1, 30, dictionary("HeartRate",
            descriptor = SensorDescriptorLayout.HEART_DESCRIPTOR, maximum = 601))
        assertTrue(parser.receive(restart, 16).mappingChanged)
        assertNull(parser.motionService)
        assertEquals(30, parser.heartRateService)
        assertTrue(parser.motionDescriptorSeen)
        assertNull(parser.receive(command(16, movement()), 16).motion)
        assertNull(parser.receive(command(27, movement()), 16).motion)
        assertFalse(parser.receive(restart, 16).mappingChanged)

        val unsupportedRestart = SensorReports()
        unsupportedRestart.receive(announcement(3, 27, dictionary()), 16)
        assertTrue(unsupportedRestart.receive(announcement(1, 31, dictionary(name = "SPL0")), 16).mappingChanged)
        assertNull(unsupportedRestart.motionService)
        assertTrue(unsupportedRestart.motionDescriptorSeen)
        assertNull(unsupportedRestart.receive(command(27, movement()), 16).motion)
        assertNull(unsupportedRestart.receive(command(16, movement()), 16).motion)
    }

    @Test fun motionUsesSignedCapturedOffsetsAcrossCommandAndPackedReportForms() {
        val parser = SensorReports()
        val expected = SensorReports.Motion(-2, -32768)
        assertEquals(expected, parser.receive(command(16, movement()), 16).motion)
        assertNull(parser.receive(command(16, movement(56)), 16).motion)
        parser.receive(announcement(3, 27, dictionary()), 16)
        for (size in listOf(56, 58)) {
            assertEquals(expected, parser.receive(command(27, movement(size)), 16).motion)
            val packed = SensorDataWX.newBuilder().setLogType(3)
                .setAnotherSensorStream(ByteString.copyFrom(byteArrayOf(27, 0) + movement(size))).build()
            assertEquals(expected, parser.receive(packed, 16).motion)
        }
        assertNull(parser.receive(command(27, movement(56).also { it[0] = 0 }), 16).motion)
        assertNull(parser.receive(command(27, movement().copyOf(57)), 16).motion)
        assertNull(parser.receive(command(14, ByteArray(23)), 16).motion)
    }

    @Test fun heartRateRequiresTheCurrentServiceConfidenceRangeAndCapturedStatusTail() {
        val parser = SensorReports()
        fun report(confidence: Int = 180, bpm: Int = 72, tail: String = "100080") = ByteArray(18).apply {
            this[1] = bpm.toByte(); this[2] = confidence.toByte()
            tail.chunked(2).map { it.toInt(16).toByte() }.toByteArray().copyInto(this, 15)
        }
        for (tail in listOf("100000", "100080", "200000", "208000", "200280", "208280")) {
            assertEquals(SensorReports.HeartRate(72, 180), parser.receive(command(20, report(tail = tail)), 16).heartRate)
        }
        for (bad in listOf(command(20, report(confidence = 127)), command(20, report(bpm = 29)),
            command(20, report(bpm = 221)), command(20, report(tail = "010203")),
            command(20, report().copyOf(17)), command(20, report(), log = 2), command(14, report()))) {
            assertNull(parser.receive(bad, 16).heartRate)
        }
        parser.receive(announcement(3, 27, dictionary("HeartRate",
            descriptor = SensorDescriptorLayout.HEART_DESCRIPTOR, maximum = 601)), 16)
        assertNull(parser.receive(command(20, report()), 16).heartRate)
        assertEquals(72, parser.receive(command(27, report()), 16).heartRate?.bpm)
    }
}
