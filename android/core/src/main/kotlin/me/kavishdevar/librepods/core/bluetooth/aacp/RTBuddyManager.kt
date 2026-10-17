package me.kavishdevar.librepods.core.bluetooth.aacp

import com.google.protobuf.ByteString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.AACPPacket
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.RTBuddyPacket
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.RequestAllDescriptors
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorDataWX
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorServiceSetting
import me.kavishdevar.librepods.core.bluetooth.aacp.rtbuddy.proto.SensorServiceType
import me.kavishdevar.librepods.core.bluetooth.aacp.types.SensorDataWxBuddyPayload
import me.kavishdevar.librepods.core.data.apple.BuddyState
import me.kavishdevar.librepods.core.devices.PacketDestination
import kotlin.time.Duration

/** One typed sensor session; every write belongs to the socket captured by its callbacks. */
internal class RTBuddyManager(
    private val sendPacket: (AACPPacket) -> Boolean,
    private val sendRaw: (ByteArray) -> Boolean,
    private val isCurrent: () -> Boolean,
    private val firmwareMajor: () -> Int?,
    private val onMotion: (SensorReports.Motion) -> Unit,
    private val onHeartRate: (SensorReports.HeartRate) -> Unit,
    private val onMotionState: (BuddyState) -> Unit,
    private val onHeartRateState: (BuddyState) -> Unit,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val frames = SensorFrames()
    private val reports = SensorReports()
    private var sequence = 0
    private var descriptorsRequested = false
    private var motionWanted = false
    private var heartWanted = false
    private var suspended = false
    private var closed = false
    private var motionLive: Int? = null
    private var motionNative = false
    private var motionSince = 0L
    private var lastMotion = 0L
    private var lastHeart = 0L
    private var heartJob: Job? = null
    private var heartEpoch = 0L

    init {
        scope.launch {
            while (isActive && isCurrent()) {
                delay(250)
                synchronized(this@RTBuddyManager) {
                    if (closed) return@synchronized
                    if (motionWanted && !suspended) {
                        if (motionLive == null) startMotion()
                        if (now() - maxOf(motionSince, lastMotion) >= 5_000) {
                            motionWanted = false
                            stopMotion()
                            onMotionState(BuddyState.INACTIVE)
                        }
                    }
                }
            }
            close()
        }
    }

    @Synchronized
    fun requestDescriptors() {
        if (closed || descriptorsRequested || !isCurrent()) return
        descriptorsRequested = true
        sendSensorData(SensorDataWX.newBuilder().setRequestAllDescriptors(RequestAllDescriptors.getDefaultInstance()).build())
    }

    @Synchronized
    fun feed(chunk: ByteArray) {
        if (closed || !isCurrent()) return
        for (frame in frames.feed(chunk)) {
            val packet = try { RTBuddyPacket.parse(frame) } catch (_: Exception) { continue }
            val data = (packet.rtBuddyPayload as? SensorDataWxBuddyPayload)?.data ?: continue
            val batch = reports.receive(data, legacyMotion())
            if (batch.mappingChanged) {
                // The previous address can now name another sensor; never send it a delayed Stop.
                motionLive = null
                motionSince = now()
                lastMotion = 0
                lastHeart = 0
                if (motionWanted) onMotionState(BuddyState.WAITING)
                if (heartWanted) onHeartRateState(BuddyState.WAITING)
                if (motionWanted && !suspended) startMotion()
            }
            if (suspended) continue
            if (motionWanted && batch.motion != null) {
                lastMotion = now()
                onMotionState(BuddyState.ACTIVE)
                onMotion(batch.motion)
            }
            if (heartWanted && batch.heartRate != null) {
                lastHeart = now()
                onHeartRateState(BuddyState.ACTIVE)
                onHeartRate(batch.heartRate)
            }
        }
    }

    @Synchronized
    fun setHeadTrackingEnabled(enabled: Boolean): Boolean {
        if (closed || !isCurrent()) return false
        motionWanted = enabled
        if (enabled) {
            motionSince = now()
            lastMotion = 0
            onMotionState(BuddyState.WAITING)
            if (!suspended) startMotion()
        } else {
            stopMotion()
            onMotionState(BuddyState.INACTIVE)
        }
        return true
    }

    @Synchronized
    fun setHeartRateEnabled(enabled: Boolean): Boolean {
        if (closed || !isCurrent()) return false
        heartWanted = enabled
        heartJob?.cancel()
        heartJob = null
        if (enabled) {
            onHeartRateState(BuddyState.WAITING)
            if (!suspended) startHeartCampaign()
        } else {
            stopHeartWire()
            onHeartRateState(BuddyState.INACTIVE)
        }
        return true
    }

    @Synchronized
    fun setSuspended(value: Boolean) {
        if (closed || suspended == value) return
        suspended = value
        if (value) {
            stopMotion()
            heartJob?.cancel()
            heartJob = null
            if (heartWanted) stopHeartWire()
            if (motionWanted) onMotionState(BuddyState.WAITING)
            if (heartWanted) onHeartRateState(BuddyState.WAITING)
        } else {
            motionSince = now()
            lastMotion = 0
            if (motionWanted) startMotion()
            if (heartWanted) startHeartCampaign()
        }
    }

    private fun legacyMotion(): Int? = if (reports.motionDescriptorSeen) null else firmwareMajor()?.let { if (it >= 8) 16 else 14 }

    private fun startMotion() {
        if (motionLive != null || suspended || !motionWanted || !isCurrent()) return
        val service = reports.motionService ?: legacyMotion()?.takeUnless { it == reports.heartRateService } ?: return
        val native = reports.motionService != null
        if ((native && !command(service, 1, report = 4)) || !command(service, if (native) 20_000 else 40_000)) {
            motionWanted = false
            onMotionState(BuddyState.INACTIVE)
            return
        }
        motionLive = service
        motionNative = native
        motionSince = now()
    }

    private fun stopMotion() {
        val service = motionLive ?: return
        if (service == (reports.motionService ?: legacyMotion())) {
            if (motionNative) command(service, 10, report = 4)
            command(service, 0)
        }
        motionLive = null
        lastMotion = 0
    }

    private fun startHeartCampaign() {
        val epoch = ++heartEpoch
        heartJob?.cancel()
        heartJob = scope.launch {
            fun wanted() = synchronized(this@RTBuddyManager) {
                epoch == heartEpoch && !closed && heartWanted && !suspended && isCurrent()
            }
            try {
                repeat(2) {
                    if (!wanted()) return@launch
                    synchronized(this@RTBuddyManager) { lastHeart = 0 }
                    for ((packet, pause) in HEART_INIT) {
                        if (!wanted() || !sendRaw(packet)) return@launch
                        delay(pause)
                    }
                    if (!wanted() || !sendRaw(byteArrayOf(4, 0, 4, 0, 9, 0, 0x30, 1, 0, 0, 0))) return@launch
                    delay(120)
                    if (!wanted() || !command(84, 1_000_000, heart = true)) return@launch
                    delay(120)
                    if (!wanted() || !command(19, 1_000_000, heart = true)) return@launch
                    val started = now()
                    while (wanted()) {
                        val last = synchronized(this@RTBuddyManager) { lastHeart }
                        if (now() - maxOf(started, last) >= 12_000) break
                        delay(250)
                    }
                    if (!wanted()) return@launch
                    synchronized(this@RTBuddyManager) { stopHeartWire() }
                }
            } finally {
                synchronized(this@RTBuddyManager) {
                    if (wanted()) {
                        stopHeartWire()
                        heartWanted = false
                        onHeartRateState(BuddyState.INACTIVE)
                    }
                }
            }
        }
    }

    private fun stopHeartWire() {
        command(84, 0, heart = true)
        command(19, 0, heart = true)
        if (!closed && isCurrent()) sendRaw(byteArrayOf(4, 0, 4, 0, 9, 0, 0x30, 2, 0, 0, 0))
        lastHeart = 0
    }

    @Synchronized
    private fun command(service: Int, value: Int, report: Int = 1, heart: Boolean = false): Boolean {
        if (closed || !isCurrent() || service !in 1..127 || value < 0) return false
        val setting = SensorServiceSetting.newBuilder().setServiceValue(service).setSetting(2)
            .setConfiguration(ByteString.copyFrom(byteArrayOf(report.toByte()) + ByteArray(4) { (value ushr (it * 8)).toByte() }))
        val data = SensorDataWX.newBuilder().setServiceSettings(setting)
        if (heart) data.setLogType(2)
        return sendSensorData(data.build())
    }

    @Synchronized
    fun sendSensorData(data: SensorDataWX): Boolean {
        if (closed || !isCurrent()) return false
        sequence = (sequence + 1) and 127
        return sendPacket(RTBuddyPacket.create(SensorDataWxBuddyPayload(data.toBuilder().setSeq(sequence).build()), PacketDestination.DEVICE))
    }

    fun setSensorServiceReportInterval(sensorServiceType: SensorServiceType, interval: Duration): Boolean =
        interval.inWholeMicroseconds.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.let {
            command(sensorServiceType.number, it.toInt())
        } ?: false

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        motionWanted = false
        heartWanted = false
        onMotionState(BuddyState.INACTIVE)
        onHeartRateState(BuddyState.INACTIVE)
    }

    companion object {
        private fun now() = System.nanoTime() / 1_000_000
        private val HEART_INIT = listOf(
            byteArrayOf(0, 0, 0, 0, 1, 0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0) to 180L,
            byteArrayOf(4, 0, 0, 0, 1, 0, 0) to 220L,
            byteArrayOf(0, 0, 4, 0, 1, 0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0) to 180L,
            byteArrayOf(4, 0, 4, 0, 1, 0, 0) to 220L,
        )
    }
}