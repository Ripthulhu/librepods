package me.kavishdevar.librepods.dongle

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.kavishdevar.librepods.dongle.DongleControlProtocol.AudioProfile as AudioProfileLayout
import me.kavishdevar.librepods.dongle.DongleControlProtocol.CachedReport
import me.kavishdevar.librepods.dongle.DongleControlProtocol.Control
import me.kavishdevar.librepods.dongle.DongleControlProtocol.Control.Capability
import me.kavishdevar.librepods.dongle.DongleControlProtocol.Control.StatusField
import me.kavishdevar.librepods.dongle.DongleControlProtocol.Control.StatusFlag
import me.kavishdevar.librepods.dongle.DongleControlProtocol.Feature
import me.kavishdevar.librepods.dongle.DongleControlProtocol.Frame
import me.kavishdevar.librepods.dongle.DongleControlProtocol.Nearby as NearbyLayout
import me.kavishdevar.librepods.dongle.DongleControlProtocol.Performance as PerformanceLayout
import me.kavishdevar.librepods.dongle.DongleControlProtocol.PhoneTransport
import me.kavishdevar.librepods.dongle.DongleControlProtocol.PhoneTransport.Operation as ReportOperation
import me.kavishdevar.librepods.dongle.DongleControlProtocol.PhysicalVolume as PhysicalVolumeLayout
import me.kavishdevar.librepods.dongle.DongleControlProtocol.Rpc
import me.kavishdevar.librepods.dongle.DongleControlProtocol.SettingsLeaseAction
import me.kavishdevar.librepods.dongle.DongleControlProtocol.SettingsSnapshot
import me.kavishdevar.librepods.dongle.DongleControlProtocol.VolumeAuthority
import me.kavishdevar.librepods.core.devices.ComponentStatus

internal class DongleBusyException : IOException("Dongle controls are busy")
internal class DongleRequestException(message: String) : IOException(message)

internal object DongleProtocol {
    const val UUID = PhoneTransport.UUID
    /** The ear-state AACP packet: header 04 00 04 00, opcode 06 00, then the two ear states. */
    private val EAR_STATE_PREFIX = listOf<Byte>(4, 0, 4, 0, 6, 0)
    private const val EAR_STATE_PACKET_SIZE = 8
    private const val FIRST_EAR_OFFSET = 6
    private const val SECOND_EAR_OFFSET = 7
    /** Status batteries count as live while younger than this. */
    private const val BATTERY_FRESHNESS_MILLISECONDS = 90_000L
    /** Client-side bound; the firmware catalog has 23 legacy and 25 extended records. */
    private const val MAXIMUM_SETTINGS_RECORDS = 32
    /** Client-side plausibility bound for nearby RSSI readings, in dBm. */
    private val ACCEPTED_RSSI_RANGE = -126..20
    private const val MAXIMUM_PERCENT = 100

    fun u32(bytes: ByteArray, at: Int): Long = (0..3).fold(0L) { value, i ->
        value or ((bytes[at + i].toLong() and 255) shl (8 * i))
    }
    fun put32(bytes: ByteArray, at: Int, value: Long) {
        require(value in 0..0xffff_ffffL)
        repeat(4) { bytes[at + it] = (value shr (8 * it)).toByte() }
    }
    fun u16(bytes: ByteArray, at: Int) = (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)
    fun header(report: Int): ByteArray = ByteArray(Frame.SIZE).also {
        it[Frame.REPORT_ID_OFFSET] = report.toByte()
        DongleControlProtocol.MAGIC.copyInto(it, Frame.MAGIC_OFFSET)
        it[Frame.VERSION_OFFSET] = DongleControlProtocol.PROTOCOL_VERSION.toByte()
        it[Frame.LENGTH_OFFSET] = Frame.SIZE.toByte()
    }
    fun checkReport(bytes: ByteArray, id: Int) {
        val expectedHeader = header(id).take(Frame.HEADER_SIZE)
        require(bytes.size == Frame.SIZE && bytes.take(Frame.HEADER_SIZE) == expectedHeader) { "Unsupported dongle report" }
    }

    /** RFCOMM is a stream; no read is assumed to preserve a packet boundary. */
    fun exchange(input: InputStream, output: OutputStream, operation: Int, id: Int, payload: ByteArray): ByteArray {
        val expectedSize = if (operation == ReportOperation.READ_REPORT) 0 else Frame.SIZE
        require(id in Control.REPORT_ID..Rpc.REPORT_ID &&
            operation in ReportOperation.READ_REPORT..ReportOperation.WRITE_REPORT &&
            payload.size == expectedSize)
        val frameStart = DongleControlProtocol.MAGIC + DongleControlProtocol.PROTOCOL_VERSION.toByte()
        output.write(frameStart + byteArrayOf(operation.toByte(), id.toByte(), payload.size.toByte()))
        output.write(payload)
        output.flush()
        fun read(count: Int): ByteArray {
            val bytes = ByteArray(count)
            var at = 0
            while (at < count) {
                val received = input.read(bytes, at, count - at)
                if (received <= 0) throw IOException("Dongle connection ended")
                at += received
            }
            return bytes
        }
        val frame = read(PhoneTransport.HEADER_SIZE)
        if (frame.take(PhoneTransport.OPERATION_OFFSET) != frameStart.toList() ||
            frame[PhoneTransport.REPORT_ID_OFFSET].toInt() != id)
            throw IOException("Unsupported dongle response")
        val responseOperation = frame[PhoneTransport.OPERATION_OFFSET].toInt() and 255
        val length = frame[PhoneTransport.LENGTH_OFFSET].toInt() and 255
        if (responseOperation == PhoneTransport.ERROR_OPERATION && length == 1) {
            val code = read(1)[0].toInt()
            if (code == PhoneTransport.Error.BUSY) throw DongleBusyException()
            throw DongleRequestException(when (code) {
                PhoneTransport.Error.UNAVAILABLE -> "Dongle controls are unavailable"
                else -> "Dongle rejected the request"
            })
        }
        if (responseOperation != (operation or PhoneTransport.RESPONSE_OPERATION_FLAG) || length != Frame.SIZE)
            throw IOException("Unsupported dongle response")
        return read(length).also { checkReport(it, id) }
    }

    data class Status(val generation: Long, val address: List<Int>, val flags: Int, val anc: Int,
        val batteries: List<Int?>, val codec: Int, val capabilities: Int,
        val batteryAgeMs: List<Long?> = List(Control.Component.SLOT_COUNT) { null }, val batteryPresent: Int = 0,
        val batteryCaseSource: Int = 0, val charging: Int = 0,
        val lastSequence: Long = 0, val lastResult: Int = 0, val lastOpcode: Int = 0) {
        fun sameConnection(other: Status) = generation == other.generation && address == other.address
        val connected get() = flags and StatusFlag.A2DP_CONNECTED != 0
        val controlsReady get() = flags and StatusFlag.AACP_READY != 0
        val microphoneActive get() = flags and StatusFlag.MICROPHONE_ACTIVE != 0
        fun batteryLive(index: Int, elapsedMs: Long = 0): Boolean {
            val age = batteryAgeMs.getOrNull(index) ?: return false
            return index in Control.Component.LEFT_SLOT..Control.Component.CASE_SLOT && batteries.getOrNull(index) != null &&
                batteryPresent and (1 shl index) != 0 && age < BATTERY_FRESHNESS_MILLISECONDS &&
                elapsedMs >= 0 && elapsedMs < BATTERY_FRESHNESS_MILLISECONDS - age
        }
        fun batteryCharging(index: Int, elapsedMs: Long = 0) =
            batteryLive(index, elapsedMs) && charging and (1 shl index) != 0
    }
    fun status(bytes: ByteArray): Status {
        checkReport(bytes, Control.REPORT_ID)
        val flags = bytes[StatusField.FLAGS_OFFSET].toInt()
        val listeningMode = bytes[StatusField.LISTENING_MODE_OFFSET].toInt()
        val charging = bytes[StatusField.CHARGING_COMPONENTS_OFFSET].toInt()
        val codec = bytes[StatusField.OUTPUT_CODEC_OFFSET].toInt()
        val lastResult = bytes[StatusField.LAST_COMMAND_RESULT_OFFSET].toInt()
        val lastOpcode = bytes[StatusField.LAST_COMMAND_OPCODE_OFFSET].toInt()
        require(flags and StatusFlag.UNDEFINED == 0 &&
            listeningMode in Control.ListeningMode.UNKNOWN..Control.ListeningMode.ADAPTIVE &&
            charging and Control.Component.UNDEFINED_BITS == 0 &&
            codec in Control.Codec.UNKNOWN..Control.Codec.AAC_ELD &&
            lastResult in Control.Result.NONE..Control.Result.MALFORMED &&
            lastOpcode in 0..Control.Opcode.OPEN_PHONE_PAIRING &&
            bytes.drop(StatusField.RESERVED_OFFSET).all { it == 0.toByte() })
        val batteryOffsets = StatusField.BATTERY_PERCENT_OFFSET until StatusField.BATTERY_PERCENT_OFFSET + Control.Component.SLOT_COUNT
        val levels = bytes.sliceArray(batteryOffsets).map { it.toInt() and 255 }
        require(levels.all { it <= MAXIMUM_PERCENT || it == Control.BATTERY_UNKNOWN })
        val ages = (0 until Control.Component.SLOT_COUNT).map { slot ->
            u32(bytes, StatusField.BATTERY_AGES_MILLISECONDS_OFFSET + slot * 4).takeUnless { age -> age == Control.AGE_UNKNOWN }
        }
        val capabilities = u16(bytes, StatusField.CAPABILITIES_OFFSET)
        val observations = capabilities and Capability.BATTERY_OBSERVATION_FLAGS != 0
        val presentByte = bytes[StatusField.PRESENT_COMPONENTS_OFFSET]
        val caseObservedByte = bytes[StatusField.CASE_OBSERVED_COMPONENTS_OFFSET]
        val present = if (observations) presentByte.toInt() and 255 else
            levels.indices.fold(0) { mask, index -> if (levels[index] <= MAXIMUM_PERCENT && ages[index] != null) mask or (1 shl index) else mask }
        val caseSource = caseObservedByte.toInt() and 255
        require(if (observations) present and Control.Component.UNDEFINED_BITS == 0 &&
            caseSource and Control.Component.UNDEFINED_BITS == 0 &&
            charging and present.inv() == 0 &&
            levels.indices.all { present and (1 shl it) == 0 || levels[it] <= MAXIMUM_PERCENT && ages[it] != null }
            else presentByte == 0.toByte() && caseObservedByte == 0.toByte())
        val addressEnd = StatusField.SELECTED_ADDRESS_OFFSET + StatusField.SELECTED_ADDRESS_SIZE
        val address = bytes.sliceArray(StatusField.SELECTED_ADDRESS_OFFSET until addressEnd).map { it.toInt() and 255 }
        return Status(u32(bytes, StatusField.CONNECTION_GENERATION_OFFSET), address,
            flags and 255, listeningMode, levels.map { it.takeIf { it <= MAXIMUM_PERCENT } },
            codec, capabilities, ages, present, caseSource, charging,
            u32(bytes, StatusField.LAST_COMMAND_SEQUENCE_OFFSET), lastResult, lastOpcode)
    }
    data class Setting(val value: Long, val ageMs: Long, val revision: Long)
    data class PhysicalVolume(val confirmed: Int?, val desired: Int?, val observationRevision: Long,
        val pending: Boolean, val inflight: Boolean, val authorityActive: Boolean, val ownToken: Long?,
        val remainingMs: Long, val usbGeneration: Long, val usbScopeReady: Boolean)
    fun physicalVolume(bytes: ByteArray): PhysicalVolume {
        val flags = bytes.getOrNull(PhysicalVolumeLayout.FLAGS_OFFSET)?.toInt() ?: 0
        require(bytes.size == PhysicalVolumeLayout.SIZE && bytes[0] == PhysicalVolumeLayout.VERSION.toByte() &&
            flags and PhysicalVolumeLayout.Flag.UNDEFINED_BITS == 0 &&
            u32(bytes, PhysicalVolumeLayout.RESERVED_OFFSET) == 0L)
        val confirmed = (bytes[PhysicalVolumeLayout.CONFIRMED_LEVEL_OFFSET].toInt() and 255)
            .takeUnless { it == PhysicalVolumeLayout.LEVEL_UNKNOWN }
        val desired = (bytes[PhysicalVolumeLayout.DESIRED_LEVEL_OFFSET].toInt() and 255)
            .takeUnless { it == PhysicalVolumeLayout.LEVEL_UNKNOWN }
        val active = flags and PhysicalVolumeLayout.Flag.ACTIVE_AUTHORITY != 0
        val token = u32(bytes, PhysicalVolumeLayout.AUTHORITY_TOKEN_OFFSET).takeUnless { it == 0L }
        val ttl = u32(bytes, PhysicalVolumeLayout.REMAINING_LEASE_MILLISECONDS_OFFSET)
        val usbGeneration = u32(bytes, PhysicalVolumeLayout.USB_MEDIA_GENERATION_OFFSET)
        val requestOutstanding = PhysicalVolumeLayout.Flag.PENDING_REQUEST or PhysicalVolumeLayout.Flag.IN_FLIGHT_REQUEST
        val scopeEligible = flags and PhysicalVolumeLayout.Flag.ELIGIBLE_USB_SCOPE != 0
        require(confirmed == null || confirmed in 0..PhysicalVolumeLayout.MAXIMUM_LEVEL)
        require(desired == null || desired in 0..PhysicalVolumeLayout.MAXIMUM_LEVEL)
        require((confirmed != null) == (flags and PhysicalVolumeLayout.Flag.KNOWN_PHYSICAL_GAIN != 0) &&
            (desired != null) == (flags and requestOutstanding != 0))
        require(if (active) ttl in 1..PhysicalVolumeLayout.LEASE_MILLISECONDS && scopeEligible && usbGeneration and 1L != 0L
            else token == null && ttl == 0L)
        return PhysicalVolume(confirmed, desired, u32(bytes, PhysicalVolumeLayout.OBSERVATION_REVISION_OFFSET),
            flags and PhysicalVolumeLayout.Flag.PENDING_REQUEST != 0, flags and PhysicalVolumeLayout.Flag.IN_FLIGHT_REQUEST != 0,
            active, token, ttl, usbGeneration, scopeEligible)
    }

    fun volumeAuthorityRequest(action: Int, token: Long = 0, level: Int = 0): ByteArray {
        require(action in VolumeAuthority.Action.ACQUIRE..VolumeAuthority.Action.SET && token in 0..0xffff_ffffL &&
            level in 0..PhysicalVolumeLayout.MAXIMUM_LEVEL &&
            (action == VolumeAuthority.Action.SET || level == 0) &&
            (if (action == VolumeAuthority.Action.ACQUIRE) token == 0L else token != 0L))
        return ByteArray(VolumeAuthority.REQUEST_SIZE).also {
            it[0] = Feature.USB_HOST_VOLUME_AUTHORITY.toByte()
            it[VolumeAuthority.ACTION_OFFSET] = action.toByte()
            put32(it, VolumeAuthority.TOKEN_OFFSET, token)
            it[VolumeAuthority.LEVEL_OFFSET] = level.toByte()
        }
    }
    fun settings(bytes: ByteArray): Map<Int, Setting> {
        require(bytes.size >= SettingsSnapshot.HEADER_SIZE && bytes[0] == SettingsSnapshot.VERSION.toByte()) {
            "Unsupported settings response"
        }
        val count = bytes[SettingsSnapshot.COUNT_OFFSET].toInt() and 255
        require(count <= MAXIMUM_SETTINGS_RECORDS && bytes.size == SettingsSnapshot.HEADER_SIZE + count * SettingsSnapshot.RECORD_SIZE)
        val result = linkedMapOf<Int, Setting>()
        repeat(count) { index ->
            val at = SettingsSnapshot.HEADER_SIZE + index * SettingsSnapshot.RECORD_SIZE
            val id = bytes[at + SettingsSnapshot.IDENTIFIER_OFFSET].toInt() and 255
            val revision = u32(bytes, at + SettingsSnapshot.REVISION_OFFSET)
            require(id !in result && revision != 0L)
            val value = u32(bytes, at + SettingsSnapshot.VALUE_OFFSET)
            val age = u32(bytes, at + SettingsSnapshot.AGE_MILLISECONDS_OFFSET)
            result[id] = Setting(value, age, revision)
        }
        return result
    }
    data class Cached(val ageMs: Long, val revision: Long, val packet: ByteArray)
    fun cached(bytes: ByteArray): Cached? {
        if (bytes.isEmpty()) return null
        require(bytes.size > CachedReport.ENVELOPE_SIZE && u32(bytes, CachedReport.REVISION_OFFSET) != 0L)
        return Cached(u32(bytes, CachedReport.AGE_MILLISECONDS_OFFSET), u32(bytes, CachedReport.REVISION_OFFSET),
            bytes.copyOfRange(CachedReport.ENVELOPE_SIZE, bytes.size))
    }
    fun ears(bytes: ByteArray): Boolean? {
        val inEar = ComponentStatus.IN_EAR.toAirPodsByte().toInt()
        val inCase = ComponentStatus.IN_CASE.toAirPodsByte().toInt()
        val knownStates = inEar..ComponentStatus.DISCONNECTED.toAirPodsByte().toInt()
        if (bytes.size != EAR_STATE_PACKET_SIZE || bytes.take(EAR_STATE_PREFIX.size) != EAR_STATE_PREFIX ||
            bytes[FIRST_EAR_OFFSET].toInt() !in knownStates || bytes[SECOND_EAR_OFFSET].toInt() !in knownStates) return null
        val first = bytes[FIRST_EAR_OFFSET].toInt()
        val second = bytes[SECOND_EAR_OFFSET].toInt()
        return (first == inEar || second == inEar) && (first == inEar || first == inCase) && (second == inEar || second == inCase)
    }
    data class AudioProfile(val requestedCodec: Int, val actualCodec: Int, val rateMode: Int,
        val requestedBitrate: Long, val actualBitrate: Long, val gameRequested: Boolean,
        val gameState: Int, val input: Int, val flags: Int, val delayTenthsMs: Int?)
    fun audio(bytes: ByteArray): AudioProfile {
        val codecs = Control.Codec.UNKNOWN..Control.Codec.AAC_ELD
        require(bytes.size == AudioProfileLayout.SIZE && bytes[0] == AudioProfileLayout.VERSION.toByte() &&
            bytes[AudioProfileLayout.REQUESTED_CODEC_OFFSET].toInt() in codecs &&
            bytes[AudioProfileLayout.ACTUAL_CODEC_OFFSET].toInt() in codecs &&
            bytes[AudioProfileLayout.RATE_MODE_OFFSET].toInt() in 0..AudioProfileLayout.RATE_MODE_VARIABLE &&
            bytes[AudioProfileLayout.GAME_MODE_REQUESTED_OFFSET].toInt() in 0..1 &&
            bytes[AudioProfileLayout.GAME_MODE_STATE_OFFSET].toInt() in 0..AudioProfileLayout.GAME_MODE_STATE_RESTORING &&
            bytes[AudioProfileLayout.INPUT_OWNERSHIP_OFFSET].toInt() in
                AudioProfileLayout.InputOwnership.IDLE..AudioProfileLayout.InputOwnership.PHONE &&
            bytes[AudioProfileLayout.FLAGS_OFFSET].toInt() and AudioProfileLayout.Flag.UNDEFINED_BITS == 0 &&
            u16(bytes, AudioProfileLayout.STARTUP_PRIMING_MILLISECONDS_OFFSET) == AudioProfileLayout.STARTUP_PRIMING_MILLISECONDS) {
            "Unsupported audio profile response"
        }
        return AudioProfile(bytes[AudioProfileLayout.REQUESTED_CODEC_OFFSET].toInt(),
            bytes[AudioProfileLayout.ACTUAL_CODEC_OFFSET].toInt(),
            bytes[AudioProfileLayout.RATE_MODE_OFFSET].toInt(),
            u32(bytes, AudioProfileLayout.REQUESTED_BITRATE_OFFSET),
            u32(bytes, AudioProfileLayout.ACTUAL_BITRATE_OFFSET),
            bytes[AudioProfileLayout.GAME_MODE_REQUESTED_OFFSET] == 1.toByte(),
            bytes[AudioProfileLayout.GAME_MODE_STATE_OFFSET].toInt(),
            bytes[AudioProfileLayout.INPUT_OWNERSHIP_OFFSET].toInt(),
            bytes[AudioProfileLayout.FLAGS_OFFSET].toInt(),
            u16(bytes, AudioProfileLayout.DELAY_TENTHS_MILLISECONDS_OFFSET).takeIf { it != AudioProfileLayout.DELAY_UNKNOWN })
    }
    data class Performance(val input: Int, val codec: Int, val flags: Int, val fields: List<Long>)
    fun performance(bytes: ByteArray): Performance {
        require(bytes.size == PerformanceLayout.SIZE && bytes[0] == PerformanceLayout.VERSION.toByte() &&
            bytes[PerformanceLayout.INPUT_OWNERSHIP_OFFSET].toInt() in
                AudioProfileLayout.InputOwnership.IDLE..AudioProfileLayout.InputOwnership.PHONE &&
            bytes[PerformanceLayout.ACTUAL_CODEC_OFFSET].toInt() in Control.Codec.UNKNOWN..Control.Codec.AAC_ELD &&
            bytes[PerformanceLayout.FLAGS_OFFSET].toInt() and PerformanceLayout.FLAG_UNDEFINED_BITS == 0)
        val fields = (PerformanceLayout.FIRST_FIELD_OFFSET..PerformanceLayout.LAST_FIELD_OFFSET step 4).map { u32(bytes, it) }
        fun within(value: Int, capacity: Int) = fields[value] == PerformanceLayout.UNAVAILABLE ||
            fields[capacity] == PerformanceLayout.UNAVAILABLE || fields[value] <= fields[capacity]
        val field = PerformanceLayout.FieldIndex
        require(within(field.PCM_FRAMES_USED, field.PCM_FRAMES_CAPACITY) &&
            within(field.PCM_FRAMES_HIGH_WATER_MARK, field.PCM_FRAMES_CAPACITY) &&
            within(field.HEAP_ALLOCATED_BYTES, field.LINKED_HEAP_CAPACITY) &&
            within(field.HEAP_REMAINING_BYTES, field.LINKED_HEAP_CAPACITY))
        return Performance(bytes[PerformanceLayout.INPUT_OWNERSHIP_OFFSET].toInt(),
            bytes[PerformanceLayout.ACTUAL_CODEC_OFFSET].toInt(), bytes[PerformanceLayout.FLAGS_OFFSET].toInt(), fields)
    }
    data class Nearby(val state: Int, val identityAvailable: Boolean, val rssi: Int?, val trend: Int, val ageMs: Long?,
        val reports: Long, val pauseFlags: Long, val session: Long, val smoothedRssi: Double?)
    fun nearby(bytes: ByteArray): Nearby {
        val stateByte = bytes.getOrNull(NearbyLayout.STATE_OFFSET)?.toInt() ?: 0
        require(bytes.size == NearbyLayout.SIZE && bytes[0] == NearbyLayout.VERSION.toByte() &&
            (stateByte and NearbyLayout.STATE_MASK) in NearbyLayout.STATE_STOPPED..NearbyLayout.STATE_PAUSED &&
            bytes[NearbyLayout.TREND_OFFSET].toInt() in 0..NearbyLayout.TREND_WEAKER)
        val rssi = bytes[NearbyLayout.RSSI_OFFSET].toInt().takeIf { it != NearbyLayout.RSSI_UNKNOWN }
        val smooth = u32(bytes, NearbyLayout.SMOOTHED_RSSI_OFFSET).toInt()
            .takeIf { it != NearbyLayout.SMOOTHED_RSSI_UNKNOWN }
            ?.div(NearbyLayout.SMOOTHED_RSSI_SCALE)
        val identity = stateByte and NearbyLayout.IDENTITY_AVAILABLE_FLAG != 0
        val state = stateByte and NearbyLayout.STATE_MASK
        val acceptedSmoothedRssi = ACCEPTED_RSSI_RANGE.first.toDouble()..ACCEPTED_RSSI_RANGE.last.toDouble()
        require((state == NearbyLayout.STATE_STOPPED || identity) && (identity || rssi == null && smooth == null) &&
            (rssi == null || rssi in ACCEPTED_RSSI_RANGE) && (smooth == null || smooth in acceptedSmoothedRssi))
        val age = u32(bytes, NearbyLayout.LAST_HEARD_AGE_OFFSET).takeIf { it != NearbyLayout.AGE_UNKNOWN }
        return Nearby(state, identity, rssi, bytes[NearbyLayout.TREND_OFFSET].toInt(), age,
            u32(bytes, NearbyLayout.MATCHED_REPORTS_OFFSET), u32(bytes, NearbyLayout.PAUSE_FLAGS_OFFSET),
            u32(bytes, NearbyLayout.SESSION_OFFSET), smooth)
    }
}

/** One caller serializes this session. A failed write is never automatically retried. */
internal class DongleSession(private val io: suspend (Int, Int, ByteArray) -> ByteArray,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wait: suspend () -> Unit = { delay(POLL_INTERVAL_MILLISECONDS) },
    val controlEpoch: Long = 0L,
    private val closeTransport: () -> Unit = {}, private val resumeSequences: Boolean = false,
    private val boundedIo: (suspend (Int, Int, ByteArray, Long) -> ByteArray)? = null) {
    private var sequence = 0L
    private var actionTarget: DongleProtocol.Status? = null
    private var leaseAttempted = false
    private var leaseFailed = false
    private var leaseToken: Long? = null
    private var transportBroken = false
    private var rpcCheckpoint: (suspend () -> Long?)? = null
    private var checkingVolume = false
    private var volumeDeadline: Long? = null
    private var rpcIoDeadline: Long? = null
    private var checkpointIoDeadline: Long? = null
    private var cleanupIoDeadline: Long? = null
    var status: DongleProtocol.Status? = null
        private set

    /** The caller still owns the whole workflow. Checkpoints run only between complete RPCs. */
    suspend fun <T> withRpcCheckpoints(checkpoint: suspend () -> Long?, block: suspend () -> T): T {
        check(rpcCheckpoint == null)
        rpcCheckpoint = checkpoint
        return try { block() } finally { rpcCheckpoint = null; volumeDeadline = null }
    }

    private suspend fun checkpointVolume() {
        val checkpoint = rpcCheckpoint ?: return
        if (checkingVolume) return
        checkingVolume = true
        checkpointIoDeadline = now() + VOLUME_CHECKPOINT_BUDGET_MILLISECONDS
        try {
            // The coordinator returns remaining time, independently of this session's clock.
            volumeDeadline = checkpoint()?.let { remaining ->
                check(now() < checkNotNull(checkpointIoDeadline)) { "Volume checkpoint exceeded its budget" }
                check(remaining > MINIMUM_VOLUME_LEASE_FOR_SETTINGS_MILLISECONDS) { "Not enough volume lease remains for settings" }
                now() + remaining - VOLUME_LEASE_RESERVE_MILLISECONDS // Reserve confirmed Cancel and a subsequent renewal.
            }
        } finally { checkingVolume = false; checkpointIoDeadline = null }
    }

    private suspend fun exchangeIo(operation: Int, report: Int, data: ByteArray): ByteArray {
        val end = cleanupIoDeadline ?: minOf(rpcIoDeadline ?: Long.MAX_VALUE,
            if (checkingVolume) checkpointIoDeadline ?: Long.MAX_VALUE else volumeDeadline ?: Long.MAX_VALUE)
        if (end == Long.MAX_VALUE) return io(operation, report, data)
        val remaining = end - now()
        if (remaining <= 0) throw IOException("Dongle I/O exceeded its volume lease budget")
        val result = if (boundedIo != null) boundedIo.invoke(operation, report, data, remaining)
            else withTimeout(remaining) { io(operation, report, data) }
        if (now() >= end) throw IOException("Dongle I/O exceeded its volume lease budget")
        return result
    }
    private suspend fun readStatusReport(): ByteArray = exchangeIo(ReportOperation.READ_REPORT, Control.REPORT_ID, byteArrayOf())
    private suspend fun readRpcReport(): ByteArray = exchangeIo(ReportOperation.READ_REPORT, Rpc.REPORT_ID, byteArrayOf())
    private suspend fun writeRpcReport(report: ByteArray) = exchangeIo(ReportOperation.WRITE_REPORT, Rpc.REPORT_ID, report)

    suspend fun refresh(): DongleProtocol.Status {
        check(!transportBroken) { "Reconnect the dongle before another request" }
        return DongleProtocol.status(readStatusReport()).also { current ->
            check(actionTarget?.sameConnection(current) != false) { "AirPods connection changed; read the settings again" }
            status = current
        }
    }

    suspend fun command(opcode: Int): DongleProtocol.Status {
        require(opcode in listOf(Control.Opcode.RECONNECT_AIRPODS, Control.Opcode.PAIR_AIRPODS, Control.Opcode.OPEN_PHONE_PAIRING))
        val target = refresh()
        val requiredCapability = if (opcode == Control.Opcode.OPEN_PHONE_PAIRING) Capability.PHONE_PAIRING
            else Capability.PAIR_AND_RECONNECT
        check(target.capabilities and requiredCapability != 0) { "This dongle does not support pairing controls" }
        val pairingBlockers = StatusFlag.STREAMING or StatusFlag.PHONE_CONNECTED or StatusFlag.MICROPHONE_ACTIVE or
            StatusFlag.USB_PLAYBACK
        if (opcode != Control.Opcode.RECONNECT_AIRPODS && target.flags and pairingBlockers != 0)
            throw DongleRequestException("Pause playback, stop microphone use and disconnect the phone relay before pairing.")
        val token = ((target.lastSequence + 1) and 0xffff_ffffL).takeIf { it != 0L } ?: 1L
        val request = DongleProtocol.header(Control.REPORT_ID).also {
            it[Control.CommandField.OPCODE_OFFSET] = opcode.toByte()
            DongleProtocol.put32(it, Control.CommandField.CONNECTION_GENERATION_OFFSET, target.generation)
            target.address.forEachIndexed { index, value -> it[Control.CommandField.SELECTED_ADDRESS_OFFSET + index] = value.toByte() }
            DongleProtocol.put32(it, Control.CommandField.COMMAND_SEQUENCE_OFFSET, token)
        }
        exchangeIo(ReportOperation.WRITE_REPORT, Control.REPORT_ID, request)
        val deadline = now() + COMMAND_RECEIPT_TIMEOUT_MILLISECONDS
        while (now() < deadline) {
            val current = refresh()
            if (current.lastSequence == token && current.lastOpcode == opcode) {
                if (current.lastResult == Control.Result.BUSY) {
                    if (opcode == Control.Opcode.PAIR_AIRPODS) throw DongleRequestException("Pairing may have changed the saved AirPods. Check the dongle status before trying again.")
                    throw DongleBusyException()
                }
                check(current.lastResult == Control.Result.ACCEPTED) { "The dongle did not accept the request" }
                return current
            }
            wait()
        }
        throw IOException("Dongle request timed out; it was not retried")
    }

    suspend fun <T> withTarget(target: DongleProtocol.Status, block: suspend () -> T): T {
        check(actionTarget == null)
        actionTarget = target
        return try { refresh(); block() } finally {
            try {
                leaseToken?.takeUnless { transportBroken }?.let { token -> cleanup {
                    val current = DongleProtocol.status(readStatusReport())
                    if (current.sameConnection(target))
                        check(transactRpc(Rpc.Operation.TYPED_FEATURE_REQUEST, leaseRequest(SettingsLeaseAction.RELEASE, token), current).isEmpty())
                } }
            } finally {
                actionTarget = null
                leaseToken = null
                leaseAttempted = false
                leaseFailed = false
            }
        }
    }

    private fun leaseRequest(action: Int, token: Long) = ByteArray(LEASE_REQUEST_SIZE).also {
        it[0] = Feature.SETTINGS_WORKFLOW_LEASE.toByte()
        it[LEASE_ACTION_OFFSET] = action.toByte()
        DongleProtocol.put32(it, LEASE_TOKEN_OFFSET, token)
    }

    /** Cached mode baselines also need exclusion before constructing a multi-packet edit. */
    suspend fun acquireSettingsLease() {
        check(actionTarget != null) { "Settings require a target-bound action" }
        val target = refresh()
        check(target.capabilities and Capability.SETTINGS_WORKFLOW_LEASE != 0) { "Update the dongle firmware before editing hearing settings" }
        check(!leaseFailed) { "The settings lease was lost; read the settings again" }
        checkpointVolume()
        val acquiring = !leaseAttempted
        leaseAttempted = true
        try {
            val action = if (acquiring) SettingsLeaseAction.ACQUIRE else SettingsLeaseAction.RENEW
            val response = transactRpc(Rpc.Operation.TYPED_FEATURE_REQUEST, leaseRequest(action, leaseToken ?: 0), target)
            if (acquiring) {
                require(response.size == LEASE_RESPONSE_SIZE &&
                    response[0] == DongleControlProtocol.SETTINGS_LEASE_RESPONSE_VERSION.toByte() &&
                    DongleProtocol.u32(response, LEASE_RESPONSE_TOKEN_OFFSET) != 0L)
                leaseToken = DongleProtocol.u32(response, LEASE_RESPONSE_TOKEN_OFFSET)
            } else require(response.isEmpty())
        } catch (error: Exception) {
            leaseFailed = true
            // An acquire may have succeeded even when its receipt was lost.
            if (acquiring && error !is DongleBusyException) {
                transportBroken = true
                runCatching { closeTransport() }
            }
            throw error
        }
        checkpointVolume()
    }

    private suspend fun cleanup(block: suspend () -> Unit) = withContext(NonCancellable) {
        val previous = cleanupIoDeadline
        cleanupIoDeadline = now() + CLEANUP_TIMEOUT_MILLISECONDS
        coroutineScope {
            // Coroutine cancellation alone cannot interrupt BluetoothSocket's blocking I/O.
            val watchdog = launch(start = CoroutineStart.UNDISPATCHED) {
                delay(CLEANUP_TIMEOUT_MILLISECONDS)
                runCatching { closeTransport() }
            }
            try { withTimeout(CLEANUP_TIMEOUT_MILLISECONDS) { block() } }
            catch (_: Exception) { transportBroken = true; runCatching { closeTransport() } }
            finally { watchdog.cancel(); cleanupIoDeadline = previous }
        }
    }

    suspend fun transact(operation: Int, data: ByteArray, beforeCommit: () -> Unit = {}): ByteArray {
        val uploadOperations = listOf(Rpc.Operation.SEND_ATT_REQUEST, Rpc.Operation.SEND_AACP_PACKET,
            Rpc.Operation.READ_CACHED_REPORT, Rpc.Operation.TYPED_FEATURE_REQUEST)
        require(operation in uploadOperations && data.size in 1..Rpc.MAXIMUM_PAYLOAD_LENGTH)
        check(!leaseFailed) { "The settings lease was lost; read the settings again" }
        checkpointVolume()
        if (operation == Rpc.Operation.SEND_ATT_REQUEST || operation == Rpc.Operation.SEND_AACP_PACKET || leaseToken != null) {
            acquireSettingsLease()
        }
        val target = refresh()
        check(operation == Rpc.Operation.TYPED_FEATURE_REQUEST || target.controlsReady) { "Connect the AirPods to the dongle first" }
        val response = transactRpc(operation, data, target, beforeCommit)
        checkpointVolume()
        return response
    }

    private suspend fun transactRpc(operation: Int, data: ByteArray, target: DongleProtocol.Status,
        beforeCommit: () -> Unit = {}): ByteArray {
        val previous = rpcIoDeadline
        val deadline = if (checkingVolume) checkNotNull(checkpointIoDeadline)
            else minOf(now() + RPC_TIMEOUT_MILLISECONDS, volumeDeadline ?: Long.MAX_VALUE)
        rpcIoDeadline = deadline
        return try { transactBeforeDeadline(operation, data, target, beforeCommit, deadline) }
        finally { rpcIoDeadline = previous }
    }

    private suspend fun transactBeforeDeadline(operation: Int, data: ByteArray, target: DongleProtocol.Status,
        beforeCommit: () -> Unit, deadline: Long): ByteArray {
        if (resumeSequences && sequence == 0L) {
            val previous = readRpcReport()
            DongleProtocol.checkReport(previous, Rpc.REPORT_ID)
            val previousPhase = previous[Rpc.Field.RESPONSE_PHASE_OFFSET].toInt()
            if (previousPhase in Rpc.Phase.ASSEMBLING..Rpc.Phase.RUNNING) throw DongleBusyException()
            sequence = DongleProtocol.u32(previous, Rpc.Field.TRANSACTION_SEQUENCE_OFFSET)
        }
        sequence = (sequence + 1).and(0xffff_ffffL).takeIf { it != 0L } ?: 1L
        val token = sequence
        fun request(op: Int, total: Int = 0, offset: Int = 0, chunk: ByteArray = byteArrayOf()) =
            DongleProtocol.header(Rpc.REPORT_ID).also {
                it[Rpc.Field.OPERATION_OFFSET] = op.toByte()
                DongleProtocol.put32(it, Rpc.Field.CONNECTION_GENERATION_OFFSET, target.generation)
                DongleProtocol.put32(it, Rpc.Field.TRANSACTION_SEQUENCE_OFFSET, token)
                it[Rpc.Field.TOTAL_LENGTH_OFFSET] = total.toByte()
                it[Rpc.Field.TOTAL_LENGTH_OFFSET + 1] = (total shr 8).toByte()
                it[Rpc.Field.FRAGMENT_OFFSET_OFFSET] = offset.toByte()
                it[Rpc.Field.FRAGMENT_OFFSET_OFFSET + 1] = (offset shr 8).toByte()
                it[Rpc.Field.FRAGMENT_LENGTH_OFFSET] = chunk.size.toByte()
                chunk.copyInto(it, Rpc.Field.PAYLOAD_OFFSET)
            }
        fun phase(reply: ByteArray) = reply[Rpc.Field.RESPONSE_PHASE_OFFSET]
        fun fragmentOffset(reply: ByteArray) = DongleProtocol.u16(reply, Rpc.Field.FRAGMENT_OFFSET_OFFSET)
        suspend fun await(accept: (ByteArray) -> Boolean): ByteArray {
            while (now() < deadline) {
                val reply = readRpcReport()
                DongleProtocol.checkReport(reply, Rpc.REPORT_ID)
                if (DongleProtocol.u32(reply, Rpc.Field.CONNECTION_GENERATION_OFFSET) != target.generation) {
                    throw IOException("AirPods connection changed")
                }
                if (DongleProtocol.u32(reply, Rpc.Field.TRANSACTION_SEQUENCE_OFFSET) == token) {
                    if (phase(reply) == Rpc.Phase.FAILED.toByte()) {
                        val error = reply[Rpc.Field.ERROR_OFFSET].toInt()
                        if (error == Rpc.Error.BUSY) throw DongleBusyException()
                        throw IOException(when (error) {
                            Rpc.Error.STALE -> "AirPods connection changed"
                            Rpc.Error.UNSUPPORTED -> "This control is not supported by these AirPods"
                            else -> "Dongle request did not complete"
                        })
                    }
                    if (accept(reply)) return reply
                }
                wait()
            }
            throw IOException("Dongle request timed out; it was not retried")
        }
        var completed = false
        try {
            var sent = 0
            while (sent < data.size) {
                val next = minOf(sent + Rpc.MAXIMUM_FRAGMENT_LENGTH, data.size)
                writeRpcReport(request(operation, data.size, sent, data.copyOfRange(sent, next)))
                sent = next
                await { phase(it) == Rpc.Phase.ASSEMBLING.toByte() && fragmentOffset(it) == sent }
            }
            beforeCommit()
            writeRpcReport(request(Rpc.Operation.COMMIT_TRANSACTION))
            var reply = await { phase(it) == Rpc.Phase.COMPLETE.toByte() }
            val size = DongleProtocol.u16(reply, Rpc.Field.TOTAL_LENGTH_OFFSET)
            require(size <= Rpc.MAXIMUM_PAYLOAD_LENGTH)
            val result = ByteArray(size)
            var read = 0
            while (read < size) {
                val count = reply[Rpc.Field.FRAGMENT_LENGTH_OFFSET].toInt() and 255
                require(DongleProtocol.u16(reply, Rpc.Field.TOTAL_LENGTH_OFFSET) == size && fragmentOffset(reply) == read &&
                    count in 1..Rpc.MAXIMUM_FRAGMENT_LENGTH && count <= size - read)
                reply.copyInto(result, read, Rpc.Field.PAYLOAD_OFFSET, Rpc.Field.PAYLOAD_OFFSET + count)
                read += count
                if (read < size) {
                    writeRpcReport(request(Rpc.Operation.SELECT_RESPONSE_PAGE, offset = read))
                    reply = await { phase(it) == Rpc.Phase.COMPLETE.toByte() && fragmentOffset(it) == read }
                }
            }
            val current = refresh()
            // A completed codec request may deliberately restart this pair's AACP transport.
            // Its receipt remains bound to the original generation; other results may not cross it.
            val codecRestart = operation == Rpc.Operation.TYPED_FEATURE_REQUEST &&
                data[0] == Feature.CONFIGURE_AUDIO_PROFILE.toByte() &&
                current.address == target.address && runCatching { DongleProtocol.audio(result) }.isSuccess
            check(current.generation == target.generation || codecRestart) { "AirPods connection changed" }
            completed = true
            return result
        } finally {
            if (!completed) cleanup {
                // A write acknowledgement only queues Cancel. Confirm it before another PDU.
                writeRpcReport(request(Rpc.Operation.CANCEL_TRANSACTION))
                val cancelDeadline = now() + CLEANUP_TIMEOUT_MILLISECONDS
                var cancelled = false
                while (now() < cancelDeadline) {
                    val reply = readRpcReport()
                    DongleProtocol.checkReport(reply, Rpc.REPORT_ID)
                    val sameTransaction = DongleProtocol.u32(reply, Rpc.Field.TRANSACTION_SEQUENCE_OFFSET) == token
                    val cancelConfirmed = sameTransaction && phase(reply) == Rpc.Phase.FAILED.toByte() &&
                        reply[Rpc.Field.ERROR_OFFSET] == Rpc.Error.CANCELLED.toByte()
                    if (DongleProtocol.u32(reply, Rpc.Field.CONNECTION_GENERATION_OFFSET) != target.generation || cancelConfirmed) {
                        cancelled = true; break
                    }
                    wait()
                }
                check(cancelled) { "Dongle cancellation was not confirmed" }
            }
        }
    }
    suspend fun cached(slot: Int) = DongleProtocol.cached(transact(Rpc.Operation.READ_CACHED_REPORT, byteArrayOf(slot.toByte())))
    suspend fun settings() = DongleProtocol.settings(transact(Rpc.Operation.TYPED_FEATURE_REQUEST,
        byteArrayOf(Feature.READ_SETTINGS_SNAPSHOT.toByte())))
    suspend fun readPhysicalVolume(): DongleProtocol.PhysicalVolume {
        check(refresh().capabilities and Capability.USB_HOST_VOLUME_AUTHORITY != 0) {
            "The dongle does not support exclusive USB volume control"
        }
        return DongleProtocol.physicalVolume(transact(Rpc.Operation.TYPED_FEATURE_REQUEST,
            byteArrayOf(Feature.READ_PHYSICAL_VOLUME.toByte())))
    }
    suspend fun volumeAuthority(action: Int, expectedUsbGeneration: Long, token: Long = 0, level: Int = 0,
        beforePhysicalCommit: (DongleProtocol.PhysicalVolume) -> Unit = {},
        beforeCommit: () -> Unit = {}): DongleProtocol.PhysicalVolume {
        check(resumeSequences) { "Volume authority requires the USB control transport" }
        check(actionTarget != null) { "Volume authority requires a target-bound action" }
        val request = DongleProtocol.volumeAuthorityRequest(action, token, level)
        val before = readPhysicalVolume()
        check(before.usbGeneration == expectedUsbGeneration && before.usbScopeReady) { "USB audio connection changed" }
        if (action != VolumeAuthority.Action.ACQUIRE) check(before.ownToken == token && before.authorityActive) { "USB volume authority was lost" }
        return DongleProtocol.physicalVolume(transact(Rpc.Operation.TYPED_FEATURE_REQUEST, request) {
            beforePhysicalCommit(before)
            beforeCommit()
        }).also {
            check(it.usbGeneration == expectedUsbGeneration) { "USB audio connection changed" }
            check(when (action) {
                VolumeAuthority.Action.ACQUIRE -> it.authorityActive && it.ownToken != null
                VolumeAuthority.Action.RELEASE -> !it.authorityActive && it.ownToken == null
                else -> it.authorityActive && it.ownToken == token
            })
            // A successful SET is only admission; the caller still needs a newer physical observation.
        }
    }
    suspend fun setting(id: Int, value: Long, beforeCommit: () -> Unit = {}) =
        transact(Rpc.Operation.TYPED_FEATURE_REQUEST, ByteArray(WRITE_SETTING_REQUEST_SIZE).also {
            it[0] = Feature.WRITE_SETTING.toByte()
            it[SETTING_IDENTIFIER_OFFSET] = id.toByte()
            DongleProtocol.put32(it, SETTING_VALUE_OFFSET, value)
        }, beforeCommit)
    suspend fun requestAudio(codec: Int, rateMode: Int, bitrate: Long,
        expected: DongleProtocol.Status? = null): DongleProtocol.AudioProfile {
        val target = expected ?: refresh()
        val request = byteArrayOf(Feature.CONFIGURE_AUDIO_PROFILE.toByte(), codec.toByte(), rateMode.toByte()) + ByteArray(4)
        DongleProtocol.put32(request, AUDIO_PROFILE_BITRATE_OFFSET, bitrate)
        var attempted = false
        // A codec restart can erase its own receipt. Read the current preference, never replay it.
        try { withTarget(target) { transact(Rpc.Operation.TYPED_FEATURE_REQUEST, request) { attempted = true } } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: IOException) { if (!attempted) throw error }
        catch (error: IllegalStateException) { if (!attempted) throw error }
        val current = refresh()
        check(current.address == target.address) { "Selected AirPods changed" }
        val readAudioProfile = byteArrayOf(Feature.READ_AUDIO_PROFILE.toByte())
        return withTarget(current) { DongleProtocol.audio(transact(Rpc.Operation.TYPED_FEATURE_REQUEST, readAudioProfile)) }.also {
            check(it.requestedCodec == codec && it.rateMode == rateMode && it.requestedBitrate == bitrate) {
                "Codec request was not confirmed"
            }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MILLISECONDS = 40L
        const val COMMAND_RECEIPT_TIMEOUT_MILLISECONDS = 2_000L
        const val RPC_TIMEOUT_MILLISECONDS = 6_000L
        const val CLEANUP_TIMEOUT_MILLISECONDS = 1_000L
        const val VOLUME_CHECKPOINT_BUDGET_MILLISECONDS = 1_000L
        const val MINIMUM_VOLUME_LEASE_FOR_SETTINGS_MILLISECONDS = 1_600L
        const val VOLUME_LEASE_RESERVE_MILLISECONDS = 1_500L
        /** Feature byte, action u8, token u32. */
        const val LEASE_REQUEST_SIZE = 6
        const val LEASE_ACTION_OFFSET = 1
        const val LEASE_TOKEN_OFFSET = 2
        /** Version u8, token u32. */
        const val LEASE_RESPONSE_SIZE = 5
        const val LEASE_RESPONSE_TOKEN_OFFSET = 1
        /** Feature byte, setting id u8, value u32. */
        const val WRITE_SETTING_REQUEST_SIZE = 6
        const val SETTING_IDENTIFIER_OFFSET = 1
        const val SETTING_VALUE_OFFSET = 2
        /** Feature byte, codec u8, rate mode u8, bitrate u32. */
        const val AUDIO_PROFILE_BITRATE_OFFSET = 3
    }
}
