/* Copyright (C) 2026 LibrePods contributors; GPL-3.0-or-later. */
package me.kavishdevar.librepods.audio

import android.bluetooth.BluetoothSocket
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import me.kavishdevar.librepods.utils.calculateLevel
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class MicrophonePhase { IDLE, STARTING, CAPTURING, STOPPING, RECOVERING, ERROR }

data class MicrophoneState(
    val phase: MicrophonePhase = MicrophonePhase.IDLE,
    val generation: Long = 0,
    val message: String = "",
    val format: PcmFormat? = null,
    val file: File? = null,
    val pcmDurationMs: Long = 0,
    val wallDurationMs: Long = 0,
    val level: Float = 0f,
    val receivedPackets: Long = 0,
    val decodedFrames: Long = 0,
)

/** Callbacks run on the microphone worker; the sink must use bounded, cancellable writes. */
interface PcmSink {
    fun onFormat(format: PcmFormat)
    fun onPcm(bytes: ByteArray, presentationTimeUs: Long)
    fun onStopped()
}

/** AACP acquisition, session-owned decoding and optional private diagnostic recording. */
class AirPodsMicrophone(
    private val aacp: AACPManager,
    private val outputDirectory: File,
    private val onRecoveryRequired: (Long) -> Unit,
) : AutoCloseable {
    private data class ReceivedPacket(val units: List<MicrophonePacket.AccessUnit>, val receivedNs: Long)

    private class Session(val generation: Long, val socket: BluetoothSocket) {
        val packets = ArrayBlockingQueue<ReceivedPacket>(32)
        val stop = AtomicBoolean(false)
        val connected = AtomicBoolean(true)
        val lastPacketMs = AtomicLong(SystemClock.elapsedRealtime())
        val receivedPackets = AtomicLong()
        @Volatile var failure: String? = null
        @Volatile var recovery: Boolean? = null
        @Volatile var accepting = true
        @Volatile var worker: Thread? = null
    }

    private val mutableState = MutableStateFlow(MicrophoneState())
    val state: StateFlow<MicrophoneState> = mutableState.asStateFlow()
    @Volatile private var session: Session? = null
    private var generation = 0L
    private var closed = false
    private val packetCallback: (BluetoothSocket, ByteArray) -> Unit = ::receive

    init { aacp.microphonePacketCallback = packetCallback }

    @Synchronized
    fun start(recordToFile: Boolean = true, sink: PcmSink? = null, expectedSocket: BluetoothSocket? = null,
        destination: File? = null): Boolean {
        if (closed || session != null || (!recordToFile && sink == null)) {
            Log.w("AirPodsMicrophone", "Start rejected: closed=$closed busy=${session != null} recording=$recordToFile sink=${sink != null}")
            return false
        }
        val socket = aacp.captureAudioSocket()
        if (socket == null || !socket.isConnected || (expectedSocket != null && socket !== expectedSocket)) {
            Log.w("AirPodsMicrophone", "Start rejected: protocol transport disconnected")
            mutableState.value = MicrophoneState(MicrophonePhase.ERROR, message = "Connect your AirPods first.")
            return false
        }
        val next = Session(++generation, socket)
        session = next
        mutableState.value = MicrophoneState(MicrophonePhase.STARTING, next.generation)
        Log.i("AirPodsMicrophone", "Session ${next.generation}: start accepted, recording=$recordToFile")
        next.worker = Thread({ capture(next, recordToFile, sink, destination) }, "AirPods microphone").apply { start() }
        return true
    }

    fun stop() {
        session?.let {
            it.accepting = false
            it.stop.set(true)
        }
    }

    fun onDisconnected() {
        session?.let {
            it.connected.set(false)
            fail(it, "AirPods disconnected. Reconnect before recording again.")
        }
    }

    fun onPermissionRevoked() {
        session?.let { fail(it, "Microphone access was removed or blocked. Check Android microphone privacy and Shizuku.") }
    }

    fun completeRecovery(generation: Long, success: Boolean) {
        val active = session?.takeIf { it.generation == generation }
        Log.i("AirPodsMicrophone", "Recovery result: session=$generation accepted=${active != null} success=$success")
        active?.recovery = success
    }

    fun transportFor(generation: Long): BluetoothSocket? = session?.takeIf { it.generation == generation }?.socket

    private fun receive(source: BluetoothSocket, packet: ByteArray) {
        val active = session ?: return
        if (active.socket !== source || !aacp.ownsAudioSocket(source)) return
        if (!MicrophonePacket.isAudio(packet)) return
        active.lastPacketMs.set(SystemClock.elapsedRealtime())
        if (!active.accepting || active.stop.get()) return
        try {
            val units = MicrophonePacket.parse(packet)
            active.receivedPackets.incrementAndGet()
            if (!active.packets.offer(ReceivedPacket(units, SystemClock.elapsedRealtimeNanos())))
                fail(active, "Microphone decoder could not keep up. Please retry.")
        } catch (_: IllegalArgumentException) {
            fail(active, "AirPods sent an incomplete microphone packet. Please retry.")
        }
    }

    private fun fail(active: Session, message: String) {
        active.failure = message
        active.accepting = false
        active.stop.set(true)
    }

    private fun capture(active: Session, record: Boolean, sink: PcmSink?, destination: File?) {
        val startedNs = SystemClock.elapsedRealtimeNanos()
        val started = startedNs / 1_000_000
        val clock = MicrophoneClockStats(startedNs)
        var lastPcm = started
        var bytesDecoded = 0L
        var writer: PcmWaveWriter? = null
        var decoder: EldDecoder? = null
        var attemptedStart = false
        var outputFormat: PcmFormat? = null
        var outputFile: File? = null
        var captureEnded = started
        try {
            val currentDecoder = EldDecoder(onFormat = format@{ format ->
                if (active.stop.get()) return@format
                if (outputFormat == null) {
                    outputFormat = format
                    Log.i("AirPodsMicrophone", "Session ${active.generation}: PCM16 format ${format.sampleRate} Hz, ${format.channels} channel")
                    if (record) {
                        check(outputDirectory.exists() || outputDirectory.mkdirs()) { "Cannot create recording directory" }
                        outputFile = destination ?: File.createTempFile("airpods-mic-", ".wav", outputDirectory)
                        if (destination == null) pruneMicrophoneRecordings(outputDirectory, checkNotNull(outputFile))
                        writer = PcmWaveWriter(checkNotNull(outputFile), format)
                    }
                    sink?.onFormat(format)
                }
            }, onPcm = pcm@{ bytes, timestamp ->
                if (!active.accepting || active.stop.get()) return@pcm
                writer?.write(bytes)
                sink?.onPcm(bytes, timestamp)
                if (bytesDecoded == 0L) Log.i("AirPodsMicrophone", "Session ${active.generation}: first decoded PCM")
                bytesDecoded += bytes.size
                lastPcm = SystemClock.elapsedRealtime()
                val format = checkNotNull(outputFormat)
                mutableState.value = MicrophoneState(MicrophonePhase.CAPTURING, active.generation,
                    format = format, file = outputFile, pcmDurationMs = format.durationMs(bytesDecoded),
                    wallDurationMs = lastPcm - started, level = calculateLevel(bytes),
                    receivedPackets = active.receivedPackets.get(), decodedFrames = bytesDecoded / format.frameSize)
            })
            decoder = currentDecoder
            check(active.connected.get() && !active.stop.get()) { "Microphone start cancelled" }
            attemptedStart = true
            check(aacp.requestMicrophoneStream(active.socket)) { "Could not start the AirPods microphone" }
            Log.i("AirPodsMicrophone", "Session ${active.generation}: microphone Start sent")
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (!active.stop.get()) {
                val packet = active.packets.poll(10, TimeUnit.MILLISECONDS)
                packet?.units?.forEach { unit ->
                    if (!active.stop.get()) currentDecoder.decode(unit.bytes) { active.stop.get() }
                }
                currentDecoder.drain()
                if (packet != null && !active.stop.get()) {
                    clock.packet(packet.units.map { it.timestamp }, packet.receivedNs, currentDecoder.decodedFrames)
                }
                val now = SystemClock.elapsedRealtime()
                check(if (bytesDecoded == 0L) now < deadline else now - lastPcm < 5_000) {
                    "No decoded microphone audio. Please retry."
                }
                check(bytesDecoded == 0L || now - active.lastPacketMs.get() < 2_000) {
                    "Microphone stream was interrupted. Please retry."
                }
                if (record && now - started >= 60_000) stop()
            }
        } catch (e: Exception) {
            Log.w("AirPodsMicrophone", "Session ${active.generation}: capture ended (${e.javaClass.simpleName})")
            if (active.failure == null && !active.stop.get())
                active.failure = e.message ?: "Microphone capture failed"
        } finally {
            captureEnded = SystemClock.elapsedRealtime()
            clock.snapshot()?.let { stats ->
                Log.i("AirPodsMicrophone", "Session ${active.generation}: clock first_packet_ms=${stats.firstPacketMs} warmup_ms=${stats.warmupMs} steady_ms=${stats.steadyMs} aus=${stats.accessUnits} decoded_frames=${stats.decodedFrames} source_hz=${stats.sourceHz} physical_hz=${AirPodsMicrophoneClock.PHYSICAL_RATE} timestamp_ticks=${stats.timestampTicks} intervals=${stats.timestampIntervals} repeats=${stats.repeats} backwards=${stats.backwards}")
            }
            active.accepting = false
            active.stop.set(true)
            active.packets.clear()
            try { sink?.onStopped() } catch (_: Exception) { active.failure = "Microphone output did not close cleanly" }
            try { decoder?.close() } catch (_: Exception) { active.failure = "Microphone decoder did not close cleanly" }
            try { writer?.close() } catch (_: Exception) { active.failure = "Could not finish the microphone recording" }
            if (attemptedStart && active.connected.get()) {
                try { finishUplink(active) } catch (_: Exception) {
                    active.failure = "Microphone cleanup failed. Reconnect your AirPods."
                }
            }
            val previous = mutableState.value
            mutableState.value = previous.copy(
                phase = if (active.failure == null) MicrophonePhase.IDLE else MicrophonePhase.ERROR,
                message = active.failure ?: "Recording stopped. Confirm playback in both AirPods, then check the recording duration and pitch.",
                wallDurationMs = captureEnded - started,
                level = 0f,
            )
            Log.i("AirPodsMicrophone", "Session ${active.generation}: ${mutableState.value.phase}, PCM ${mutableState.value.pcmDurationMs} ms, elapsed ${mutableState.value.wallDurationMs} ms, error=${active.failure != null}")
            synchronized(this) { if (session === active) session = null }
            if (closed && aacp.microphonePacketCallback === packetCallback) aacp.microphonePacketCallback = null
        }
    }

    private fun finishUplink(active: Session) {
        mutableState.value = mutableState.value.copy(phase = MicrophonePhase.STOPPING)
        Log.i("AirPodsMicrophone", "Session ${active.generation}: stopping uplink")
        var quiet = false
        repeat(2) {
            if (quiet || !active.connected.get()) return@repeat
            if (!aacp.endMicrophoneStream(active.socket)) {
                active.failure = "Could not stop the AirPods microphone. Reconnect your AirPods."
                return@repeat
            }
            val deadline = SystemClock.elapsedRealtime() + 2_000
            while (active.connected.get() && SystemClock.elapsedRealtime() < deadline) {
                if (SystemClock.elapsedRealtime() - active.lastPacketMs.get() >= 500) {
                    quiet = true
                    break
                }
                Thread.sleep(25)
            }
        }
        if (!active.connected.get()) return
        if (!quiet) active.failure = "Microphone uplink did not stop. Reconnect your AirPods."
        mutableState.value = mutableState.value.copy(phase = MicrophonePhase.RECOVERING)
        Log.i("AirPodsMicrophone", "Session ${active.generation}: recovering playback, quiet=$quiet")
        try {
            onRecoveryRequired(active.generation)
            val deadline = SystemClock.elapsedRealtime() + 8_000
            while (active.connected.get() && active.recovery == null && SystemClock.elapsedRealtime() < deadline)
                Thread.sleep(25)
            if (active.connected.get() && active.recovery != true)
                active.failure = "Playback recovery could not be confirmed. Reconnect your AirPods."
        } catch (_: Exception) {
            active.failure = "Playback recovery failed. Reconnect your AirPods."
        }
    }

    override fun close() {
        synchronized(this) {
            closed = true
            stop()
            if (session == null && aacp.microphonePacketCallback === packetCallback) aacp.microphonePacketCallback = null
        }
    }
}
