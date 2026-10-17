package me.kavishdevar.librepods.core.bluetooth.aacp

import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorDataWX

/** Device-supplied service numbers are accepted only with an exact supported HID layout. */
internal class SensorReports {
    data class Motion(val horizontal: Int, val vertical: Int)
    data class HeartRate(val bpm: Int, val confidence: Int)
    data class Batch(val mappingChanged: Boolean, val motion: Motion?, val heartRate: HeartRate?)
    var motionService: Int? = null
        private set
    var heartRateService: Int? = null
        private set
    var motionDescriptorSeen = false
        private set
    private var heartDescriptorSeen = false
    private var lastDescriptorSequence: Int? = null

    fun receive(data: SensorDataWX, legacyMotionService: Int?): Batch {
        val changed = discover(data)
        return Batch(changed, motion(data, legacyMotionService), heartRate(data))
    }

    private fun discover(data: SensorDataWX): Boolean {
        if (data.logType !in setOf(1, 3) || data.sensorDescriptorCount > 96) return false
        val announced = data.sensorDescriptorList.mapNotNull { descriptor ->
            val service = descriptor.serviceValue.takeIf { it in 1..127 } ?: return false
            SensorDescriptorLayout.parse(descriptor.sensorDescriptor.toByteArray())?.let { service to it }
        }
        val restart = data.sensorDescriptorCount > 0 && data.seq == 1 && (lastDescriptorSequence ?: 0) > 1
        if ((!restart && announced.isEmpty()) || announced.any { item -> announced.any {
                (item.first == it.first) != (item.second == it.second) } }) return false
        var motion = if (restart) null else motionService
        var heart = if (restart) null else heartRateService
        for ((service, kind) in announced) when (kind) {
            SensorDescriptorLayout.Kind.MOTION -> { if (heart == service) return false; motion = service }
            SensorDescriptorLayout.Kind.HEART_RATE -> { if (motion == service) return false; heart = service }
        }
        val changed = restart || motion != motionService || heart != heartRateService
        motionService = motion
        heartRateService = heart
        motionDescriptorSeen = motionDescriptorSeen || motion != null
        heartDescriptorSeen = heartDescriptorSeen || heart != null
        lastDescriptorSequence = data.seq
        return changed
    }

    private fun motion(data: SensorDataWX, fallback: Int?): Motion? {
        if (data.logType !in setOf(1, 3, 5)) return null
        val service = motionService ?: fallback?.takeUnless { motionDescriptorSeen || it == heartRateService }
            ?: return null
        val packed = data.anotherSensorStream.toByteArray()
        val bytes = if (packed.isNotEmpty() && !data.hasCommand() && motionService != null) {
            if (!((data.logType in setOf(1, 3) && packed.size in setOf(58, 60)) ||
                    (data.logType == 5 && packed.size == 58)) ||
                packed[0].toInt() and 255 != service || packed[1] != 0.toByte()) return null
            packed.copyOfRange(2, packed.size)
        } else if (packed.isEmpty() && data.hasCommand() && data.logType in setOf(1, 3) && data.command.serviceValue == service) {
            data.command.payload.toByteArray()
        } else return null
        if (bytes.size !in setOf(56, 58) || (bytes.size == 56 && (motionService == null || bytes[0] != 1.toByte()))) return null
        fun signed(offset: Int) = ((bytes[offset].toInt() and 255) or
            ((bytes[offset + 1].toInt() and 255) shl 8)).toShort().toInt()
        return Motion(signed(28), signed(30))
    }

    private fun heartRate(data: SensorDataWX): HeartRate? {
        if (data.logType !in setOf(1, 3) || !data.hasCommand()) return null
        val service = data.command.serviceValue
        if (heartRateService != null) {
            if (service != heartRateService) return null
        } else if (heartDescriptorSeen || service !in setOf(19, 20, 84) || service == motionService) return null
        val bytes = data.command.payload.toByteArray()
        if (bytes.size != 18 || bytes[1].toInt() and 255 !in 30..220 || bytes[2].toInt() and 255 < 0x80 ||
            HEART_TAILS.none { it.indices.all { index -> it[index] == bytes[15 + index] } }) return null
        return HeartRate(bytes[1].toInt() and 255, bytes[2].toInt() and 255)
    }

    companion object {
        private val HEART_TAILS = listOf("100000", "100080", "200000", "208000", "200280", "208280")
            .map { tail -> tail.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }
    }
}
