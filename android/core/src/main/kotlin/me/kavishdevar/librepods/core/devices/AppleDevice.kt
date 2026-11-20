package me.kavishdevar.librepods.core.devices

import android.annotation.SuppressLint
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.ControlCommandPacket
import me.kavishdevar.librepods.core.bluetooth.aacp.types.AppleEvent
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommand
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.bluetooth.aacp.types.AudioSourceType
import me.kavishdevar.librepods.core.bluetooth.att.ATTHandle
import me.kavishdevar.librepods.core.bluetooth.att.ATTManager
import me.kavishdevar.librepods.core.bluetooth.att.types.accommodationsTransparencyPreset
import me.kavishdevar.librepods.core.bluetooth.att.types.hearingAidSettingsWritable
import me.kavishdevar.librepods.core.bluetooth.att.types.parseHearingAidSettingsResponse
import me.kavishdevar.librepods.core.bluetooth.att.types.parseTransparencySettingsResponse
import me.kavishdevar.librepods.core.bluetooth.att.types.customTransparencyEnabled
import me.kavishdevar.librepods.core.bluetooth.aacp.publishForTransport
import me.kavishdevar.librepods.core.data.StemAction
import me.kavishdevar.librepods.core.data.StemPressBud
import me.kavishdevar.librepods.core.data.StemPressType
import me.kavishdevar.librepods.core.data.audio.MicrophoneFrame
import me.kavishdevar.librepods.core.data.audio.MicrophoneState
import me.kavishdevar.librepods.core.data.recording.Recording
import me.kavishdevar.librepods.core.data.recording.RecordingState
import me.kavishdevar.librepods.core.utils.GestureDetector
import me.kavishdevar.librepods.core.utils.HeadTracking
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.coroutines.resume

private const val TAG = "AppleDevice"

@SuppressLint("MissingPermission")
class AppleDevice(
    override val context: Context,
    override val bluetoothAdapter: BluetoothAdapter,
    override val bluetoothDevice: BluetoothDevice,
    currentState: ConnectionState
) : Device<AppleState, AppleSettings, AppleMetadata> {
    override val macAddress = MacAddress(bluetoothDevice.address)
    private val _state = MutableStateFlow(AppleState())
    override val state = _state.asStateFlow()

    private val _settings = MutableStateFlow(AppleSettings())
    override val settings = _settings.asStateFlow()

    private val _metadata = MutableStateFlow(AppleMetadata())
    override val metadata = _metadata.asStateFlow()

    private val _connectionState = MutableStateFlow(currentState)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<AppleEvent>()
    val events = _events.asSharedFlow()

    private val _microphoneFrames = MutableSharedFlow<MicrophoneFrame>(
        extraBufferCapacity = 64
    )
    val microphoneFrames = _microphoneFrames.asSharedFlow()

    private val _connectionNumber = MutableStateFlow(0)
    override val connectionNumber = _connectionNumber.asStateFlow()

    fun loadInitialState(state: AppleState, settings: AppleSettings, metadata: AppleMetadata) {
        _state.value = state.copy(aacpPackets = retainedPacketHistory(state.aacpPackets),
            controlSession = 0, accommodationsRequest = AccommodationsRequestState(), accommodationsAudiogramSource = null,
            transparencyReadStatus = SettingsReadStatus.WAITING, hearingAidReadStatus = SettingsReadStatus.WAITING,
            loudSoundReductionReadStatus = SettingsReadStatus.WAITING,
            controlStates = state.cachedControlStates(settings, metadata.modelNumber))
        _settings.value = if (settings.allowOffChoice != null && "desired_allow_off" !in settings.savedUnconfirmedChoices)
            settings.copy(savedUnconfirmedChoices = settings.savedUnconfirmedChoices +
                ("desired_allow_off" to if (settings.allowOffChoice) "on" else "off")) else settings
        _metadata.value = metadata
    }

    private val caseBatteryComponents = mutableMapOf<BatteryComponent, Battery>()

    fun receiveNearbyBattery(batteries: Set<Battery>) = receiveNearbyBattery(batteries, allowNewerCase = true)

    fun receiveCaseBattery(batteries: Set<Battery>, observedAt: Long) =
        receiveNearbyBattery(batteries, allowNewerCase = false, caseObservedAt = observedAt)

    private fun receiveNearbyBattery(batteries: Set<Battery>, allowNewerCase: Boolean, caseObservedAt: Long? = null) {
        synchronized(aacp) {
            val keepLive = connectionState.value == ConnectionState.CONNECTED && aacp.captureAudioSocket() != null
            val now = System.currentTimeMillis()
            var caseComponents = emptySet<BatteryComponent>()
            _state.update { state ->
                val fresh = if (caseObservedAt == null) batteries else batteries.filter { incoming ->
                    state.battery.none { it.component == incoming.component && it.observedAt != null && it.observedAt > caseObservedAt }
                }.toSet()
                val reported = if (!keepLive) fresh else fresh.filter { incoming ->
                    ((incoming.isLive(now) && incoming.status != BatteryStatus.UNKNOWN && incoming.level in 0..100) ||
                        (!allowNewerCase && !incoming.present && incoming.status == BatteryStatus.DISCONNECTED)) &&
                        state.battery.none { old ->
                            old.component == incoming.component && old.level in 0..100 &&
                                old.status != BatteryStatus.UNKNOWN && old.isLive(now) &&
                                (allowNewerCase || caseBatteryComponents[old.component] !== old) &&
                                // The case reports independently of the connected earbuds over BLE.
                                (!allowNewerCase || incoming.component != BatteryComponent.CASE || incoming.observedAt!! <= old.observedAt!!)
                        }
                }.toSet()
                val merged = mergeBatteryReports(state.battery, reported, settings.value.cacheDisconnectedComponentBattery)
                if (!allowNewerCase) caseComponents = reported.map { it.component }.toSet()
                state.copy(battery = merged)
            }
            // StateFlow retains the old object on an equal update; track the published instances.
            caseComponents.forEach { component ->
                _state.value.battery.firstOrNull { it.component == component && it.present }?.let {
                    caseBatteryComponents[component] = it
                }
            }
            _connectionState.update {
                if (it == ConnectionState.DISCONNECTED && (allowNewerCase || _state.value.battery.any { battery -> battery.isLive(now) }))
                    ConnectionState.AVAILABLE else it
            }
        }
    }

    fun expireNearbyBattery(now: Long) {
        synchronized(aacp) {
            val connected = connectionState.value == ConnectionState.CONNECTED
            _state.update { state -> state.copy(battery = state.battery.map {
                if (it.present && !it.isLive(now) && (!connected || caseBatteryComponents[it.component] === it))
                    it.copy(status = BatteryStatus.DISCONNECTED, present = false) else it
            }.toSet()) }
            caseBatteryComponents.entries.removeAll { entry -> _state.value.battery.none { it === entry.value } }
            if (!connected && _state.value.battery.none { it.isLive(now) })
                _connectionState.update { if (it == ConnectionState.AVAILABLE) ConnectionState.DISCONNECTED else it }
        }
    }

    // exists because audio processing is done by Service and other parts use Device as source of truth for microphone state and I didn't want to make whole updateState public
    fun updateMicrophoneState(
        transform: (MicrophoneState) -> MicrophoneState
    ) {
        _state.update {
            it.copy(
                microphoneState = transform(it.microphoneState)
            )
        }
    }

    internal inline fun updateState(
        transform: (AppleState) -> AppleState
    ) {
        _state.update { current ->
            val next = transform(current)
            val freshPme = current.hearingModes.pmeRevision != next.hearingModes.pmeRevision
            val superseded = current.controlSession == next.controlSession &&
                (freshPme ||
                    !current.hearingModes.sameClinicalGuards(next.hearingModes) ||
                    !current.hearingAidData.contentEquals(next.hearingAidData))
            val request = if (superseded && current.accommodationsRequest.requested != null)
                next.accommodationsRequest.copy(requested = null,
                    editRevision = current.accommodationsRequest.editRevision + 1,
                    baselineRevision = if (freshPme) next.accommodationsRequest.baselineRevision else current.hearingModes.pmeRevision,
                    result = if (next.accommodationsRequest.pending) next.accommodationsRequest.result
                        else if (freshPme) null else AccommodationsResult.UNCONFIRMED)
            else next.accommodationsRequest
            next.copy(aacpPackets = retainedPacketHistory(next.aacpPackets), accommodationsRequest = request,
                accommodationsAudiogramSource = next.accommodationsAudiogramSource?.takeIf { it.session == next.controlSession })
        }
    }

    internal inline fun updateMetadata(
        transform: (AppleMetadata) -> AppleMetadata
    ) {
        _metadata.update(transform)
    }

    fun updateSettings(
        transform: (AppleSettings) -> AppleSettings
    ) {
        synchronized(_settings) {
            val old = _settings.value
            val next = transform(old)
            if (!LocalPreferenceEdits.remember(context, macAddress.value, LocalPreferenceEdits.changed(old, next))) {
                onSettingsSaveFailed?.invoke()
                return
            }
            _settings.value = next
        }
        if (!settings.value.cacheDisconnectedComponentBattery)
            _state.update { it.copy(battery = it.battery.filter { battery -> battery.isLive() && battery.status != BatteryStatus.DISCONNECTED }.toSet()) }
    }

    var onSettingsSaveFailed: (() -> Unit)? = null

    /** The received stamp carries remote provenance; projecting it is never a new local edit. */
    fun updateSettingsFromCompanion(transform: (AppleSettings) -> AppleSettings) = synchronized(_settings) {
        _settings.value = transform(_settings.value)
    }

    /** Call only inside the successful write's current-transport publication. */
    internal fun rememberAllowOffRequest(value: ByteArray, previousReport: ByteArray?) {
        updateSettings { current ->
            val next = current.withAllowOffRequest(metadata.value.modelNumber, state.value, value, previousReport)
            if (next == current) current else next.copy(savedUnconfirmedChoices = next.savedUnconfirmedChoices +
                ("desired_allow_off" to if (next.allowOffChoice == true) "on" else "off"))
        }
    }

    internal fun rememberSavedControlRequest(id: ControlCommandIdentifier, value: ByteArray) {
        val key = savedControlKey(id) ?: return
        val choice = savedControlChoice(id, value) ?: return
        updateSettings { it.copy(savedUnconfirmedChoices = it.savedUnconfirmedChoices + (key to choice)) }
    }

    /** A fresh report also replaces a silent choice for the next reconnect. */
    internal fun rememberAllowOffReport(value: ByteArray) {
        synchronized(_settings) { _settings.value = _settings.value.withAllowOffReport(metadata.value.modelNumber, value) }
    }

    internal suspend fun emitEvent(event: AppleEvent) {
        _events.emit(event)
    }

    internal suspend fun emitMicrophoneFrame(frame: MicrophoneFrame) {
        _microphoneFrames.emit(frame)
    }

    val aacp = AACPManager(this)
    private val settingsAudioIdle = SettingsAudioIdle(aacp, SystemClock::elapsedRealtimeNanos, ::readNativeCallIdle)
    val att = ATTManager(this)
    private val hearingLock = Mutex()
    private var hearingConfirmationOwner: Any? = null
    private var accommodationsConfirmationOwner: Any? = null
    private data class AccommodationsWriteBase(val socket: BluetoothSocket, val session: Long,
        val editRevision: Long, val plan: AccommodationsPlan, val clinical: ByteArray)
    private var accommodationsWriteBase: AccommodationsWriteBase? = null
    @Volatile internal var attOperationPending = false
        private set
    val settingsOperationPending: Boolean get() = attOperationPending


    // ik, probably wrong place. TODO
    private val gestureDetector by lazy {
        GestureDetector()
    }
    private val legacyHeadTrackingOwner = Any()
    private val legacyHeadGestureOwner = Any()
    private var headGestureOwner: Any? = null

    private fun clearHeadGestureDetection() = synchronized(gestureDetector) {
        headGestureOwner = null
        gestureDetector.stopDetection()
    }

    init {
        updateMetadata {
            it.copy(
                name = bluetoothDevice.alias ?: bluetoothDevice.name ?: "Unknown"
            )
        }
    }

    var onExplicitConnect: (() -> (() -> Boolean)?)? = null

    override fun connect(): Boolean {
        val authorize = onExplicitConnect
        val current = if (authorize == null) ({ true }) else authorize() ?: return false
        return connect(current)
    }

    /** Native-route attachment retires if Android no longer has this pair's audio output. */
    fun connect(stillCurrent: () -> Boolean): Boolean {
        if (!stillCurrent()) return false
        return connectControl { aacp.connect(stillCurrent) {} }
    }

    /** AACP owns attachment; its connection scope starts independent, bounded ATT reads. */
    fun connect(stillCurrent: () -> Boolean, onPendingSocket: (BluetoothSocket) -> Unit): Boolean {
        if (!stillCurrent()) return false
        return connectControl { aacp.connect(stillCurrent, onPendingSocket) }
    }

    private fun connectControl(connect: () -> Boolean): Boolean {
        _connectionState.update { ConnectionState.CONNECTING }
        val success = connect()

        val connected = synchronized(aacp) {
            val current = aacp.captureAudioSocket()
            val connected = success && current != null
            if (connected || current == null)
                _connectionState.value = if (connected) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED
            connected
        }
        _connectionNumber.update {
            it + 1
        }

        return connected
    }

    var onExplicitDisconnect: (() -> Unit)? = null

    fun disconnectControl() {
        clearHeadGestureDetection()
        _connectionState.update {
            ConnectionState.DISCONNECTING
        }

        aacp.disconnect()
        att.disconnect()

        _connectionState.update { ConnectionState.DISCONNECTED }
    }

    override fun disconnect() {
        onExplicitDisconnect?.invoke()
        disconnectControl()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            try {
                bluetoothDevice.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "couldn't disconnect bluetooth device", e)
            }
        }

    }

    internal fun onAacpDisconnected() {
        clearHeadGestureDetection()
        recordingHandler?.invoke(null)
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    fun setControlCommand(identifier: ControlCommandIdentifier, value: ByteArray): Boolean {
        return aacp.sendControlCommand(identifier.value, value)
    }
    fun setControlCommand(identifier: ControlCommandIdentifier, value: Byte): Boolean = setControlCommand(identifier, byteArrayOf(value))
    fun setControlCommand(identifier: ControlCommandIdentifier, value: Int): Boolean =
        value in 0..255 && setControlCommand(identifier, byteArrayOf(value.toByte()))
    fun setControlCommand(identifier: ControlCommandIdentifier, value: Boolean): Boolean =
        aacp.sendControlCommand(identifier.value, value)

    fun setListeningModeFromUi(mode: Int): Boolean = mode in 1..4 &&
        aacp.sendControlCommand(ControlCommandIdentifier.LISTENING_MODE.value,
            byteArrayOf(mode.toByte()), quietListeningMode = true)

    suspend fun setHearingAidEnabled(enabled: Boolean, expectedTransport: BluetoothSocket? = null, expectedSession: Long? = null): Boolean =
        setHearingMode(HearingMode.HEARING_AID, enabled, expectedTransport, expectedSession)
    suspend fun setMediaAssistEnabled(enabled: Boolean, expectedTransport: BluetoothSocket? = null, expectedSession: Long? = null): Boolean =
        setHearingMode(HearingMode.MEDIA_ASSIST, enabled, expectedTransport, expectedSession)

    val settingsSocketReady: Boolean get() = aacp.captureSettingsSocket() != null
    val transparencyTransportAvailable: Boolean get() = att.available
    val loudSoundReductionWritable: Boolean get() = connectionState.value == ConnectionState.CONNECTED &&
        state.value.controlSession > 0 && metadata.value.model != AirPodsModel.UNKNOWN && BaseCapability.LOUD_SOUND_REDUCTION in AirPodsSpecs.getSpec(metadata.value.model).baseCapabilities &&
        state.value.loudSoundReductionReadStatus == SettingsReadStatus.AVAILABLE && att.available(ATTHandle.LOUD_SOUND_REDUCTION) &&
        confirmationAudioIdle() && state.value.hearingModes.pending == null &&
        !state.value.accommodationsRequest.pending

    suspend fun setAccommodations(expected: AccommodationsReport, phone: Boolean?, media: Boolean?,
        preset: AccommodationsPreset?, expectedEditRevision: Long = 0, audiogram: Boolean = false,
        expectedTransport: BluetoothSocket? = null, expectedSession: Long? = null): AccommodationsResult = hearingLock.withLock {
        Log.i(TAG, "Headphone Accommodations requested phone=$phone media=$media preset=$preset audiogram=$audiogram")
        if (preset != null && audiogram || (preset != null || audiogram) && !att.available) return@withLock AccommodationsResult.NOT_SENT
        val transport = aacp.captureAudioSocket() ?: return@withLock AccommodationsResult.NOT_SENT
        val snapshot = state.value
        if (expectedTransport != null && transport !== expectedTransport ||
            expectedSession != null && snapshot.controlSession != expectedSession) return@withLock AccommodationsResult.NOT_SENT
        val original = snapshot.hearingModes.accommodationsReport(snapshot.controlSession)
            ?: return@withLock AccommodationsResult.NOT_SENT
        val receivedPlan = expected.plan(phone ?: expected.phoneEnabled, media ?: expected.mediaEnabled, preset)
        val previous = synchronized(aacp) { accommodationsWriteBase?.takeIf {
            it.socket === transport && it.session == snapshot.controlSession &&
                it.editRevision == snapshot.accommodationsRequest.editRevision &&
                snapshot.accommodationsRequest.requested != null && it.plan.canStart(original)
        } }
        var plan = previous?.plan?.followup(phone, media, preset) ?: receivedPlan
        val owner = Any()
        val session = snapshot.controlSession
        if (metadata.value.model == AirPodsModel.UNKNOWN || !receivedPlan.canStart(original) ||
            snapshot.accommodationsRequest.editRevision != expectedEditRevision ||
            !snapshot.accommodationsRequest.editable(original) || attOperationPending ||
            snapshot.microphoneState.isActive || aacp.isMicrophoneRequested()) return@withLock AccommodationsResult.NOT_SENT
        if (!plan.changesRequired && preset == null && !audiogram) return@withLock AccommodationsResult.NO_CHANGE
        val reserved = synchronized(aacp) {
            val current = state.value
            if (!aacp.ownsAudioSocket(transport) || current.controlSession != session ||
                current.accommodationsRequest.editRevision != expectedEditRevision ||
                current.accommodationsRequest.pending || current.hearingModes.pending != null || attOperationPending ||
                current.microphoneState.isActive || aacp.isMicrophoneRequested() ||
                current.hearingModes.accommodationsReport(session)?.let(plan::canStart) != true) false
            else {
                accommodationsConfirmationOwner = owner
                updateState { it.copy(accommodationsRequest = it.accommodationsRequest.copy(
                    pending = true, result = null, baselineRevision = original.receptionRevision)) }
                true
            }
        }
        if (!reserved) return@withLock AccommodationsResult.NOT_SENT
        Log.i(TAG, "Headphone Accommodations accepted phone=${plan.selection.phone} media=${plan.selection.media} " +
            "preset=${plan.selection.preset} audiogram=$audiogram changesRequired=${plan.changesRequired}")
        fun ownsOperation(): Boolean {
            val value = state.value
            return aacp.ownsAudioSocket(transport) && connectionState.value == ConnectionState.CONNECTED &&
                value.controlSession == session && ownsAccommodationsConfirmation(owner) && confirmationAudioIdle()
        }
        fun current(): Boolean = ownsOperation() && state.value.hearingModes.sameClinicalGuards(snapshot.hearingModes) &&
            state.value.hearingModes.accommodationsReport(session)?.let(plan::acceptsReported) == true
        var attempted = false
        var transparencyAttempted = false
        var transparencyConfirmed = false
        var result = AccommodationsResult.NOT_SENT
        var stage = "preflight"
        var pmeSend = AccommodationsSendOutcome.NOT_ATTEMPTED
        try {
            result = withTimeoutOrNull(15.seconds) operation@ {
                if (!current()) return@operation result
                stage = "read-transparency"
                val transparency = att.readCharacteristic(ATTHandle.TRANSPARENCY, ::current)
                    ?.copyOf()?.takeIf { it.size == 104 && parseTransparencySettingsResponse(it) != null }
                    ?: return@operation result
                val attSession = att.sessionToken() ?: return@operation result
                fun currentPair(): Boolean = current() && att.ownsSession(attSession)
                stage = "read-clinical-guard"
                val clinical = att.readCharacteristic(ATTHandle.HEARING_AID, ::currentPair)
                    ?.copyOf()?.takeIf { it.size == 104 && parseHearingAidSettingsResponse(it) != null }
                    ?: return@operation result
                if (previous != null && !previous.clinical.contentEquals(clinical)) {
                    result = AccommodationsResult.UNCONFIRMED
                    return@operation result
                }
                val source = AccommodationsAudiogram.parse(clinical, session)
                if (audiogram) plan = plan.withAudiogram(source ?: return@operation result)
                val requestedTransparency = when {
                    audiogram -> source?.transparency(transparency) ?: return@operation result
                    preset != null -> accommodationsTransparencyPreset(transparency, preset) ?: return@operation result
                    else -> transparency
                }
                val transparencyChanges = !requestedTransparency.contentEquals(transparency)
                val selectedAudiogram = preset == null && (audiogram ||
                    (previous?.let { snapshot.accommodationsRequest.requested?.audiogram } ?: (source?.let(plan::matchesAudiogram) == true)))
                if (!plan.changesRequired && !transparencyChanges) {
                    if (!currentPair()) return@operation result
                    publishATTValue(ATTHandle.TRANSPARENCY, transparency)
                    publishATTValue(ATTHandle.HEARING_AID, clinical)
                    if (previous != null && selectedAudiogram != snapshot.accommodationsRequest.requested?.audiogram) {
                        val revision = state.value.accommodationsRequest.editRevision + 1
                        accommodationsWriteBase = AccommodationsWriteBase(transport, session, revision, plan, clinical.copyOf())
                        updateState { it.copy(accommodationsRequest = it.accommodationsRequest.copy(
                            requested = plan.selection.copy(preset = plan.selection.preset.takeUnless { selectedAudiogram },
                                audiogram = selectedAudiogram), editRevision = revision)) }
                        return@operation AccommodationsResult.SENT
                    }
                    return@operation AccommodationsResult.NO_CHANGE
                }
                if (!currentPair() || state.value.hearingModes.accommodationsReport(session)?.let(plan::canStart) != true)
                    return@operation result
                if (plan.changesRequired) {
                    stage = "send-pme"
                    attempted = true
                    result = AccommodationsResult.UNCONFIRMED
                    pmeSend = AccommodationsSendOutcome.INCOMPLETE
                    if (!aacp.sendAudioPacket(transport, plan.packet())) return@operation result
                    pmeSend = AccommodationsSendOutcome.COMPLETE
                }
                if (transparencyChanges) {
                    if (!currentPair()) return@operation result
                    stage = "write-transparency"
                    attempted = true
                    transparencyAttempted = true
                    result = AccommodationsResult.UNCONFIRMED
                    if (!att.writeCharacteristic(ATTHandle.TRANSPARENCY, requestedTransparency, transparency, ::currentPair))
                        return@operation result
                }
                if (!currentPair()) return@operation result
                stage = "verify-readback"
                val actualTransparency = att.readCharacteristic(ATTHandle.TRANSPARENCY, ::currentPair)
                val actualClinical = att.readCharacteristic(ATTHandle.HEARING_AID, ::currentPair)
                if (!currentPair() || !requestedTransparency.contentEquals(actualTransparency) ||
                    !clinical.contentEquals(actualClinical)) return@operation AccommodationsResult.UNCONFIRMED
                synchronized(aacp) {
                    if (!currentPair()) return@synchronized AccommodationsResult.UNCONFIRMED
                    val report = state.value.hearingModes.accommodationsReport(session)
                    val send = if (plan.changesRequired) AccommodationsSendOutcome.COMPLETE else AccommodationsSendOutcome.NOT_ATTEMPTED
                    val reported = plan.result(report, send)
                    val fresh = report != null && report.receptionRevision > original.receptionRevision
                    val outcome = when {
                        reported == AccommodationsResult.CONFIRMED -> AccommodationsResult.CONFIRMED
                        fresh && reported != AccommodationsResult.NO_CHANGE -> AccommodationsResult.UNCONFIRMED
                        previous != null || plan.changesRequired -> AccommodationsResult.SENT
                        else -> AccommodationsResult.CONFIRMED
                    }
                    if (outcome == AccommodationsResult.UNCONFIRMED) return@synchronized outcome
                    publishATTValue(ATTHandle.TRANSPARENCY, actualTransparency)
                    publishATTValue(ATTHandle.HEARING_AID, actualClinical)
                    transparencyConfirmed = true
                    if (outcome == AccommodationsResult.SENT) {
                        val revision = state.value.accommodationsRequest.editRevision + 1
                        accommodationsWriteBase = AccommodationsWriteBase(transport, session, revision, plan, clinical.copyOf())
                        updateState { it.copy(accommodationsRequest = it.accommodationsRequest.copy(
                            requested = plan.selection.copy(preset = plan.selection.preset.takeUnless { selectedAudiogram },
                                audiogram = selectedAudiogram), editRevision = revision)) }
                    } else accommodationsWriteBase = null
                    outcome
                }
            } ?: if (attempted) AccommodationsResult.UNCONFIRMED else AccommodationsResult.NOT_SENT
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            result = if (attempted) AccommodationsResult.UNCONFIRMED else AccommodationsResult.NOT_SENT
            Log.w(TAG, "Headphone Accommodations request failed: ${error.javaClass.simpleName}")
            result
        } finally {
            if (attempted && result == AccommodationsResult.NOT_SENT) result = AccommodationsResult.UNCONFIRMED
            Log.i(TAG, "Headphone Accommodations result=$result stage=$stage pmeSend=$pmeSend " +
                "phone=${plan.selection.phone} media=${plan.selection.media} preset=${plan.selection.preset} " +
                "audiogram=$audiogram transparencyAttempted=$transparencyAttempted transparencyConfirmed=$transparencyConfirmed")
            synchronized(aacp) {
                if (accommodationsConfirmationOwner !== owner) return@synchronized
                if (!state.value.accommodationsRequest.pending) {
                    accommodationsConfirmationOwner = null
                    return@synchronized
                }
                if (transparencyAttempted && !transparencyConfirmed) {
                    publishATTValue(ATTHandle.TRANSPARENCY, null)
                    publishATTValue(ATTHandle.HEARING_AID, null)
                }
                if (result == AccommodationsResult.UNCONFIRMED) accommodationsWriteBase = null
                updateState { state -> state.copy(accommodationsRequest = state.accommodationsRequest.copy(
                    pending = false, result = result, baselineRevision = original.receptionRevision,
                    requested = state.accommodationsRequest.requested.takeUnless { result == AccommodationsResult.UNCONFIRMED },
                    editRevision = state.accommodationsRequest.editRevision + if (result == AccommodationsResult.UNCONFIRMED) 1 else 0)) }
                accommodationsConfirmationOwner = null
            }
        }
    }

    internal fun ownsHearingConfirmation(owner: Any, mode: HearingMode): Boolean = synchronized(aacp) {
        hearingConfirmationOwner === owner && state.value.hearingModes.pending == mode
    }

    internal fun canRefreshHearingConfirmation(owner: Any, mode: HearingMode): Boolean =
        confirmationAudioIdle() && synchronized(aacp) { ownsHearingConfirmation(owner, mode) && confirmationAudioIdle() }

    internal fun ownsAccommodationsConfirmation(owner: Any): Boolean = synchronized(aacp) {
        accommodationsConfirmationOwner === owner && state.value.accommodationsRequest.pending
    }

    internal fun canRefreshAccommodationsConfirmation(owner: Any): Boolean =
        confirmationAudioIdle() && synchronized(aacp) { ownsAccommodationsConfirmation(owner) && confirmationAudioIdle() }

    private fun confirmationAudioIdle(): Boolean = !state.value.microphoneState.isActive &&
        !aacp.isMicrophoneRequested() && !state.value.recordingState.isRecording &&
        state.value.audioSource.type != AudioSourceType.CALL && settingsAudioIdle.isIdle()

    private suspend fun reopenForConfirmation(request: AACPManager.ControlSessionRefresh, current: () -> Boolean): Boolean =
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { aacp.cancelControlSessionRefresh(request) }
                val result = aacp.refreshControlSession(request, current)
                if (continuation.isActive) continuation.resume(result)
            }
        }

    @Suppress("DEPRECATION")
    private fun readNativeCallIdle(): Boolean = runCatching {
        context.getSystemService(AudioManager::class.java)?.mode == AudioManager.MODE_NORMAL &&
            (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED ||
                context.getSystemService(TelephonyManager::class.java)?.callState == TelephonyManager.CALL_STATE_IDLE)
    }.getOrDefault(false)

    private suspend fun setHearingMode(mode: HearingMode, enabled: Boolean,
        expectedTransport: BluetoothSocket?, expectedSession: Long?): Boolean {
        val requestedTransport = expectedTransport ?: aacp.captureAudioSocket() ?: return false
        val requestedSession = expectedSession ?: state.value.controlSession
        return hearingLock.withLock {
            if (aacp.captureAudioSocket() !== requestedTransport || state.value.controlSession != requestedSession) return@withLock false
            if (state.value.accommodationsRequest.pending) return@withLock false
            if (enabled && state.value.accommodationsRequest.requested != null) return@withLock false
            if (enabled && state.value.accommodationsRequest.result == AccommodationsResult.UNCONFIRMED &&
                !state.value.accommodationsRequest.editable(state.value.hearingModes.accommodationsReport(state.value.controlSession))) return@withLock false
            if (metadata.value.model == AirPodsModel.UNKNOWN ||
                BaseCapability.HEARING_AID !in AirPodsSpecs.getSpec(metadata.value.model).baseCapabilities) return@withLock false
            var transport = requestedTransport
            val original = state.value.hearingModes
            val plan = original.plan(mode, enabled) ?: return@withLock false
            if (plan.packets.isEmpty()) return@withLock true
            val owner = Any()
            val current = { aacp.ownsAudioSocket(transport) && ownsHearingConfirmation(owner, mode) }
            val reserved = synchronized(aacp) {
                if (!aacp.ownsAudioSocket(transport) || state.value.controlSession != requestedSession || state.value.hearingModes !== original ||
                    (enabled && (state.value.microphoneState.isActive || aacp.isMicrophoneRequested()))) false
                else {
                    hearingConfirmationOwner = owner
                    updateState { it.copy(hearingModes = original.copy(pending = mode)) }
                    true
                }
            }
            if (!reserved) return@withLock false
            var attempted = false
            var confirmed = false
            try {
                confirmed = withTimeoutOrNull(15.seconds) confirmation@ {
                    val fitting = if (enabled) att.readCharacteristic(ATTHandle.HEARING_AID) else null
                    if (!current()) return@confirmation false
                    if (enabled) {
                        if (fitting == null || !hearingAidSettingsWritable(fitting)) return@confirmation false
                        val transparency = att.readCharacteristic(ATTHandle.TRANSPARENCY) ?: return@confirmation false
                        if (!current() || customTransparencyEnabled(transparency) != false) return@confirmation false
                    }
                    if (!state.value.hearingModes.sameConfiguration(original)) return@confirmation false
                    if (enabled && (state.value.microphoneState.isActive || aacp.isMicrophoneRequested())) return@confirmation false
                    for (packet in plan.packets) {
                        if (!current()) return@confirmation false
                        attempted = true
                        if (!aacp.sendAudioPacket(transport, packet)) return@confirmation false
                    }
                    var freshSession = false
                    val spontaneous = withTimeoutOrNull(1.seconds) {
                        state.first { !current() || it.hearingModes.confirms(plan) }
                    }
                    if (!current()) return@confirmation false
                    if (spontaneous?.hearingModes?.confirms(plan) != true) {
                        val refresh = aacp.prepareHearingConfirmationRefresh(transport, owner, mode)
                            ?: return@confirmation false
                        // Closing only this request's socket also releases a blocked connect on cancellation.
                        val reopened = reopenForConfirmation(refresh) { canRefreshHearingConfirmation(owner, mode) }
                        if (!reopened) return@confirmation false
                        transport = refresh.replacement?.takeIf(aacp::ownsAudioSocket) ?: return@confirmation false
                        freshSession = true
                    }
                    fun matches(value: AppleState): Boolean = if (freshSession) value.hearingModes.confirmsFreshSession(plan)
                        else value.hearingModes.confirms(plan)
                    val report = state.first { !current() || (freshSession && !canRefreshHearingConfirmation(owner, mode)) || matches(it) }
                    if (!current() || (freshSession && !canRefreshHearingConfirmation(owner, mode)) || !matches(report)) return@confirmation false
                    if (enabled) {
                        // Mode reports can arrive before the replacement settings transport finishes startup.
                        while (freshSession && current() && canRefreshHearingConfirmation(owner, mode) &&
                            aacp.captureSettingsSocket() !== transport)
                            delay(50.milliseconds)
                        if (!current() || (freshSession && !canRefreshHearingConfirmation(owner, mode)) ||
                            !matches(state.value)) return@confirmation false
                        val readback = att.readCharacteristic(ATTHandle.HEARING_AID)
                        if (!current() || !fitting.contentEquals(readback) || !matches(state.value)) return@confirmation false
                    }
                    true
                } == true
                confirmed
            } finally {
                synchronized(aacp) {
                    if (hearingConfirmationOwner !== owner) return@synchronized
                    updateState { state ->
                        if (state.hearingModes.pending != mode) state else state.copy(hearingModes = state.hearingModes.copy(
                            pending = null, uncertain = if (confirmed) null else if (attempted) mode else original.uncertain))
                    }
                    hearingConfirmationOwner = null
                }
            }
        }
    }

    suspend fun writeATTCharacteristic(handle: ATTHandle, value: ByteArray, expected: ByteArray? = null,
        expectedTransport: BluetoothSocket? = null, expectedSession: Long? = null): Boolean {
        val requested = value.copyOf()
        if (handle == ATTHandle.LOUD_SOUND_REDUCTION && state.value.loudSoundReductionReadStatus != SettingsReadStatus.AVAILABLE) return false
        val baseline = expected?.copyOf() ?: if (handle == ATTHandle.LOUD_SOUND_REDUCTION)
            byteArrayOf(if (state.value.loudSoundReductionEnabled) 1 else 0) else return false
        val transport = aacp.captureAudioSocket() ?: return false
        if (expectedTransport != null && transport !== expectedTransport) return false
        return hearingLock.withLock {
            val modes = state.value.hearingModes
            var attSession: Any? = null
            val requiresModes = handle == ATTHandle.HEARING_AID ||
                (handle == ATTHandle.TRANSPARENCY && customTransparencyEnabled(requested) == true)
            fun cacheMatches(): Boolean = when (handle) {
                ATTHandle.HEARING_AID -> baseline.contentEquals(state.value.hearingAidData)
                ATTHandle.TRANSPARENCY -> baseline.contentEquals(state.value.transparencyData)
                ATTHandle.LOUD_SOUND_REDUCTION -> state.value.loudSoundReductionReadStatus == SettingsReadStatus.AVAILABLE &&
                    baseline.contentEquals(byteArrayOf(if (state.value.loudSoundReductionEnabled) 1 else 0))
            }
            fun current(): Boolean {
                val state = state.value
                return aacp.ownsAudioSocket(transport) && connectionState.value == ConnectionState.CONNECTED &&
                    (expectedSession == null || state.controlSession == expectedSession) &&
                    (attSession == null || att.ownsSession(attSession)) &&
                    confirmationAudioIdle() && !state.accommodationsRequest.pending && cacheMatches() &&
                    (!requiresModes || (state.hearingModes.pending == null && state.hearingModes.uncertain == null &&
                        state.hearingModes.sameConfiguration(modes)))
            }
            when (handle) {
                ATTHandle.HEARING_AID -> if (!hearingAidSettingsWritable(baseline) ||
                    !hearingAidSettingsWritable(requested) || !requested.copyOfRange(0, 4).contentEquals(byteArrayOf(2, 2, 0x64, 0)) ||
                    metadata.value.model == AirPodsModel.UNKNOWN ||
                    BaseCapability.HEARING_AID !in AirPodsSpecs.getSpec(metadata.value.model).baseCapabilities ||
                    modes.enrolled != true || !modes.clinicalReportsKnown) return@withLock false
                ATTHandle.TRANSPARENCY -> {
                    if (parseTransparencySettingsResponse(baseline) == null || baseline.size != requested.size) return@withLock false
                    val settings = parseTransparencySettingsResponse(requested) ?: return@withLock false
                    if (settings.enabled && (metadata.value.model == AirPodsModel.UNKNOWN ||
                        (BaseCapability.HEARING_AID in AirPodsSpecs.getSpec(metadata.value.model).baseCapabilities &&
                            !modes.customTransparencyAllowed))) return@withLock false
                }
                ATTHandle.LOUD_SOUND_REDUCTION -> if (!validATTValue(handle, requested) || !validATTValue(handle, baseline) ||
                    metadata.value.model == AirPodsModel.UNKNOWN || BaseCapability.LOUD_SOUND_REDUCTION !in AirPodsSpecs.getSpec(metadata.value.model).baseCapabilities) return@withLock false
            }
            if (!current()) return@withLock false
            val reserved = synchronized(aacp) {
                if (attOperationPending || !current()) false else { attOperationPending = true; true }
            }
            if (!reserved) return@withLock false
            var confirmed = false
            try {
                val fresh = att.readCharacteristic(handle, ::current) ?: return@withLock false
                attSession = att.sessionToken() ?: return@withLock false
                if (!current()) return@withLock false
                if (!baseline.contentEquals(fresh)) {
                    if (validATTValue(handle, fresh)) synchronized(aacp) {
                        if (current()) publishATTValue(handle, fresh)
                    }
                    return@withLock false
                }
                if (!att.writeCharacteristic(handle, requested, baseline, ::current)) return@withLock false
                for (attempt in 0..4) {
                    val readback = att.readCharacteristic(handle, ::current) ?: return@withLock false
                    if (!current() || !validATTValue(handle, readback)) return@withLock false
                    val matches = if (handle == ATTHandle.HEARING_AID)
                        hearingAidSettingsWritable(readback) && readback.copyOfRange(4, 104).contentEquals(requested.copyOfRange(4, 104))
                    else readback.contentEquals(requested)
                    if (matches) {
                        synchronized(aacp) {
                            if (current()) { publishATTValue(handle, readback); confirmed = true }
                        }
                        return@withLock confirmed
                    }
                    val unchanged = if (handle == ATTHandle.HEARING_AID)
                        hearingAidSettingsWritable(readback) && readback.copyOfRange(4, 104).contentEquals(baseline.copyOfRange(4, 104))
                    else readback.contentEquals(baseline)
                    if (!unchanged || attempt == 4) return@withLock false
                    delay(75.milliseconds)
                }
                false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "ATT setting could not be confirmed: ${error.javaClass.simpleName}")
                false
            } finally {
                synchronized(aacp) {
                    if (!confirmed && aacp.ownsAudioSocket(transport) && cacheMatches()) publishATTValue(handle, null)
                    attOperationPending = false
                }
            }
        }
    }

    private fun validATTValue(handle: ATTHandle, value: ByteArray): Boolean = when (handle) {
        ATTHandle.HEARING_AID -> parseHearingAidSettingsResponse(value) != null
        ATTHandle.TRANSPARENCY -> customTransparencyEnabled(value) != null
        ATTHandle.LOUD_SOUND_REDUCTION -> value.size == 1 && value[0] in byteArrayOf(0, 1)
    }

    private fun publishATTValue(handle: ATTHandle, value: ByteArray?) {
        updateState {
            when (handle) {
                ATTHandle.LOUD_SOUND_REDUCTION -> it.copy(loudSoundReductionEnabled = value?.get(0)?.let { byte -> byte == 1.toByte() } ?: it.loudSoundReductionEnabled,
                    loudSoundReductionReadStatus = if (value == null) SettingsReadStatus.UNAVAILABLE else SettingsReadStatus.AVAILABLE)
                ATTHandle.TRANSPARENCY -> it.copy(transparencyData = value?.copyOf() ?: byteArrayOf(),
                    transparencyReadStatus = if (value == null) SettingsReadStatus.UNAVAILABLE else SettingsReadStatus.AVAILABLE)
                ATTHandle.HEARING_AID -> it.copy(hearingAidData = value?.copyOf() ?: byteArrayOf(),
                    hearingAidReadStatus = if (value == null) SettingsReadStatus.UNAVAILABLE else SettingsReadStatus.AVAILABLE,
                    accommodationsAudiogramSource = value?.let { data -> AccommodationsAudiogram.parse(data, it.controlSession) })
            }
        }
    }

    fun observeATTCharacteristic(handle: ATTHandle): Job = CoroutineScope(Dispatchers.IO).launch {
        while (true) {
            readATTCharacteristic(handle)
            delay(1000.milliseconds)
        }
    }

    suspend fun readATTCharacteristic(handle: ATTHandle): ByteArray? {
        val transport = aacp.captureAudioSocket() ?: return null
        return readATTCharacteristic(handle, transport)
    }

    private class InitialATTRead(val transport: BluetoothSocket, val session: Long) {
        val attempted = mutableSetOf<ATTHandle>()
        var retryAfter = 0L
        var running = false
    }
    private var initialATTRead: InitialATTRead? = null

    internal suspend fun readInitialATTCharacteristics(transport: BluetoothSocket) {
        while (aacp.ownsAudioSocket(transport) && connectionState.value == ConnectionState.CONNECTING)
            delay(100.milliseconds)
        val session = state.value.controlSession
        fun current() = session > 0 && aacp.ownsAudioSocket(transport) &&
            state.value.controlSession == session && connectionState.value == ConnectionState.CONNECTED
        if (!current()) return
        val initial = synchronized(aacp) {
            val owned = initialATTRead?.takeIf { it.transport === transport && it.session == session }
                ?: InitialATTRead(transport, session).also { initialATTRead = it }
            if (owned.running) return
            owned.running = true
            owned
        }
        try {
            while (current()) {
                if (metadata.value.model == AirPodsModel.UNKNOWN) {
                    delay(250.milliseconds)
                    continue
                }
                val capabilities = AirPodsSpecs.getSpec(metadata.value.model).baseCapabilities
                synchronized(aacp) {
                    if (current()) updateState { it.copy(
                        hearingAidReadStatus = if (BaseCapability.HEARING_AID !in capabilities) SettingsReadStatus.UNAVAILABLE else it.hearingAidReadStatus,
                        transparencyReadStatus = if (BaseCapability.LISTENING_MODE !in capabilities) SettingsReadStatus.UNAVAILABLE else it.transparencyReadStatus,
                        loudSoundReductionReadStatus = if (BaseCapability.LOUD_SOUND_REDUCTION !in capabilities) SettingsReadStatus.UNAVAILABLE else it.loudSoundReductionReadStatus) }
                }
                val handle = listOf(ATTHandle.HEARING_AID, ATTHandle.TRANSPARENCY, ATTHandle.LOUD_SOUND_REDUCTION).firstOrNull { handle ->
                    handle !in initial.attempted && when (handle) {
                        ATTHandle.HEARING_AID -> BaseCapability.HEARING_AID in capabilities
                        ATTHandle.TRANSPARENCY -> BaseCapability.LISTENING_MODE in capabilities
                        ATTHandle.LOUD_SOUND_REDUCTION -> BaseCapability.LOUD_SOUND_REDUCTION in capabilities
                    }
                } ?: return
                if (System.nanoTime() / 1_000_000 < initial.retryAfter) { delay(250.milliseconds); continue }
                if (hearingLock.isLocked || attOperationPending || state.value.hearingModes.pending != null ||
                    state.value.accommodationsRequest.pending || !confirmationAudioIdle()) {
                    delay(250.milliseconds)
                    continue
                }
                try {
                    val value = readATTCharacteristic(handle, transport, session) { initial.attempted += handle }
                    if (value == null && current() && att.canRetryRead()) {
                        initial.attempted -= handle
                        initial.retryAfter = System.nanoTime() / 1_000_000 + 5_000
                    }
                } catch (cancelled: CancellationException) {
                    if (current() && att.canRetryRead()) {
                        initial.attempted -= handle
                        initial.retryAfter = System.nanoTime() / 1_000_000 + 5_000
                    }
                    throw cancelled
                }
                // A busy read which never entered ATT must remain available after the call or recording ends.
                if (handle !in initial.attempted) delay(250.milliseconds)
            }
        } finally {
            synchronized(aacp) { if (initialATTRead === initial) initial.running = false }
        }
    }

    private suspend fun readATTCharacteristic(handle: ATTHandle, transport: BluetoothSocket,
        session: Long = state.value.controlSession, onAttempt: () -> Unit = {}): ByteArray? {
        return hearingLock.withLock {
            fun ownsRead() = aacp.ownsAudioSocket(transport) && connectionState.value == ConnectionState.CONNECTED &&
                state.value.controlSession == session
            fun current() = ownsRead() && confirmationAudioIdle()
            if (!current()) return@withLock null
            val reserved = synchronized(aacp) {
                if (attOperationPending || !current()) false else { attOperationPending = true; true }
            }
            if (!reserved) return@withLock null
            var attempted = false
            var published = false
            try {
                val value = withTimeoutOrNull(15.seconds) {
                    att.readCharacteristic(handle, ::current) {
                        attempted = true
                        onAttempt()
                        if (!current()) return@readCharacteristic
                        synchronized(aacp) {
                            if (current()) updateState { when (handle) {
                                ATTHandle.HEARING_AID -> it.copy(hearingAidReadStatus = SettingsReadStatus.READING)
                                ATTHandle.TRANSPARENCY -> it.copy(transparencyReadStatus = SettingsReadStatus.READING)
                                ATTHandle.LOUD_SOUND_REDUCTION -> it.copy(loudSoundReductionReadStatus = SettingsReadStatus.READING)
                            } }
                        }
                    }
                }?.copyOf()?.takeIf { validATTValue(handle, it) }
                if (!current()) return@withLock null
                synchronized(aacp) {
                    if (!current() || !attempted) null else { publishATTValue(handle, value); published = true; value }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "ATT setting read failed: ${error.javaClass.simpleName}")
                if (current()) synchronized(aacp) { if (current()) { publishATTValue(handle, null); published = true } }
                null
            } finally {
                synchronized(aacp) {
                    if (attempted && !published && ownsRead()) publishATTValue(handle, null)
                    attOperationPending = false
                }
            }
        }
    }

    fun requestMicrophoneStream() = aacp.requestMicrophoneStream()
    fun endMicrophoneStream() {
        aacp.endMicrophoneStream()
    }

    fun startRecording(recording: Recording) {
        if (recordingHandler?.invoke(recording) != true) return
        updateState {
            it.copy(
                recordingState = RecordingState(
                    isRecording = true,
                    recording = recording
                )
            )
        }
    }
    fun stopRecording() {
        recordingHandler?.invoke(null)
        updateState {
            it.copy(
                recordingState = RecordingState(
                    isRecording = false,
                    recording = null
                ),
                microphoneState = MicrophoneState(),
            )
        }
    }

    /** The application owns permission, foreground access, decoding and playback recovery. */
    var recordingHandler: ((Recording?) -> Boolean)? = null

    fun updateRecordingState(transform: (RecordingState) -> RecordingState) {
        updateState { it.copy(recordingState = transform(it.recordingState)) }
    }

    fun setListeningModeCycle(mask: Int, expectedMetadata: AppleMetadata,
                              expectedSocket: BluetoothSocket?): Boolean =
        aacp.sendControlCommand(ControlCommandIdentifier.LISTENING_MODE_CONFIGS.value,
            byteArrayOf(mask.toByte()), expectedSocket = expectedSocket) {
            metadata.value === expectedMetadata && validNoiseModeCycle(mask,
                expectedMetadata.supportedNoiseModeCycleMask(state.value.effectiveAllowOff(settings.value, expectedMetadata.modelNumber)))
        }

    fun setCallControls(singlePress: Boolean, expectedMetadata: AppleMetadata,
                        expectedSocket: BluetoothSocket?): Boolean =
        aacp.sendControlCommand(ControlCommandIdentifier.CALL_MANAGEMENT_CONFIG.value,
            legacyEndCallValue(singlePress), expectedSocket = expectedSocket) {
            metadata.value === expectedMetadata && expectedMetadata.supportsCallControls() && state.value.canSetLegacyEndCall()
        }

    private val holdActionLock = Any()
    private var holdActionSocket: BluetoothSocket? = null
    private var rawHoldActions = false

    /** 0x39 delegates only holds; normal single/double/triple presses stay native. */
    fun configureHoldActions(expected: BluetoothSocket): Boolean = synchronized(holdActionLock) {
        applyHoldActions(expected, settings.value)
    }

    private fun applyHoldActions(expected: BluetoothSocket, choice: AppleSettings): Boolean {
        val customized = choice.leftLongPressAction == StemAction.DIGITAL_ASSISTANT ||
            choice.rightLongPressAction == StemAction.DIGITAL_ASSISTANT
        if (!aacp.ownsAudioSocket(expected)) return false
        if (holdActionSocket === expected && rawHoldActions == customized) return true
        val packet = ControlCommandPacket.create(
            ControlCommand(
                ControlCommandIdentifier.RAW_GESTURES_CONFIG, byteArrayOf(if (customized) 8 else 0)))
        if (!aacp.sendPacket(packet, expected) || !aacp.ownsAudioSocket(expected)) return false
        holdActionSocket = expected
        rawHoldActions = customized
        return true
    }

    fun setLongPressAction(side: String, action: StemAction,
                          expectedSocket: BluetoothSocket? = aacp.captureAudioSocket(),
                          expectedSession: Long = state.value.controlSession): Boolean = synchronized(holdActionLock) {
        val left = when (side.lowercase(java.util.Locale.ROOT)) { "left" -> true; "right" -> false; else -> return@synchronized false }
        if (action !in setOf(StemAction.CYCLE_NOISE_CONTROL_MODES, StemAction.DIGITAL_ASSISTANT)) return@synchronized false
        val expected = expectedSocket?.takeIf {
            aacp.ownsAudioSocket(it) && state.value.controlSession == expectedSession
        } ?: return@synchronized false
        val choice = if (left) settings.value.copy(leftLongPressAction = action) else settings.value.copy(rightLongPressAction = action)
        if (!applyHoldActions(expected, choice)) return@synchronized false
        updateSettings { if (left) it.copy(leftLongPressAction = action) else it.copy(rightLongPressAction = action) }
        true
    }

    fun hostHoldAction(event: AppleEvent.StemPress): StemAction? = synchronized(holdActionLock) {
        if (event.pressType != StemPressType.LONG_PRESS || !rawHoldActions ||
            holdActionSocket !== event.sourceSocket || !aacp.ownsAudioSocket(event.sourceSocket)) return@synchronized null
        if (event.bud == StemPressBud.LEFT) settings.value.leftLongPressAction else settings.value.rightLongPressAction
    }

    fun cycleHoldListeningMode(event: AppleEvent.StemPress): Boolean = synchronized(holdActionLock) {
        if (hostHoldAction(event) != StemAction.CYCLE_NOISE_CONTROL_MODES) return@synchronized false
        val mode = state.value.nextHoldListeningMode(settings.value, metadata.value) ?: return@synchronized false
        aacp.sendPacket(ControlCommandPacket.create(
            ControlCommand(ControlCommandIdentifier.LISTENING_MODE, byteArrayOf(mode.toByte()))), event.sourceSocket)
    }

    fun renameDevice(newName: String, expected: BluetoothSocket? = aacp.captureAudioSocket()): Boolean =
        aacp.sendRename(newName, expected)

    fun startHeadTracking() {
        startHeadTracking(legacyHeadTrackingOwner)
    }

    fun stopHeadTracking() {
        stopHeadTracking(legacyHeadTrackingOwner)
        stopHeadTracking(legacyHeadGestureOwner)
    }

    fun startHeadTracking(owner: Any): Boolean = aacp.setHeadTrackingEnabled(true, owner)

    /** Temporary sampling request owned by this client; never changes the saved interval. */
    fun startHeadTracking(owner: Any, interval: Duration): Boolean = aacp.setHeadTrackingEnabled(true, owner, interval)

    fun stopHeadTracking(owner: Any): Boolean {
        synchronized(gestureDetector) {
            if (headGestureOwner === owner) clearHeadGestureDetection()
        }
        return aacp.setHeadTrackingEnabled(false, owner)
    }

    /** Pause sensor transport while the HQ microphone owns it; preserve user sensor intent. */
    fun setSensorsSuspended(suspended: Boolean) {
        aacp.setSensorsSuspended(suspended)
    }

    fun detectHeadGestures(callback: (Boolean) -> Unit) {
        detectHeadGestures(legacyHeadGestureOwner, callback)
    }

    // Gesture detection still accepts one callback; each requester owns only its own motion stream.
    fun detectHeadGestures(owner: Any, callback: (Boolean) -> Unit): Boolean {
        synchronized(gestureDetector) {
            if (headGestureOwner != null) return false
            headGestureOwner = owner
        }
        if (!startHeadTracking(owner)) {
            stopHeadTracking(owner)
            return false
        }
        val started = synchronized(gestureDetector) {
            headGestureOwner === owner && gestureDetector.startDetection(HeadTracking.angularVelocity) { gesture ->
                try {
                    if (synchronized(gestureDetector) { headGestureOwner === owner }) callback(gesture)
                } finally {
                    stopHeadTracking(owner)
                }
            }
        }
        if (!started) {
            stopHeadTracking(owner)
            Log.w(TAG, "Failed to start gesture detection, probably because already running.")
        }
        return started
    }

    fun setHeadGesturesEnabled(enabled: Boolean) {
        updateSettings {
            it.copy(headGesturesEnabled = enabled)
        }
    }

    fun setHeadGesturesVerticalOffset(offset: Int) {
        updateSettings {
            it.copy(headGesturesVerticalOffset = offset)
        }
    }

    fun setHeadGesturesHorizontalOffset(offset: Int) {
        updateSettings {
            it.copy(headGesturesHorizontalOffset = offset)
        }
    }

    fun setHeadTrackingInterval(interval: Duration): Boolean {
        if (!aacp.setHeadTrackingInterval(interval)) return false
        updateSettings { it.copy(headTrackingInterval = interval, headTrackingIntervalConfigured = true) }
        return true
    }

    fun setCustomEqEnabled(enabled: Boolean): Boolean =
        aacp.setCustomEq((_state.value.requestedCustomEq ?: _state.value.customEq).copy(state = if(enabled) 2 else 1))

    fun setCustomEq(low: Int, mid: Int, high: Int): Boolean {
        require(low in 0..100)
        require(mid in 0..100)
        require(high in 0..100)
        return aacp.setCustomEq((_state.value.requestedCustomEq ?: _state.value.customEq).copy(low = low, mid = mid, high = high))
    }

    // ACTIVE is reported only after a validated sample, never after a successful write alone.
    fun startHr(): Boolean = aacp.setHeartRateEnabled(true)
    fun stopHr(): Boolean = aacp.setHeartRateEnabled(false)

    fun sendRawPacket(data: ByteArray): Boolean = aacp.sendRawPacket(data)

////    private val _cameraAction = MutableStateFlow(
////        sharedPreferences.getString("camera_action", null)
////            ?.let { value -> StemPressType.entries.find { it.name == value } })
////
////    val cameraAction: StateFlow<StemPressType?> = _cameraAction
////
////    fun setCameraAction(action: StemPressType?) {
////        sharedPreferences.edit {
////            if (action == null) remove("camera_action")
////            else putString("camera_action", action.name)
////        }
////        _cameraAction.value = action
////    }

}
