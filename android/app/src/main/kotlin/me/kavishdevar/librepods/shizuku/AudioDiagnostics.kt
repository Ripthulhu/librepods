package me.kavishdevar.librepods.shizuku

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Process
import android.os.SystemClock
import me.kavishdevar.librepods.audio.AirPodsMicrophoneClock
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class AudioDiagnostics(
    private val context: Context,
    private val routing: AudioRouting,
    private val appUid: Int,
) {
    fun run(): String {
        val capabilities = JSONObject()
        val permissions = JSONObject()
        for (permission in arrayOf("MODIFY_AUDIO_ROUTING", "CAPTURE_AUDIO_OUTPUT", "CAPTURE_MEDIA_OUTPUT",
            "CAPTURE_VOICE_COMMUNICATION_OUTPUT", "CALL_AUDIO_INTERCEPTION", "RECORD_AUDIO", "BLUETOOTH_CONNECT",
            "OBSERVE_SENSOR_PRIVACY")) {
            permissions.put(permission, context.checkPermission("android.permission.$permission", Process.myPid(), Process.myUid()) ==
                PackageManager.PERMISSION_GRANTED)
        }
        val modules = JSONArray()
        for (name in arrayOf("com.android.bluetooth", "com.google.android.bluetooth", "com.android.btservices",
            "com.google.android.btservices", "com.android.bt", "com.google.android.bt")) {
            runCatching { context.packageManager.getPackageInfo(name, PackageManager.MATCH_APEX) }.getOrNull()?.let {
                modules.put(JSONObject().put("package", name).put("version", it.versionName).put("versionCode", it.longVersionCode))
            }
        }
        capabilities.put("microphonePrivacy", attempt {
            val blocked = ShellMicrophonePrivacy.isBlocked(context)
            result(if (blocked) "blocked" else "proven",
                "Read-only all-sensors, software and hardware microphone privacy checks succeeded. " +
                    "Microphone privacy is currently ${if (blocked) "enabled (access blocked)" else "disabled (access allowed)"}. " +
                    "No privacy setting was changed.")
                .put("blocked", blocked).put("checkedAtElapsedRealtimeMs", SystemClock.elapsedRealtime())
        })
        capabilities.put("playbackCapture", attempt { playbackCapture() })
        capabilities.put("microphonePolicy", attempt { microphonePolicy() })
        capabilities.put("callAudio", attempt {
            val interceptable = AudioManager::class.java.getMethod("isPstnCallAudioInterceptable")
                .invoke(routing.manager) as Boolean
            AudioManager::class.java.getMethod("getCallUplinkInjectionAudioTrack", AudioFormat::class.java)
            AudioManager::class.java.getMethod("getCallDownlinkExtractionAudioRecord", AudioFormat::class.java)
            result("untested", "PSTN interceptable=$interceptable; current audio mode=${routing.manager.mode}. " +
                "No call audio was recorded or injected. A deliberate call and remote-listener check are required.")
        })
        capabilities.put("avdtpPsm19", attempt { avdtpPsmProbe() })
        capabilities.put("appleAacEldEncoder", attempt { eldEncoder() })
        capabilities.put("customPlayback", result("untested",
            "Native Android AAC is retained. Encoder and PSM probes do not prove AVDTP negotiation, pacing or delay control."))
        capabilities.put("recipientVerification", result("untested",
            "Verify a known source in the selected recorder, with a remote listener for calls. Stream allocation alone is not end-to-end proof."))
        return JSONObject().put("schema", 1).put("fingerprint", Build.FINGERPRINT)
            .put("model", Build.MODEL).put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
            .put("securityPatch", Build.VERSION.SECURITY_PATCH).put("serviceUid", Process.myUid())
            .put("bluetoothModules", modules).put("shellPermissions", permissions)
            .put("capabilities", capabilities).toString(2)
    }

    fun microphonePolicy(): JSONObject {
        check(!ShellMicrophonePrivacy.isBlocked(context)) { "Android microphone privacy blocks microphone policy testing" }
        routing.injection(AirPodsMicrophoneClock.PHYSICAL_RATE, 1, appUid).use { route ->
            val track = checkNotNull(route.track)
            val mixRate = track.sampleRate
            val playbackRate = track.playbackRate
            check(mixRate == 48000 && playbackRate == AirPodsMicrophoneClock.PHYSICAL_RATE) {
                "Microphone source-rate conversion has unexpected rates: mix=$mixRate source=$playbackRate"
            }
            return result("untested", "A UID-scoped microphone policy configured for a $mixRate Hz mix and $playbackRate Hz source playback rate was created and released. " +
                "End-to-end injection needs a selected recorder and a user-started test.")
                .put("configuredMixSampleRate", mixRate).put("sourcePlaybackRate", playbackRate)
        }
    }

    private fun playbackCapture(): JSONObject {
        routing.playbackCopy().use { route ->
            val record = checkNotNull(route.record)
            val samples = ShortArray(4800)
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Playback copy could not start recording" }
            val end = SystemClock.elapsedRealtime() + 750
            var frames = 0
            var nonzero = 0
            while (SystemClock.elapsedRealtime() < end) {
                val read = record.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
                check(read >= 0) { "Playback copy read failed ($read)" }
                frames += read / 2
                for (i in 0 until read) if (samples[i] != 0.toShort()) nonzero++
                if (read == 0) Thread.sleep(10)
            }
            check(frames > 0) { "Playback copy produced no PCM" }
            return result(if (nonzero > 0) "proven" else "untested",
                "Playback-copy stream delivered $frames stereo PCM frames ($nonzero nonzero samples). " +
                    "Normal playback was kept rendered; no PCM was saved. Verify the known source and absence of duplication on this Pixel.")
        }
    }

    @Suppress("DEPRECATION")
    // This probe runs only with shell identity in the UserService or ADB app_process.
    @SuppressLint("SoonBlockedPrivateApi")
    private fun avdtpPsmProbe(): JSONObject {
        val adapter = ShellBluetooth.adapter(context)
        check(adapter.isEnabled) { "Bluetooth is off; diagnostics will not enable it" }
        val method = BluetoothAdapter::class.java.getDeclaredMethod("listenUsingInsecureL2capOn", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        // Never disconnect A2DP to reserve PSM 0x19. If native Android owns it, that is a real gate.
        val cancelled = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()
        val job = executor.submit<JSONObject> {
            val socket = method.invoke(adapter, 0x19) as BluetoothServerSocket
            socket.use {
                check(!cancelled.get()) { "AVDTP reservation exceeded the probe deadline" }
                result("proven", "PSM 0x19 was briefly reserved and closed while the native stack remained active. " +
                    "This does not establish an AVDTP media connection.")
            }
        }
        return try {
            job.get(3, TimeUnit.SECONDS)
        } finally {
            cancelled.set(true)
            job.cancel(true)
            executor.shutdownNow()
        }
    }

    private fun eldEncoder(): JSONObject {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD)
            setInteger(MediaFormat.KEY_BIT_RATE, 192000)
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val packets = ArrayList<ByteArray>()
        var configuration: ByteArray? = null
        var codecName = ""
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codecName = encoder.name
            encoder.start()
            val info = MediaCodec.BufferInfo()
            var queuedFrames = 0
            var inputEnded = false
            var outputEnded = false
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (!outputEnded && SystemClock.elapsedRealtime() < deadline) {
                if (!inputEnded) {
                    val index = encoder.dequeueInputBuffer(1000)
                    if (index >= 0) {
                        val buffer = checkNotNull(encoder.getInputBuffer(index))
                        buffer.clear()
                        val frames = minOf(512, buffer.remaining() / 4, 4096 - queuedFrames)
                        repeat(frames * 2) { buffer.putShort(0) }
                        val flags = if (frames == 0) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        encoder.queueInputBuffer(index, 0, frames * 4, queuedFrames * 1_000_000L / 48000, flags)
                        queuedFrames += frames
                        inputEnded = frames == 0
                    }
                }
                when (val index = encoder.dequeueOutputBuffer(info, 1000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        configuration = encoder.outputFormat.getByteBuffer("csd-0")?.let { bytes(it) }
                    }
                    else -> if (index >= 0) {
                        try {
                            if (info.size > 0) {
                                check(info.size <= 65536 && packets.size < 64) { "Unbounded encoder output" }
                                val buffer = checkNotNull(encoder.getOutputBuffer(index))
                                buffer.position(info.offset).limit(info.offset + info.size)
                                val packet = bytes(buffer)
                                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) configuration = packet
                                else packets.add(packet)
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally { encoder.releaseOutputBuffer(index, false) }
                    }
                }
            }
            check(outputEnded && packets.size >= 2) { "AAC-ELD encoder did not finish within five seconds" }
        } finally {
            runCatching { encoder.stop() }
            encoder.release()
        }
        val asc = checkNotNull(configuration) { "Encoder did not supply AAC AudioSpecificConfig" }
        val parsed = AudioBridgeRules.parseEldConfig(asc)
        val decoded = decodeFrames(asc, packets)
        AudioBridgeRules.requireAppleEld(parsed, decoded)
        return result("proven", "$codecName produced real 48 kHz stereo AAC-ELD with 480 samples per frame; " +
            "${decoded.size} decoded frames checked. ASC=${asc.joinToString("") { "%02x".format(it.toInt() and 255) }}")
    }

    private fun decodeFrames(asc: ByteArray, packets: List<ByteArray>): List<Int> {
        val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val decoded = ArrayList<Int>()
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 2)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(asc))
            decoder.configure(format, null, null, 0)
            decoder.start()
            var queued = 0
            var inputEnded = false
            var outputEnded = false
            var bytesPerFrame = 4
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (!outputEnded && SystemClock.elapsedRealtime() < deadline) {
                if (!inputEnded) {
                    val index = decoder.dequeueInputBuffer(1000)
                    if (index >= 0) {
                        val packet = packets.getOrNull(queued)
                        val buffer = checkNotNull(decoder.getInputBuffer(index))
                        buffer.clear()
                        if (packet != null) {
                            check(packet.size <= buffer.remaining()) { "AAC decoder input buffer is too small" }
                            buffer.put(packet)
                        }
                        decoder.queueInputBuffer(index, 0, packet?.size ?: 0, queued * 10_000L,
                            if (packet == null) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        inputEnded = packet == null
                        queued++
                    }
                }
                when (val index = decoder.dequeueOutputBuffer(info, 1000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val actual = decoder.outputFormat
                        check(actual.getInteger(MediaFormat.KEY_SAMPLE_RATE) == 48000 &&
                            actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 2) { "AAC decoder output format changed" }
                        val encoding = actual.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        check(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            "Unrecognized decoder PCM encoding $encoding"
                        }
                        bytesPerFrame = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 8 else 4
                    }
                    else -> if (index >= 0) {
                        try {
                            if (info.size > 0) {
                                check(decoded.size < 64 && info.size % bytesPerFrame == 0) { "Invalid decoded PCM size" }
                                decoded.add(info.size / bytesPerFrame)
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally { decoder.releaseOutputBuffer(index, false) }
                    }
                }
            }
            check(outputEnded) { "AAC-ELD decoder did not finish within five seconds" }
            return decoded
        } finally {
            runCatching { decoder.stop() }
            decoder.release()
        }
    }

    private fun bytes(buffer: ByteBuffer): ByteArray = ByteArray(buffer.remaining()).also { buffer.get(it) }
    private fun result(status: String, detail: String) = JSONObject().put("status", status).put("detail", detail)
    private fun attempt(action: () -> JSONObject): JSONObject = try { action() } catch (error: Throwable) {
        if (error is AudioRoutingCleanupException) throw error
        error.suppressed.filterIsInstance<AudioRoutingCleanupException>().firstOrNull()?.let { throw it }
        var cause = error
        while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
        result("blocked", "${cause.javaClass.simpleName}: ${cause.message.orEmpty()}".take(2048))
    }
}
