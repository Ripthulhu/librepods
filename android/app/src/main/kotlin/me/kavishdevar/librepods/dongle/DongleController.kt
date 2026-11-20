package me.kavishdevar.librepods.dongle

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.Closeable
import java.io.PrintWriter
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.InformationPacket
import me.kavishdevar.librepods.core.bluetooth.att.types.HearingAidSettings
import me.kavishdevar.librepods.core.bluetooth.att.types.hearingAidSettingsWritable
import me.kavishdevar.librepods.core.bluetooth.att.types.parseHearingAidSettingsResponse
import me.kavishdevar.librepods.core.bluetooth.att.types.sendHearingAidSettings
import me.kavishdevar.librepods.core.devices.AccommodationsAudiogram
import me.kavishdevar.librepods.core.devices.AccommodationsPreset
import me.kavishdevar.librepods.core.devices.AccommodationsReport
import me.kavishdevar.librepods.core.devices.AccommodationsRequestState
import me.kavishdevar.librepods.core.devices.AccommodationsResult
import me.kavishdevar.librepods.core.devices.HearingMode
import me.kavishdevar.librepods.core.devices.HearingModes
import me.kavishdevar.librepods.core.devices.SettingsReadStatus
import me.kavishdevar.librepods.data.LiveAccommodationsEditor
import me.kavishdevar.librepods.data.LiveAccommodationsSource
import me.kavishdevar.librepods.data.LiveRecordEditor
import me.kavishdevar.librepods.data.LiveRecordSource
import me.kavishdevar.librepods.data.LiveValueEditor
import me.kavishdevar.librepods.data.LiveValueSource
import me.kavishdevar.librepods.dongle.DongleControlProtocol.AudioProfile.InputOwnership
import me.kavishdevar.librepods.dongle.DongleControlProtocol.CachedSlot
import me.kavishdevar.librepods.dongle.DongleControlProtocol.Control.Capability
import me.kavishdevar.librepods.dongle.DongleControlProtocol.Control.Codec
import me.kavishdevar.librepods.dongle.DongleControlProtocol.Control.Opcode
import me.kavishdevar.librepods.dongle.DongleControlProtocol.Control.StatusFlag
import me.kavishdevar.librepods.dongle.DongleControlProtocol.Feature
import me.kavishdevar.librepods.dongle.DongleControlProtocol.Rpc
import me.kavishdevar.librepods.dongle.DongleControlProtocol.VolumeAuthority
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient
import me.kavishdevar.librepods.utils.MediaController
import me.kavishdevar.librepods.dongle.DongleControlProtocol.AudioProfile.Flag as AudioProfileFlag

/**
 * One foreground-service-owned control link. Creating it never selects an audio route.
 *
 * Coordinates the transport and its polling loop; editing ownership lives in [DongleEditingLease],
 * sent-but-unanswered writes in [DonglePendingWrites], remembered choices in [DongleSavedChoiceStore],
 * the hearing records in [DongleHearingRecords] and the polling loop's own reads in [DongleAutomaticReads].
 */
@SuppressLint("MissingPermission")
internal class DongleController(context: Context) : Closeable {
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val savedChoiceStore = DongleSavedChoiceStore(preferences)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bridge by lazy { ShizukuAudioClient(this.context) }
    private val io = Mutex()
    private val refreshRequested = Channel<Unit>(Channel.CONFLATED)
    private val lifecycle = DongleLifecycle()
    private val earClaim = DongleEarClaim()
    private val mutableState = MutableStateFlow(DongleState(
        selected = preferences.contains(ADDRESS_KEY),
        automaticPause = preferences.getBoolean(AUTOMATIC_PAUSE_KEY, false),
        usbVolumeRequested = volumeRequested(),
    ))
    val state = mutableState.asStateFlow()
    private val lease = DongleEditingLease(lifecycle, io, mutableState,
        session = { session },
        callActive = { callActive },
        volumeCheckpoint = ::volumeCheckpoint,
        requestRefresh = { refreshRequested.trySend(Unit) })
    private val hearing = DongleHearingRecords(mutableState, lease)
    private val automaticReads = DongleAutomaticReads(mutableState, lease, hearing, callActive = { callActive })
    @Volatile private var socket: Closeable? = null
    private val usbManager = this.context.getSystemService(UsbManager::class.java)
    private val audioManager = this.context.getSystemService(AudioManager::class.java)
    private val audioDevices = object : AudioDeviceCallback() {
        private fun changed() {
            scope.launch {
                if (usbVolume.isInitialized()) usbVolume.value.topologyChanged()
                refreshRequested.trySend(Unit)
            }
        }
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = changed()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = changed()
    }
    private var usbWasPresent = false
    private var usbLostAt: Long? = null
    private var usbPermissionPending: String? = null
    private var retry: Job? = null
    private var retryDelay = INITIAL_RETRY_DELAY_MILLISECONDS
    private var lastUsbOpenFailure: String? = null
    private var reconnectWanted = true
    private val usbEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return
            if (!DongleUsb.candidate(device)) return
            val attached = intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED
            val detached = intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED
            if (attached) observeUsbPresence(true)
            if (detached && activeUsbPath != device.deviceName) return
            if (attached && activeUsbPath != null) return
            if (intent.action == USB_PERMISSION) {
                if (usbPermissionPending != device.deviceName) return
                usbPermissionPending = null
                if (!intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    mutableState.update { it.copy(busy = false, error = "Allow USB access to connect the dongle.") }
                    return
                }
            }
            val usbSelected = preferences.getString(ADDRESS_KEY, null)?.startsWith(USB_ADDRESS_PREFIX) == true
            if (usbSelected && reconnectWanted) {
                retry?.cancel()
                if (detached && activeUsbPath == device.deviceName) {
                    observeUsbPresence(false, ownedLoss = true)
                    retire()
                }
                retry = scope.launch {
                    delay(USB_EVENT_RECONNECT_DELAY_MILLISECONDS)
                    retry = null
                    reconnect()
                }
            }
        }
    }
    private var activeUsbPath: String? = null
    private var session: DongleSession? = null
    private var connection: Job? = null
    /** Report revision of the last ear-state cached report seen. */
    private var earReportRevision: Long? = null
    private val pendingWrites = DonglePendingWrites(SystemClock::elapsedRealtime)
    @Volatile private var finderWanted = false
    private var finderEpoch = 0L
    @Volatile private var pauseEpoch = 0L
    @Volatile private var callActive = false
    @Volatile private var controlsVisible = false
    private val controlObservers = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    private var volumeObservation: Pair<DongleUsbVolume.Target, DongleUsbVolume.Observation>? = null
    private var audioUpdated = 0L

    /** A record edit's owner, plus the hearing state it was started against. */
    private data class RecordOwner(val transport: DongleEditorOwner, val guards: List<Any?>)

    private fun recordGuards(): List<Any?> = mutableState.value.hearingModes.let {
        listOf(it.enrolled, it.clinicalReportsKnown, it.hearingAidEnabled, it.mediaAssistEnabled,
            it.customTransparencyAllowed, it.pending, it.uncertain)
    }

    private val liveSettings = ConcurrentHashMap<Int, LiveValueEditor<Long>>()
    private val transparencyEditor = lazy { recordEditor(DongleRecordKind.TRANSPARENCY) }
    private val hearingEditor = lazy { recordEditor(DongleRecordKind.HEARING) }
    private val accommodationsEditor = lazy {
        LiveAccommodationsEditor(scope, read = {
            val value = mutableState.value
            val report = value.accommodationsReport
            val captured = lease.currentOwner()
            val own = lease.liveOperation == (captured to DongleRecordKind.ACCOMMODATIONS)
            val owner = captured?.takeIf {
                lease.writable(it) && report != null && (value.accommodationsRequest.editable(report) || own)
            }
            val source = hearing.profileData?.let { AccommodationsAudiogram.parse(it, value.controlSession) }
            val received = value.hearingModes.receivedAccommodations(value.controlSession, source)
            val editable = owner != null && (!value.busy || own)
            LiveAccommodationsSource(owner, report, received, value.accommodationsRequest, editable, source)
        }, write = { captured, report, revision, choice, changeCurves ->
            val owner = captured as? DongleEditorOwner
            if (owner == null) {
                AccommodationsResult.NOT_SENT
            } else {
                val preset = choice.preset.takeIf { changeCurves }
                val audiogram = choice.audiogram && changeCurves
                accommodationsAwaited(owner, report, choice.phone, choice.media, preset, revision, audiogram).result
                    ?: AccommodationsResult.NOT_SENT
            }
        })
    }
    private val usbVolume = lazy {
        DongleUsbVolume(context, scope, bridge, ::volumeTarget, canStart = {
            val value = mutableState.value
            val audioBusyFlags = StatusFlag.STREAMING or StatusFlag.USB_PLAYBACK or StatusFlag.MICROPHONE_ACTIVE
            val profileBusyFlags = AudioProfileFlag.PROFILE_PENDING or AudioProfileFlag.STREAMING or AudioProfileFlag.MICROPHONE
            lease.protectedWorkIdle() && !value.busy && lease.liveOperation == null &&
                value.status?.flags?.and(audioBusyFlags) == 0 &&
                !callActive && value.audio?.flags?.and(profileBusyFlags) == 0 &&
                runCatching { !audioManager.isMusicActive && audioManager.activeRecordingConfigurations.isEmpty() }
                    .getOrDefault(false)
        }, read = { selected ->
            io.withLock {
                check(volumeTarget() == selected)
                val owner = selected.owner as DongleEditorOwner
                owner.client.withTarget(checkNotNull(mutableState.value.status)) {
                    val at = SystemClock.elapsedRealtime()
                    cacheVolumeObservation(selected, DongleUsbVolume.Observation(owner.client.readPhysicalVolume(), at))
                }
            }
        }, action = { selected, action, usbGeneration, token, level, guard ->
            io.withLock {
                check(volumeTarget() == selected)
                val owner = selected.owner as DongleEditorOwner
                owner.client.withTarget(checkNotNull(mutableState.value.status)) {
                    val at = SystemClock.elapsedRealtime()
                    val value = owner.client.volumeAuthority(action, usbGeneration, token, level,
                        beforePhysicalCommit = { physical ->
                            check(volumeTarget() == selected)
                            guard(physical)
                        })
                    cacheVolumeObservation(selected, DongleUsbVolume.Observation(value, at))
                }
            }
        }, changed = { value ->
            mutableState.update {
                val error = when {
                    value.cleanupFailed -> context.getString(R.string.dongle_volume_restore_failed)
                    value.failed -> context.getString(R.string.dongle_volume_failed)
                    value.enabled && it.error in volumeErrors() -> null
                    else -> it.error
                }
                it.copy(usbVolumeAvailable = value.available, usbVolumeEnabled = value.enabled,
                    usbVolumePending = value.pending, error = error)
            }
        }, requested = { serial -> preferences.getBoolean(volumeRequestKey(serial), false) })
    }

    private fun volumeErrors() = listOf(context.getString(R.string.dongle_volume_failed),
        context.getString(R.string.dongle_volume_restore_failed))

    private fun selectedVolumeSerial() = preferences.getString(ADDRESS_KEY, null)
        ?.takeIf { it.startsWith(USB_SERIAL_PREFIX) }
        ?.removePrefix(USB_SERIAL_PREFIX)
        ?.takeIf { it.matches(volumeSerialPattern) }

    private fun volumeRequestKey(serial: String) = "hardware_volume:$serial"

    private fun volumeRequested() =
        selectedVolumeSerial()?.let { preferences.getBoolean(volumeRequestKey(it), false) } == true

    private fun volumeTarget(): DongleUsbVolume.Target? {
        val value = mutableState.value
        val status = value.status ?: return null
        val volumeAuthority = status.capabilities and Capability.USB_HOST_VOLUME_AUTHORITY != 0
        if (!value.usb || !value.connected || !value.usbAudioAvailable || !volumeAuthority ||
            status.microphoneActive || callActive) return null
        val owner = lease.currentOwner() ?: return null
        val serial = preferences.getString(ADDRESS_KEY, null)
            ?.takeIf { it.startsWith(USB_SERIAL_PREFIX) }
            ?.removePrefix(USB_SERIAL_PREFIX)
            ?: return null
        return DongleUsbVolume.Target(owner, serial, value.controlSession, status.generation, status.capabilities)
    }

    private fun cacheVolumeObservation(selected: DongleUsbVolume.Target, observation: DongleUsbVolume.Observation) =
        observation.also {
            synchronized(lifecycle) {
                if (volumeTarget() == selected) volumeObservation = selected to observation
            }
        }

    internal fun dumpVolume(writer: PrintWriter) = synchronized(lifecycle) {
        val value = mutableState.value
        val cached = volumeObservation
        val age = cached?.let { SystemClock.elapsedRealtime() - it.second.observedAt }
        val current = volumeTarget()?.let { it == cached?.first } == true && age != null && age >= 0
        val error = when (value.error) {
            null -> "none"
            context.getString(R.string.dongle_volume_failed) -> "volume_stopped"
            context.getString(R.string.dongle_volume_restore_failed) -> "volume_restore_failed"
            else -> "present"
        }
        writer.println("dongle_volume cached_only=true connected=${value.connected} busy=${value.busy} " +
            "requested=${value.usbVolumeRequested} enabled=${value.usbVolumeEnabled} pending=${value.usbVolumePending} error=$error")
        writer.println("control_session=${value.controlSession} status_generation=${value.status?.generation ?: -1} " +
            "fresh_scope=$current age_ms=${age ?: -1}")
        val physical = cached?.second?.value?.takeIf { current }
        if (physical == null) {
            writer.println("physical=unavailable")
        } else {
            writer.println("physical_gain=${physical.confirmed ?: "unknown"} revision=${physical.observationRevision} " +
                "usb_generation=${physical.usbGeneration} " +
                "pending_at_snapshot=${physical.pending} inflight_at_snapshot=${physical.inflight} " +
                "authority_at_snapshot=${physical.authorityActive} " +
                "token_present=${physical.ownToken != null} remaining_ttl_at_snapshot_ms=${physical.remainingMs} " +
                "usb_scope_ready_at_snapshot=${physical.usbScopeReady}")
        }
    }

    fun usbHardwareVolume(enabled: Boolean) {
        val serial = selectedVolumeSerial() ?: return
        val edit = preferences.edit()
        if (enabled) edit.putBoolean(volumeRequestKey(serial), true) else edit.remove(volumeRequestKey(serial))
        if (!edit.commit()) return
        mutableState.update { it.copy(usbVolumeRequested = enabled) }
        if (enabled) {
            mutableState.update { if (it.error in volumeErrors()) it.copy(error = null) else it }
            usbVolume.value.enable(true)
        } else if (usbVolume.isInitialized()) {
            usbVolume.value.enable(false)
        }
        refreshRequested.trySend(Unit)
    }

    fun inhibitsAutomaticNative(): Boolean {
        if (observeUsbPresence(usbManager?.let(DongleUsb::devices).orEmpty().isNotEmpty())) return true
        val value = mutableState.value
        if (!value.selected || value.usb) return false
        if (value.connected || value.busy) return true
        val address = preferences.getString(ADDRESS_KEY, null) ?: return false
        return runCatching {
            context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO) &&
                    it.address.equals(address, ignoreCase = true)
            }
        }.getOrDefault(false)
    }

    @Synchronized private fun observeUsbPresence(present: Boolean, ownedLoss: Boolean = false): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (present) usbLostAt = null
        else if (usbWasPresent || ownedLoss) usbLostAt = now
        usbWasPresent = present
        // PID changes briefly remove USB. Repeated absence cannot prolong a real unplug.
        return present || usbLostAt?.let { now - it < USB_ABSENCE_GRACE_MILLISECONDS } == true
    }

    private val audioEditor = lazy {
        LiveValueEditor(scope, {
            val owner = lease.ownerAvailableForEdits()
            val intent = mutableState.value.audio?.let { DongleAudioIntent(it.requestedCodec, it.rateMode, it.requestedBitrate) }
            LiveValueSource(owner, intent, lease.writable(owner))
        }, { value: DongleAudioIntent ->
            value.codec in Codec.UNKNOWN..Codec.AAC_ELD &&
                value.rateMode in 0..DongleControlProtocol.AudioProfile.RATE_MODE_VARIABLE &&
                value.bitrate in 0L..MAXIMUM_REQUESTED_BITRATE
        }, write = { captured, value ->
            val owner = captured as? DongleEditorOwner
            if (owner == null) false else try {
                lease.ownedCommand(owner, bindTarget = false) { client ->
                    val expected = checkNotNull(mutableState.value.status)
                    val profile = client.requestAudio(value.codec, value.rateMode, value.bitrate, expected)
                    val status = checkNotNull(client.status)
                    lease.publish(client) {
                        if (mutableState.value.status?.sameConnection(status) != true) clearReports()
                        mutableState.update { it.copy(status = status) }
                        recordAudio(profile)
                    }
                }
                true
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                false
            } catch (_: Exception) {
                reportLiveFailure(owner)
                false
            }
        })
    }

    private val gameEditor = lazy {
        LiveValueEditor(scope, {
            val owner = lease.ownerAvailableForEdits()
            LiveValueSource(owner, mutableState.value.audio?.gameRequested, lease.writable(owner))
        }, ::gameModeAllowed, write = { captured, value ->
            val owner = captured as? DongleEditorOwner
            if (owner == null || !gameModeAllowed(value)) false else try {
                lease.ownedCommand(owner) { client ->
                    val request = byteArrayOf(Feature.SET_GAME_MODE.toByte(), if (value) 1 else 0)
                    client.transact(Rpc.Operation.TYPED_FEATURE_REQUEST, request) {
                        check(lease.writable(owner) && gameModeAllowed(value)) { "Game Mode is unavailable" }
                    }
                    val profile = DongleProtocol.audio(client.transact(Rpc.Operation.TYPED_FEATURE_REQUEST, readAudioProfileRequest))
                    lease.publish(client) { recordAudio(profile) }
                }
                true
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                false
            } catch (_: Exception) {
                reportLiveFailure(owner)
                false
            }
        })
    }

    private fun gameModeAllowed(enabled: Boolean): Boolean {
        val supported = AudioProfileFlag.GAME_MODE_SUPPORTED
        return !enabled || mutableState.value.audio?.flags?.and(supported) == supported
    }

    init {
        audioManager.registerAudioDeviceCallback(audioDevices, Handler(Looper.getMainLooper()))
        val usbFilter = IntentFilter().apply {
            addAction(USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        this.context.registerReceiver(usbEvents, usbFilter, Context.RECEIVER_NOT_EXPORTED)
        scope.launch {
            state.collect {
                liveSettings.values.forEach { it.observe() }
                if (transparencyEditor.isInitialized()) transparencyEditor.value.observe()
                if (hearingEditor.isInitialized()) hearingEditor.value.observe()
                if (audioEditor.isInitialized()) audioEditor.value.observe()
                if (gameEditor.isInitialized()) gameEditor.value.observe()
                if (accommodationsEditor.isInitialized()) accommodationsEditor.value.observe()
            }
        }
    }

    /** Keeps an active USB volume authority alive between the RPCs of a settings workflow. */
    private fun volumeCheckpoint(client: DongleSession): suspend () -> Long? = {
        if (!usbVolume.isInitialized()) {
            null
        } else {
            usbVolume.value.keepAliveForSettings { selected, usbGeneration, token ->
                val owner = selected.owner as? DongleEditorOwner
                check(owner != null && owner.client === client && lease.owns(owner) && volumeTarget() == selected)
                val observedAt = SystemClock.elapsedRealtime()
                val physical = client.volumeAuthority(VolumeAuthority.Action.RENEW, usbGeneration, token) {
                    check(volumeTarget() == selected)
                }
                cacheVolumeObservation(selected, DongleUsbVolume.Observation(physical, observedAt))
            }?.let { it - SystemClock.elapsedRealtime() }
        }
    }

    fun liveSettingEditor(id: Int): LiveValueEditor<Long> = liveSettings.getOrPut(id) {
        fun source(): LiveValueSource<Long> {
            val owner = lease.ownerAvailableForEdits()
            val current = mutableState.value
            val reported = current.settings[id]?.value?.let { currentDongleSetting(id, it) }
            val value = current.requested[id] ?: reported ?: current.savedChoices[id]
            return LiveValueSource(owner, value, lease.writable(owner) && current.canEditSetting(id))
        }
        LiveValueEditor(scope, ::source, { validLiveSetting(id, it) }, write = { captured, value ->
            val owner = captured as? DongleEditorOwner ?: return@LiveValueEditor false
            try {
                lease.ownedCommand(owner) { client -> setSettingNow(client, id, value) }
                true
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                false
            } catch (_: Exception) {
                reportLiveFailure(owner)
                false
            }
        })
    }

    private fun reportLiveFailure(owner: DongleEditorOwner) {
        runCatching {
            lease.publish(owner.client) { mutableState.update { it.copy(error = "The request was not confirmed.") } }
        }
    }

    private suspend fun setSettingNow(client: DongleSession, id: Int, value: Long) {
        val before = mutableState.value
        check(before.canWriteSetting(id, value))
        val owner = checkNotNull(lease.currentOwner())
        val baseline = before.settings[id]
        val peer = savedChoiceStore.peer(before.status)
        client.setting(id, value) {
            check(lease.writable(owner) && mutableState.value.canWriteSetting(id, value) &&
                savedChoiceStore.peer(mutableState.value.status) == peer)
        }
        lease.publish(client) {
            check(peer != null && savedChoiceStore.peer(mutableState.value.status) == peer)
            val persist = savedChoiceStore.persists(id, before.metadata?.model)
            val stored = !persist || savedChoiceStore.save(peer, id, value)
            val saved = if (persist && stored) mutableState.value.savedChoices + (id to value) else mutableState.value.savedChoices
            pendingWrites.recordSent(id, value, baseline, stored)
            mutableState.update {
                val error = if (!stored) "The setting was sent, but the choice could not be saved." else it.error
                it.copy(savedChoices = saved, requested = pendingWrites.requestedValues(), error = error)
            }
        }
    }

    private fun recordEditor(kind: DongleRecordKind): LiveRecordEditor {
        fun source(): LiveRecordSource {
            val owner = lease.ownerAvailableForEdits()
            val value = mutableState.value
            val modes = value.hearingModes
            val hearingRecord = kind == DongleRecordKind.HEARING
            val data = if (hearingRecord) hearing.profileData ?: byteArrayOf() else value.transparencyData
            val hearingReady = modes.enrolled == true && modes.clinicalReportsKnown
            val ready = lease.writable(owner, kind) && hearing.supported() && (!hearingRecord || hearingReady)
            val hearingSettled = hearingReady && modes.pending == null && modes.uncertain == null
            val recordOwner = owner?.takeIf { !hearingRecord || hearingSettled }?.let { RecordOwner(it, recordGuards()) }
            return LiveRecordSource(recordOwner, data, ready)
        }
        fun valid(data: ByteArray) =
            if (kind == DongleRecordKind.HEARING) hearingAidSettingsWritable(data) else DongleHearingRecords.validTransparency(data)
        fun matches(actual: ByteArray, target: ByteArray) = actual.contentEquals(target) ||
            kind == DongleRecordKind.HEARING && valid(actual) && valid(target) &&
            DongleHearingRecords.hearingProfileMatches(actual, target)
        return LiveRecordEditor(scope, ::source, ::valid, ::matches, write = { captured, expected, requested ->
            val recordOwner = captured as? RecordOwner
            val owner = recordOwner?.transport
            if (owner == null || source().owner != captured) false else try {
                lease.ownedCommand(owner, kind) { client ->
                    check(source().owner == captured)
                    val stillOwned = { source().owner == captured }
                    if (kind == DongleRecordKind.TRANSPARENCY) hearing.updateTransparency(client, expected, requested, stillOwned)
                    else hearing.updateHearing(client, expected, requested, stillOwned)
                }
                true
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                false
            } catch (_: Exception) {
                reportLiveFailure(owner)
                false
            }
        })
    }

    fun liveTransparencyEditor(): LiveRecordEditor = transparencyEditor.value
    fun liveHearingEditor(): LiveRecordEditor = hearingEditor.value
    fun liveAudioEditor(): LiveValueEditor<DongleAudioIntent> = audioEditor.value
    fun liveGameModeEditor(): LiveValueEditor<Boolean> = gameEditor.value
    fun liveAccommodationsEditor(): LiveAccommodationsEditor = accommodationsEditor.value

    fun choices(): List<DongleChoice> {
        val devices = usbManager?.let(DongleUsb::devices).orEmpty().sortedBy(DongleUsb::key)
        val usb = devices.mapIndexed { index, device ->
            val name = device.productName ?: "AirPods Dongle"
            DongleChoice(DongleUsb.key(device), if (devices.size > 1) "$name ${index + 1}" else name, usb = true)
        }
        val bluetooth = runCatching {
            context.getSystemService(BluetoothManager::class.java).adapter.bondedDevices
                .filter { it.bondState == BluetoothDevice.BOND_BONDED }
                .map { DongleChoice(it.address, it.name?.takeIf(String::isNotBlank) ?: "Paired Bluetooth device") }
        }.getOrDefault(emptyList())
        return usb + bluetooth.sortedBy { it.name }
    }

    fun restore() {
        if (preferences.contains(ADDRESS_KEY)) reconnect()
    }

    fun select(address: String) {
        val available = choices().any { it.address.equals(address, true) }
        val saved = available && runCatching {
            lifecycle.publish(lifecycle.current()) { preferences.edit().putString(ADDRESS_KEY, address).commit() }
        }.getOrDefault(false)
        if (!available || !saved) {
            mutableState.update { it.copy(error = "Attach the USB dongle or pair it in Bluetooth settings, then choose it again.") }
            return
        }
        retryDelay = INITIAL_RETRY_DELAY_MILLISECONDS
        reconnectWanted = true
        mutableState.update { it.copy(selected = true) }
        reconnect()
    }

    fun reconnect() {
        retry?.cancel()
        retry = null
        val address = preferences.getString(ADDRESS_KEY, null) ?: return
        reconnectWanted = true
        val mine = retire()
        val usb = address.startsWith(USB_ADDRESS_PREFIX)
        val devices = usbManager?.let(DongleUsb::devices).orEmpty()
        observeUsbPresence(devices.isNotEmpty())
        val usbDevice = if (usb) DongleUsb.resolve(devices, address) else null
        mutableState.update {
            it.copy(selected = true, usb = usb, attached = usbDevice != null,
                usbAudioAvailable = usbDevice?.let(DongleUsb::audioAvailable) == true,
                usbPermissionRequired = usbDevice != null && !usbManager.hasPermission(usbDevice))
        }
        if (usb && usbDevice == null) {
            mutableState.update {
                it.copy(error = if (devices.size > 1) "Choose the USB dongle to reconnect its controls." else null)
            }
            return
        }
        if (usb && !usbManager.hasPermission(usbDevice)) {
            usbPermissionPending = usbDevice!!.deviceName
            val permissionIntent = PendingIntent.getBroadcast(context, 0, Intent(USB_PERMISSION).setPackage(context.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            usbManager.requestPermission(usbDevice, permissionIntent)
            return
        }
        val job = lifecycle.publish(mine) {
            mutableState.update { it.copy(selected = true, busy = true, error = null) }
            scope.launch(start = CoroutineStart.LAZY) { runConnection(mine, address, usb, usbDevice) }
                .also { connection = it }
        }
        job.start()
    }

    /** Opens the transport, then polls until the connection is retired or fails. */
    private suspend fun runConnection(mine: Long, address: String, usb: Boolean, usbDevice: UsbDevice?) {
        var ownTransport: Closeable? = null
        try {
            val exchange: suspend (Int, Int, ByteArray) -> ByteArray
            val name: String
            if (usb) {
                val device = checkNotNull(usbDevice)
                val serial = checkNotNull(DongleUsb.serial(device)) { "The USB dongle did not report a board identity" }
                val serialAddress = "$USB_SERIAL_PREFIX$serial"
                check(address.startsWith(USB_PATH_PREFIX) || address == serialAddress) { "Choose the attached USB dongle again" }
                val transport = DongleUsb.open(usbManager, device) { usbInterface ->
                    bridge.connect() // Reuses authorized root; USB attachment never asks for a root grant.
                    if (!bridge.hasRootPrivileges) {
                        withTimeoutOrNull(BRIDGE_CONNECT_TIMEOUT_MILLISECONDS) { bridge.state.first { it.connected } }
                    }
                    bridge.openDongleHid(device.deviceName, serial, usbInterface.id) {
                        ownsUsbSelection(mine, address, serial) &&
                            usbManager.hasPermission(device) &&
                            usbManager.deviceList[device.deviceName]?.let(DongleUsb::serial) == serial
                    }
                }
                ownTransport = transport
                lifecycle.publish(mine) {
                    check(preferences.getString(ADDRESS_KEY, null) == address) { "Selected dongle changed" }
                    check(preferences.edit().putString(ADDRESS_KEY, serialAddress).commit())
                    socket = transport
                    activeUsbPath = device.deviceName
                }
                exchange = { operation, report, data -> transport.exchange(operation, report, data) }
                name = device.productName ?: "AirPods Dongle"
            } else {
                val adapter = context.getSystemService(BluetoothManager::class.java).adapter
                check(adapter != null && adapter.isEnabled) { "Turn on Bluetooth to connect the dongle" }
                val device = adapter.getRemoteDevice(address)
                check(device.bondState == BluetoothDevice.BOND_BONDED) { "Pair the dongle in Bluetooth settings first" }
                val transport = device.createRfcommSocketToServiceRecord(UUID.fromString(DongleProtocol.UUID))
                ownTransport = transport
                lifecycle.publish(mine) { socket = transport }
                guarded(transport, RFCOMM_CONNECT_TIMEOUT_MILLISECONDS) { transport.connect() }
                exchange = { operation, report, data ->
                    DongleProtocol.exchange(transport.inputStream, transport.outputStream, operation, report, data)
                }
                name = device.name ?: "AirPods Dongle"
            }
            val transport = checkNotNull(ownTransport)
            val boundedExchange: suspend (Int, Int, ByteArray, Long) -> ByteArray = { operation, report, data, timeout ->
                lifecycle.publish(mine) { check(socket === transport) }
                guarded(transport, minOf(EXCHANGE_TIMEOUT_MILLISECONDS, timeout)) {
                    try {
                        exchange(operation, report, data)
                    } catch (busy: DongleBusyException) {
                        throw busy
                    } catch (rejected: DongleRequestException) {
                        throw rejected
                    } catch (error: Exception) {
                        runCatching { transport.close() }
                        throw error
                    }
                }
            }
            val client = DongleSession({ operation, report, data ->
                boundedExchange(operation, report, data, EXCHANGE_TIMEOUT_MILLISECONDS)
            }, now = SystemClock::elapsedRealtime, controlEpoch = mine, closeTransport = { transport.close() },
                resumeSequences = usb, boundedIo = boundedExchange)
            io.withLock {
                val status = client.refresh()
                lifecycle.publish(mine) {
                    session = client
                    retryDelay = INITIAL_RETRY_DELAY_MILLISECONDS
                    lastUsbOpenFailure = null
                    mutableState.update { it.copy(name = name, connected = true, busy = false, status = status) }
                }
            }
            while (currentCoroutineContext().isActive && lifecycle.current() == mine) {
                try {
                    io.withLock { refresh(client) }
                } catch (_: DongleBusyException) {
                    lease.publish(client) { mutableState.update { it.copy(error = CONTROL_BUSY) } }
                }
                withTimeoutOrNull(pollInterval()) { refreshRequested.receive() }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            runCatching {
                lifecycle.publish(mine) {
                    if (usb && error is DongleUsbControlsUnavailable) logUsbOpenFailure(error)
                    val message = if (error is DongleUsbControlsUnavailable) error.message
                        else "Could not reach the dongle. Keep it powered, then reconnect its controls."
                    mutableState.update { it.copy(connected = false, busy = false, error = message) }
                }
            }
        } finally {
            runCatching { ownTransport?.close() }
            runCatching {
                lifecycle.publish(mine) {
                    socket = null
                    session = null
                    activeUsbPath = null
                    finderWanted = false
                    finderEpoch++
                    clearReports()
                    mutableState.update { it.copy(connected = false, busy = false, status = null, finderOpen = false, nearby = null) }
                    if (reconnectWanted) {
                        val wait = retryDelay
                        retryDelay = (retryDelay * 2).coerceAtMost(MAXIMUM_RETRY_DELAY_MILLISECONDS)
                        retry = scope.launch {
                            delay(wait)
                            retry = null
                            reconnect()
                        }
                    }
                }
            }
        }
    }

    /** Logs a shared USB open failure once per distinct cause. */
    private fun logUsbOpenFailure(error: DongleUsbControlsUnavailable) {
        val cause = generateSequence<Throwable>(error) { it.cause }.last()
        val summary = usbOpenFailurePattern.find(cause.message.orEmpty())?.value
            ?: cause.javaClass.simpleName.take(MAXIMUM_LOGGED_CAUSE_LENGTH)
        if (lastUsbOpenFailure != summary) {
            lastUsbOpenFailure = summary
            Log.w("DongleUsb", "Shared control startup failed: $summary")
        }
    }

    internal fun ownsUsbSelection(ownerEpoch: Long, original: String, serial: String): Boolean =
        lifecycle.current() == ownerEpoch && preferences.getString(ADDRESS_KEY, null) in setOf(original, "$USB_SERIAL_PREFIX$serial")

    fun pairingCommand(opcode: Int) {
        require(opcode in listOf(Opcode.RECONNECT_AIRPODS, Opcode.PAIR_AIRPODS, Opcode.OPEN_PHONE_PAIRING))
        val mine = lifecycle.current()
        val started = lifecycle.publish(mine) {
            if (mutableState.value.busy) return@publish false
            mutableState.update { it.copy(busy = true, error = null) }
            true
        }
        if (!started) return
        scope.launch {
            try {
                io.withLock {
                    val client = session ?: return@withLock
                    if (client.controlEpoch != mine) return@withLock
                    client.command(opcode)
                    refresh(client)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                runCatching {
                    lifecycle.publish(mine) {
                        val message = when (error) {
                            is DongleBusyException -> CONTROL_BUSY
                            is DongleRequestException -> error.message
                            else -> "The dongle did not confirm the request."
                        }
                        mutableState.update { it.copy(error = message) }
                    }
                }
            } finally {
                runCatching { lifecycle.publish(mine) { mutableState.update { it.copy(busy = false) } } }
            }
        }
    }

    private suspend fun <T> guarded(transport: Closeable, timeoutMs: Long, block: suspend () -> T): T {
        val timer = scope.launch {
            delay(timeoutMs)
            runCatching { transport.close() }
        }
        return try {
            block()
        } finally {
            timer.cancel()
        }
    }

    private fun clearReports() {
        if (usbVolume.isInitialized()) usbVolume.value.retire()
        hearing.clear()
        earReportRevision = null
        pendingWrites.clear()
        automaticReads.reset()
        lease.forgetLiveOperation()
        cancelEar()
        audioUpdated = 0
        mutableState.update {
            it.copy(metadata = null, settings = emptyMap(), requested = emptyMap(), savedChoices = emptyMap(),
                unconfirmed = emptySet(), hearingModes = HearingModes.empty(), controlSession = hearing.nextControlSession(),
                accommodationsRequest = AccommodationsRequestState(), transparencyData = byteArrayOf(), transparencyResult = null,
                hearingProfile = null, hearingAidData = byteArrayOf(), hearingAidReadStatus = SettingsReadStatus.WAITING,
                transparencyReadStatus = SettingsReadStatus.WAITING, diagnostics = null, audio = null, performance = null)
        }
    }

    private fun pollInterval(): Long = lifecycle.publish(lifecycle.current()) {
        val watched = controlsVisible || mutableState.value.automaticPause || volumeRequested() || finderWanted ||
            pendingWrites.awaitingConfirmation()
        if (watched) FAST_POLL_MILLISECONDS else IDLE_POLL_MILLISECONDS
    }

    fun controlsVisible(visible: Boolean, owner: Any = this) {
        synchronized(lifecycle) {
            if (visible) controlObservers.add(owner) else controlObservers.remove(owner)
            controlsVisible = controlObservers.isNotEmpty()
        }
        refreshRequested.trySend(Unit)
    }

    private suspend fun refresh(client: DongleSession) {
        val status = client.refresh()
        lease.publish(client) {
            val before = mutableState.value.status
            if (before == null || before.generation != status.generation || before.address != status.address) clearReports()
            if (before != null && before.address != status.address) {
                finderWanted = false
                finderEpoch++
                mutableState.update { it.copy(finderOpen = false, nearby = null) }
            }
            mutableState.update {
                it.copy(status = status, usbVolumeRequested = volumeRequested(), savedChoices = savedChoiceStore.load(status),
                    unconfirmed = pendingWrites.unconfirmed())
            }
            if (status.microphoneActive) cancelEar()
            mutableState.update { if (it.error == CONTROL_BUSY) it.copy(error = null) else it }
        }
        val typedFeatures = status.capabilities and Capability.TYPED_FEATURES != 0
        val finderOwner = lease.publish(client) { finderEpoch.takeIf { finderWanted && mutableState.value.finderOpen } }
        if (typedFeatures && finderOwner != null) {
            try {
                val nearby = DongleProtocol.nearby(client.transact(Rpc.Operation.TYPED_FEATURE_REQUEST, readNearbyFinderRequest))
                lease.publish(client) {
                    if (finderWanted && finderEpoch == finderOwner) mutableState.update { it.copy(nearby = nearby) }
                }
            } catch (_: DongleBusyException) {
                lease.publish(client) { mutableState.update { it.copy(error = CONTROL_BUSY) } }
            }
        }
        val audioWatched = controlsVisible || mutableState.value.automaticPause || volumeRequested()
        val audioStale = SystemClock.elapsedRealtime() - audioUpdated >= AUDIO_REFRESH_MILLISECONDS
        if (typedFeatures && audioWatched && audioStale) readAudio(client)
        if (!status.controlsReady) {
            lease.publish(client) { cancelEar() }
            return
        }
        val detailsWanted = lease.publish(client) {
            controlsVisible || automaticReads.wanted() || pendingWrites.awaitingConfirmation()
        }
        if (detailsWanted) refreshDetails(client, status)
        val volumeAuthority = status.capabilities and Capability.USB_HOST_VOLUME_AUTHORITY != 0
        if (mutableState.value.usb && volumeAuthority && (controlsVisible || volumeRequested())) {
            if (volumeRequested()) usbVolume.value.reconcile() else usbVolume.value.probe()
        }
        if (!mutableState.value.automaticPause || mutableState.value.usb) return
        followEars(client, status)
    }

    /** Reads settings, metadata and hearing reports, then the automatic health reads once per owner. */
    private suspend fun refreshDetails(client: DongleSession, status: DongleProtocol.Status) {
        val settings = client.settings()
        lease.publish(client) {
            pendingWrites.dropAnswered(settings)
            mutableState.update {
                it.copy(settings = settings, requested = pendingWrites.requestedValues(), unconfirmed = pendingWrites.unconfirmed())
            }
        }
        if (mutableState.value.metadata == null) {
            client.cached(CachedSlot.MODEL_METADATA)?.let { cached ->
                runCatching { InformationPacket.parse(cached.packet).metadata }.getOrNull()?.let { metadata ->
                    lease.publish(client) { mutableState.update { it.copy(metadata = metadata) } }
                }
            }
        }
        hearing.refresh(client)
        automaticReads.afterSettingsRead(client, status)
    }

    /** Pauses or resumes owned media when the ear-state report says the AirPods left or returned to the ears. */
    private suspend fun followEars(client: DongleSession, status: DongleProtocol.Status) {
        val ear = client.cached(CachedSlot.EAR_STATE)
        val (action, pauseOwner) = lease.publish(client) {
            val newEar = ear == null || earReportRevision != ear.revision
            earReportRevision = ear?.revision
            if (!newEar && mutableState.value.automaticPause && !status.microphoneActive) return@publish null
            val inEars = ear?.takeIf { it.ageMs <= EAR_REPORT_MAXIMUM_AGE_MILLISECONDS }?.let { DongleProtocol.ears(it.packet) }
            val enabled = mutableState.value.automaticPause && !status.microphoneActive &&
                !callActive && mutableState.value.audio?.input != InputOwnership.USB
            earClaim.observe(status.generation, inEars, enabled) to pauseEpoch
        } ?: return
        when (action) {
            DongleEarClaim.Action.PAUSE -> {
                bridge.connect()
                val connected = withTimeoutOrNull(BRIDGE_PAUSE_TIMEOUT_MILLISECONDS) { bridge.state.first { it.connected } }
                if (connected != null) {
                    if (!mediaOwnershipCurrent(client, status, pauseOwner)) return
                    val generation = status.generation
                    val address = preferences.getString(ADDRESS_KEY, null) ?: return
                    val acquired = bridge.pauseOwnedMedia(address) {
                        mutableState.value.connected && mutableState.value.automaticPause && !callActive &&
                            mutableState.value.audio?.input != InputOwnership.USB && pauseEpoch == pauseOwner &&
                            lifecycle.current() == client.controlEpoch && mutableState.value.status?.generation == generation
                    }
                    lease.publish(client) { if (pauseEpoch == pauseOwner) earClaim.acquired(acquired) }
                }
            }
            DongleEarClaim.Action.RESUME -> {
                if (mediaOwnershipCurrent(client, status, pauseOwner)) bridge.finishOwnedMedia(true)
            }
            DongleEarClaim.Action.CANCEL -> lease.publish(client) { bridge.cancelOwnedMedia() }
            DongleEarClaim.Action.NONE -> Unit
        }
    }

    private fun recordAudio(profile: DongleProtocol.AudioProfile) {
        audioUpdated = SystemClock.elapsedRealtime()
        mutableState.update { it.copy(audio = profile) }
        if (profile.input == InputOwnership.USB) cancelEar()
    }

    private suspend fun readAudio(client: DongleSession): DongleProtocol.AudioProfile {
        val profile = DongleProtocol.audio(client.transact(Rpc.Operation.TYPED_FEATURE_REQUEST, readAudioProfileRequest))
        lease.publish(client) { recordAudio(profile) }
        return profile
    }

    private suspend fun mediaOwnershipCurrent(client: DongleSession, target: DongleProtocol.Status, owner: Long): Boolean {
        try {
            if (target.capabilities and Capability.TYPED_FEATURES != 0) client.withTarget(target) { readAudio(client) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            lease.publish(client) { cancelEar() }
            return false
        }
        return lease.publish(client) {
            pauseEpoch == owner && !callActive && mutableState.value.automaticPause &&
                mutableState.value.audio?.input != InputOwnership.USB
        }
    }

    private fun cancelEar() {
        pauseEpoch++
        earClaim.cancel()
        bridge.cancelOwnedMedia()
    }

    fun mediaRouteChanged() = lifecycle.publish(lifecycle.current()) {
        if (!MediaController.isMediaRoute(preferences.getString(ADDRESS_KEY, null))) cancelEar()
    }

    fun callStateChanged(active: Boolean) = lifecycle.publish(lifecycle.current()) {
        callActive = active
        if (active) cancelEar()
    }

    fun automaticPause(enabled: Boolean) {
        if (!preferences.edit().putBoolean(AUTOMATIC_PAUSE_KEY, enabled).commit()) return
        lifecycle.publish(lifecycle.current()) {
            mutableState.update { it.copy(automaticPause = enabled) }
            cancelEar()
        }
        refreshRequested.trySend(Unit)
        if (enabled) bridge.requestPermission()
    }

    fun setSetting(id: Int, value: Long) {
        val editor = liveSettingEditor(id)
        editor.edit(editor.state.value.revision, value)
    }

    private fun command(bindTarget: Boolean = true, block: suspend (DongleSession) -> Unit) {
        val mine = lifecycle.current()
        val target = mutableState.value.status
        val started = lifecycle.publish(mine) {
            if (mutableState.value.busy) return@publish false
            mutableState.update { it.copy(busy = true, error = null) }
            true
        }
        if (!started) return
        scope.launch {
            try {
                io.withLock {
                    val client = checkNotNull(session) { "Dongle is disconnected" }
                    lifecycle.publish(mine) { check(session === client) }
                    if (bindTarget) {
                        client.withTarget(checkNotNull(target) { "Read the dongle settings first" }) {
                            lease.withVolumeCheckpoints(client) { block(client) }
                        }
                    } else {
                        lease.withVolumeCheckpoints(client) { block(client) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                runCatching {
                    lifecycle.publish(mine) { mutableState.update { it.copy(error = "The request was not confirmed.") } }
                }
            } finally {
                runCatching { lifecycle.publish(mine) { mutableState.update { it.copy(busy = false) } } }
            }
        }
    }

    fun readHearing() = command { client ->
        hearing.readProfile(client)
        hearing.refresh(client)
    }

    fun readAccommodations() = command { client -> hearing.readAccommodations(client) }

    fun accommodations(expected: AccommodationsReport, phone: Boolean?, media: Boolean?, preset: AccommodationsPreset?,
        editRevision: Long = 0, audiogram: Boolean = false) {
        scope.launch { accommodationsAwaited(expected, phone, media, preset, editRevision, audiogram) }
    }

    suspend fun accommodationsAwaited(expected: AccommodationsReport, phone: Boolean?, media: Boolean?,
        preset: AccommodationsPreset?, editRevision: Long = 0, audiogram: Boolean = false): AccommodationsRequestState {
        val owner = lease.currentOwner()
            ?: return AccommodationsRequestState(false, AccommodationsResult.NOT_SENT, expected.receptionRevision)
        return accommodationsAwaited(owner, expected, phone, media, preset, editRevision, audiogram)
    }

    private suspend fun accommodationsAwaited(owner: DongleEditorOwner, expected: AccommodationsReport, phone: Boolean?,
        media: Boolean?, preset: AccommodationsPreset?, editRevision: Long, audiogram: Boolean): AccommodationsRequestState {
        val notSent = AccommodationsRequestState(false, AccommodationsResult.NOT_SENT, expected.receptionRevision)
        try {
            lease.ownedCommand(owner, DongleRecordKind.ACCOMMODATIONS) { client ->
                hearing.writeAccommodations(client, expected, phone, media, preset, editRevision, audiogram)
            }
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            return notSent
        } catch (_: Exception) {
            reportLiveFailure(owner)
            val result = mutableState.value.accommodationsRequest
            val unconfirmedHere = result.baselineRevision == expected.receptionRevision &&
                result.result == AccommodationsResult.UNCONFIRMED
            if (!unconfirmedHere) return notSent
        }
        return mutableState.value.accommodationsRequest
    }

    fun updateTransparency(expected: ByteArray, requested: ByteArray) {
        val owner = lease.currentOwner() ?: return
        scope.launch {
            try {
                lease.ownedCommand(owner, DongleRecordKind.TRANSPARENCY) { client ->
                    hearing.updateTransparency(client, expected, requested)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                reportLiveFailure(owner)
            }
        }
    }

    fun hearingMode(mode: HearingMode, enabled: Boolean) = command { client -> hearing.changeHearingMode(client, mode, enabled) }

    fun updateHearing(transform: (HearingAidSettings) -> HearingAidSettings) {
        val editor = liveHearingEditor()
        editor.edit(editor.state.value.revision) { original ->
            val profile = parseHearingAidSettingsResponse(original) ?: return@edit null
            var requested: ByteArray? = null
            sendHearingAidSettings(original, transform(profile)) { _, data, _ -> requested = data }
            requested
        }
    }

    fun readDiagnostics() = command { client -> hearing.readDiagnostics(client) }

    fun audioProfile(codec: Int, rateMode: Int, bitrate: Long) {
        val editor = liveAudioEditor()
        editor.edit(editor.state.value.revision, DongleAudioIntent(codec, rateMode, bitrate))
    }

    fun gameMode(enabled: Boolean) {
        val editor = liveGameModeEditor()
        editor.edit(editor.state.value.revision, enabled)
    }

    fun readPerformance() = command { client ->
        val request = byteArrayOf(Feature.READ_PERFORMANCE.toByte())
        val snapshot = DongleProtocol.performance(client.transact(Rpc.Operation.TYPED_FEATURE_REQUEST, request))
        lease.publish(client) { mutableState.update { it.copy(performance = snapshot) } }
    }

    fun startFinder() = lifecycle.publish(lifecycle.current()) {
        if (mutableState.value.busy) return@publish
        val owner = ++finderEpoch
        finderWanted = true
        refreshRequested.trySend(Unit)
        command { client ->
            if (!lease.publish(client) { finderWanted && finderEpoch == owner }) return@command
            try {
                val available = DongleProtocol.nearby(client.transact(Rpc.Operation.TYPED_FEATURE_REQUEST, readNearbyFinderRequest))
                val proceed = lease.publish(client) {
                    if (!finderWanted || finderEpoch != owner) {
                        false
                    } else if (!available.identityAvailable) {
                        finderWanted = false
                        mutableState.update { it.copy(finderOpen = false, nearby = available) }
                        false
                    } else {
                        true
                    }
                }
                if (!proceed) return@command
                val startRequest = byteArrayOf(Feature.START_NEARBY_FINDER.toByte())
                val nearby = DongleProtocol.nearby(client.transact(Rpc.Operation.TYPED_FEATURE_REQUEST, startRequest))
                lease.publish(client) {
                    if (finderWanted && finderEpoch == owner) mutableState.update { it.copy(finderOpen = true, nearby = nearby) }
                }
            } catch (error: Exception) {
                lease.publish(client) {
                    if (finderEpoch == owner) {
                        finderWanted = false
                        finderEpoch++
                        mutableState.update { it.copy(finderOpen = false, nearby = null) }
                    }
                }
                throw error
            }
        }
    }

    fun stopFinder() = lifecycle.publish(lifecycle.current()) {
        if (!finderWanted) return@publish
        val owner = ++finderEpoch
        finderWanted = false
        mutableState.update { it.copy(finderOpen = false, nearby = null) }
        val mine = lifecycle.current()
        val client = session ?: return@publish
        val target = mutableState.value.status ?: return@publish
        scope.launch {
            io.withLock {
                // A cancelled in-flight Start finishes before this owner-bound Stop; a newer Start supersedes it.
                if (lifecycle.current() == mine && lease.publish(client) { finderEpoch == owner && !finderWanted }) {
                    runCatching {
                        val current = client.refresh()
                        check(current.address == target.address) { "Selected AirPods changed" }
                        val stopRequest = byteArrayOf(Feature.STOP_NEARBY_FINDER.toByte())
                        client.withTarget(current) { client.transact(Rpc.Operation.TYPED_FEATURE_REQUEST, stopRequest) }
                    }
                }
            }
        }
        Unit
    }

    fun disconnect() {
        reconnectWanted = false
        retry?.cancel()
        retire()
    }

    fun forget() {
        val key = selectedVolumeSerial()?.let(::volumeRequestKey)
        val edit = preferences.edit().remove(ADDRESS_KEY)
        if (key != null) edit.remove(key)
        edit.apply()
        disconnect()
        mutableState.update { DongleState(automaticPause = it.automaticPause) }
    }

    private fun retire(): Long = lifecycle.retire {
        if (usbVolume.isInitialized()) usbVolume.value.retire()
        connection?.cancel()
        connection = null
        runCatching { socket?.close() }
        socket = null
        session = null
        activeUsbPath = null
        finderWanted = false
        finderEpoch++
        mutableState.update {
            it.copy(connected = false, busy = false, status = null, finderOpen = false, nearby = null,
                usbVolumeRequested = volumeRequested())
        }
        clearReports()
    }

    override fun close() {
        synchronized(lifecycle) {
            controlObservers.clear()
            controlsVisible = false
        }
        audioManager.unregisterAudioDeviceCallback(audioDevices)
        disconnect()
        context.unregisterReceiver(usbEvents)
        bridge.close()
        scope.cancel()
    }

    companion object {
        private const val USB_PERMISSION = "me.kavishdevar.librepods.DONGLE_USB_PERMISSION"
        private const val CONTROL_BUSY = "Controls are in use by another client. Waiting for them to finish."

        private const val PREFERENCES_NAME = "dongle-control"
        private const val ADDRESS_KEY = "address"
        private const val AUTOMATIC_PAUSE_KEY = "automatic_pause"
        private const val USB_ADDRESS_PREFIX = "usb:"
        private const val USB_SERIAL_PREFIX = "usb:serial:"
        private const val USB_PATH_PREFIX = "usb:path:"
        private val volumeSerialPattern = Regex("[A-Za-z0-9_-]{1,64}")
        private val usbOpenFailurePattern = Regex("USB HID [a-z ]+ failed \\(errno [0-9]+")
        private const val MAXIMUM_LOGGED_CAUSE_LENGTH = 80

        private const val INITIAL_RETRY_DELAY_MILLISECONDS = 1_000L
        private const val MAXIMUM_RETRY_DELAY_MILLISECONDS = 15_000L
        private const val USB_EVENT_RECONNECT_DELAY_MILLISECONDS = 400L
        private const val USB_ABSENCE_GRACE_MILLISECONDS = 2_000L
        private const val BRIDGE_CONNECT_TIMEOUT_MILLISECONDS = 8_000L
        private const val RFCOMM_CONNECT_TIMEOUT_MILLISECONDS = 8_000L
        private const val EXCHANGE_TIMEOUT_MILLISECONDS = 3_000L
        private const val FAST_POLL_MILLISECONDS = 600L
        private const val IDLE_POLL_MILLISECONDS = 5_000L
        private const val AUDIO_REFRESH_MILLISECONDS = 2_000L
        private const val EAR_REPORT_MAXIMUM_AGE_MILLISECONDS = 2_500L
        private const val BRIDGE_PAUSE_TIMEOUT_MILLISECONDS = 2_000L
        /** The highest bitrate this client asks for, in bits per second. */
        private const val MAXIMUM_REQUESTED_BITRATE = 512_000L

        private val readAudioProfileRequest = byteArrayOf(Feature.READ_AUDIO_PROFILE.toByte())
        private val readNearbyFinderRequest = byteArrayOf(Feature.READ_NEARBY_FINDER.toByte())
    }
}
