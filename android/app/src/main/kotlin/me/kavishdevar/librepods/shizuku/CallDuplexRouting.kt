package me.kavishdevar.librepods.shizuku

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import android.telecom.TelecomManager
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Owns one selected pair's call relay and this process's temporary call-mode request. */
@SuppressLint("MissingPermission", "SoonBlockedPrivateApi")
internal class CallDuplexRouting(
    private val context: Context,
    private val manager: AudioManager,
    selectedAddress: String,
) {
    private val address = A2dpRecoveryGuard(selectedAddress).address
    private val adapter = ShellBluetooth.adapter(context)
    private val selected: BluetoothDevice
    private val telecom = TelecomManager::class.java.getDeclaredConstructor(Context::class.java)
        .apply { isAccessible = true }.newInstance(context)
    private val audioService = AudioManager::class.java.getDeclaredMethod("getService")
        .apply { isAccessible = true }.invoke(null)
    private val getMode = Class.forName("android.media.IAudioService").getMethod("getMode")
    private val gate = Any()
    private val proxyGate = Any()
    private val closed = AtomicBoolean()
    private val started = AtomicBoolean()
    private val closing = AtomicBoolean()
    private val cleanupComplete = AtomicBoolean()
    private var proxyClosed = false
    private var headset: BluetoothHeadset? = null
    private var modeRequested = false
    private var playback: AudioTrack? = null
    private var downlink: AudioRecord? = null
    private val routes = AudioTrack::class.java.getMethod("getRoutedDevices")

    init {
        for (permission in arrayOf("MODIFY_PHONE_STATE", "MODIFY_AUDIO_ROUTING", "CALL_AUDIO_INTERCEPTION",
            "BLUETOOTH_CONNECT", "READ_PHONE_STATE", "RECORD_AUDIO")) {
            check(context.checkPermission("android.permission.$permission", Process.myPid(), Process.myUid()) ==
                PackageManager.PERMISSION_GRANTED) { "Shizuku lacks $permission for two-way calling" }
        }
        check(adapter.isEnabled) { "Turn Bluetooth on before using the AirPods microphone" }
        selected = adapter.bondedDevices.singleOrNull { it.address == address }
            ?: error("Selected AirPods are no longer bonded")
        check(A2dpRecoveryGuard.isAirPodsCandidate(selected.bondState == BluetoothDevice.BOND_BONDED,
            selected.type == BluetoothDevice.DEVICE_TYPE_CLASSIC || selected.type == BluetoothDevice.DEVICE_TYPE_DUAL,
            selected.uuids?.map { it.uuid.toString() }.orEmpty())) {
            "Selected device does not advertise the Classic AirPods service"
        }
        try {
            openHeadsetProxy()
            checkCall(AudioManager.MODE_IN_CALL)
            selectedOutput()
        } catch (error: Throwable) {
            closeHeadsetProxy()
            throw error
        }
    }

    private fun rawMode() = getMode.invoke(audioService) as Int

    private fun openHeadsetProxy() {
        val ready = CountDownLatch(1)
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, service: BluetoothProfile) {
                synchronized(proxyGate) {
                    if (proxyClosed) adapter.closeProfileProxy(profile, service)
                    else if (service is BluetoothHeadset) headset = service
                }
                ready.countDown()
            }
            override fun onServiceDisconnected(profile: Int) {
                synchronized(proxyGate) { headset = null }
                ready.countDown()
            }
        }
        check(adapter.getProfileProxy(context, listener, BluetoothProfile.HEADSET) &&
            ready.await(1500, TimeUnit.MILLISECONDS)) { "Cannot verify the AirPods Phone calls connection" }
        synchronized(proxyGate) {
            check(headset != null) { "Cannot verify the AirPods Phone calls connection" }
        }
    }

    private fun closeHeadsetProxy() = synchronized(proxyGate) {
        proxyClosed = true
        headset?.let { adapter.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        headset = null
    }

    private fun selectedOutput() = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).singleOrNull {
        CallPcm.isSelectedOutput(it.type, it.address, address)
    } ?: error("The selected AirPods media output is unavailable")

    private fun checkCall(expectedMode: Int) {
        check(!closed.get()) { "Call route was stopped" }
        check(rawMode() == expectedMode && telecom.isInCall) {
            "Two-way calling requires an active cellular call"
        }
        synchronized(proxyGate) {
            check(!proxyClosed && headset != null) { "AirPods Phone calls state is unavailable" }
            check(headset!!.getConnectionState(selected) == BluetoothProfile.STATE_DISCONNECTED) {
                "Turn off Phone calls for these AirPods in Android Bluetooth settings, then retry. Keep Media audio on."
            }
        }
        check(!manager.isBluetoothScoOn) { "Select the phone speaker or earpiece before starting this call route" }
        check(!manager.isMicrophoneMute && !ShellMicrophonePrivacy.isBlocked(context)) {
            "Android microphone privacy or mute is enabled"
        }
        check(adapter.isEnabled && selected.bondState == BluetoothDevice.BOND_BONDED) {
            "The selected AirPods disconnected"
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun isSelectedRoute(track: AudioTrack): Boolean {
        val actual = routes.invoke(track) as List<AudioDeviceInfo>
        return actual.isNotEmpty() && actual.all { CallPcm.isSelectedOutput(it.type, it.address, address) }
    }

    private fun inputFormat() = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()

    /** Called only after the first AirPods PCM was successfully written to the call uplink. */
    fun start(onReady: () -> Unit, onFailure: (Throwable) -> Unit) {
        if (!started.compareAndSet(false, true)) return
        val opening = AtomicBoolean(true)
        Thread({
            SystemClock.sleep(4000)
            if (opening.get()) {
                // A hung Binder/native setup must not retain a global mode request after owner loss.
                Log.e("LibrePodsCall", "Call route setup timed out; releasing its process-owned audio resources")
                Process.killProcess(Process.myPid())
            }
        }, "LibrePods-call-setup-deadline").apply { isDaemon = true }.start()
        Thread({
            try {
                synchronized(gate) {
                    checkCall(AudioManager.MODE_IN_CALL)
                    val output = selectedOutput()
                    modeRequested = true // Also release after a partially successful setMode().
                    manager.mode = AudioManager.MODE_CALL_REDIRECT
                    val modeDeadline = SystemClock.elapsedRealtime() + 1000
                    while (!closed.get() && rawMode() != AudioManager.MODE_CALL_REDIRECT &&
                        SystemClock.elapsedRealtime() < modeDeadline) Thread.sleep(10)
                    checkCall(AudioManager.MODE_CALL_REDIRECT)
                    val minimum = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT)
                    check(minimum > 0) { "Stereo call playback is unavailable" }
                    val track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                        .setBufferSizeInBytes(maxOf(minimum, 48000 * 4 / 5))
                        .setTransferMode(AudioTrack.MODE_STREAM).build().also { playback = it }
                    check(track.state == AudioTrack.STATE_INITIALIZED && track.setPreferredDevice(output)) {
                        "The selected AirPods call output could not be opened"
                    }
                    check(track.setVolume(0f) == AudioTrack.SUCCESS) { "Call output gain was rejected" }
                    track.play()
                    check(track.playState == AudioTrack.PLAYSTATE_PLAYING) { "Call playback did not start" }
                    val silence = ByteArray(1920)
                    val routeDeadline = SystemClock.elapsedRealtime() + 1000
                    while (!closed.get() && !isSelectedRoute(track) && SystemClock.elapsedRealtime() < routeDeadline) {
                        check(track.write(silence, 0, silence.size, AudioTrack.WRITE_NON_BLOCKING) >= 0) {
                            "Call output could not establish a silent route"
                        }
                        Thread.sleep(10)
                    }
                    check(isSelectedRoute(track)) { "Call playback did not reach the selected AirPods" }
                    checkCall(AudioManager.MODE_CALL_REDIRECT)
                    val record = AudioManager::class.java.getMethod("getCallDownlinkExtractionAudioRecord", AudioFormat::class.java)
                        .invoke(manager, inputFormat()) as AudioRecord
                    downlink = record
                    check(record.state == AudioRecord.STATE_INITIALIZED) { "Call downlink was not initialized" }
                    record.startRecording()
                    check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Call downlink did not start" }
                    val inputDeadline = SystemClock.elapsedRealtime() + 1000
                    while (!closed.get() && record.routedDevice == null && SystemClock.elapsedRealtime() < inputDeadline)
                        Thread.sleep(10)
                    check(record.routedDevice?.type == AudioDeviceInfo.TYPE_TELEPHONY &&
                        record.audioSource == MediaRecorder.AudioSource.VOICE_DOWNLINK) {
                        "Call downlink is not a telephony input"
                    }
                    checkCall(AudioManager.MODE_CALL_REDIRECT)
                    check(isSelectedRoute(track)) { "Call output changed before relay started" }
                    check(track.setVolume(0.5f) == AudioTrack.SUCCESS) { "Call output gain was rejected" }
                }
                opening.set(false)
                if (!closed.get()) {
                    onReady()
                    pump(onFailure)
                }
            } catch (error: Throwable) {
                if (!closed.get()) onFailure(error)
            } finally { opening.set(false) }
        }, "LibrePods-call-downlink").start()
    }

    private fun pump(onFailure: (Throwable) -> Unit) {
        try {
            val track = checkNotNull(playback)
            val record = checkNotNull(downlink)
            val mono = ByteArray(4800)
            val stereo = ByteArray(9600)
            var pending = 0
            var offset = 0
            var lastRoute = 0L
            var lastGuard = 0L
            var lastRead = SystemClock.elapsedRealtime()
            var lastWrite = lastRead
            while (!closed.get()) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastRoute >= 10) {
                    check(rawMode() == AudioManager.MODE_CALL_REDIRECT && telecom.isInCall &&
                        !manager.isBluetoothScoOn && isSelectedRoute(track) &&
                        record.routedDevice?.type == AudioDeviceInfo.TYPE_TELEPHONY) {
                        "Call audio route changed; two-way audio was stopped"
                    }
                    lastRoute = now
                }
                if (now - lastGuard >= 200) {
                    checkCall(AudioManager.MODE_CALL_REDIRECT)
                    lastGuard = now
                }
                var progressed = false
                if (pending == 0) {
                    val count = record.read(mono, 0, mono.size, AudioRecord.READ_NON_BLOCKING)
                    check(count >= 0) { "Call downlink read failed" }
                    if (count > 0) {
                        pending = CallPcm.stereoFromMono(mono, count, stereo)
                        offset = 0
                        lastRead = now
                        progressed = true
                    }
                }
                if (pending > 0) {
                    val count = track.write(stereo, offset, pending, AudioTrack.WRITE_NON_BLOCKING)
                    check(count >= 0 && count % 4 == 0) { "Call playback write failed" }
                    pending -= count
                    offset += count
                    if (count > 0) { lastWrite = now; progressed = true }
                }
                check(now - lastRead < 3000 && now - lastWrite < 3000) { "Call audio stopped making progress" }
                if (!progressed) Thread.sleep(2)
            }
        } catch (error: Throwable) {
            if (!closed.get()) onFailure(error)
        }
    }

    /** Called before the associated uplink is released; mode restoration runs afterwards. */
    fun stopStreams() {
        closed.set(true)
        if (closing.compareAndSet(false, true)) {
            Thread({
                SystemClock.sleep(4000)
                if (!cleanupComplete.get()) {
                    Log.e("LibrePodsCall", "Call route cleanup timed out; releasing its process-owned audio resources")
                    Process.killProcess(Process.myPid())
                }
            }, "LibrePods-call-cleanup-deadline").apply { isDaemon = true }.start()
        }
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) { runCatching(action).exceptionOrNull()?.let { if (failure == null) failure = it } }
        synchronized(gate) {
            playback?.let { track ->
                runCatching { track.setVolume(0f) }
                runCatching { track.pause() }
                runCatching { track.flush() }
                attempt { track.release() }
            }
            playback = null
            downlink?.let { record -> runCatching { record.stop() }; attempt { record.release() } }
            downlink = null
            attempt { closeHeadsetProxy() }
        }
        failure?.let { throw it }
    }

    fun releaseMode() = synchronized(gate) {
        if (modeRequested) {
            manager.mode = AudioManager.MODE_NORMAL // Removes this PID's request; Telecom retains its own request.
            modeRequested = false
        }
        cleanupComplete.set(true)
    }
}
