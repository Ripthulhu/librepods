package me.kavishdevar.librepods.shizuku

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioTrack
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import me.kavishdevar.librepods.BuildConfig
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** An app-owned, non-daemon shell service with scoped audio routes and selected-pair media recovery. */
class ShizukuAudioService(context: Context) : IAudioBridge.Stub() {
    private val originalContext = context
    private val expectedUid = context.packageManager.getApplicationInfo(BuildConfig.APPLICATION_ID, 0).uid
    private val shellContext by lazy { ShellAudioContext(context).also { it.installProcessAttribution() } }
    private val routing by lazy { AudioRouting(shellContext) }
    private val worker = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private var probing = false
    private val cleanupFailed = AtomicBoolean(false)
    @Volatile private var session: InjectionSession? = null
    private var playbackRecovery: A2dpPlaybackRecovery? = null

    private fun enforceCaller() {
        AudioBridgeRules.requireCaller(expectedUid, Binder.getCallingUid())
    }

    private fun requireMicrophonePermission() {
        check(originalContext.packageManager.checkPermission(Manifest.permission.RECORD_AUDIO,
            BuildConfig.APPLICATION_ID) == PackageManager.PERMISSION_GRANTED) {
            "LibrePods microphone permission was revoked"
        }
        check(!microphonePrivacyBlocked()) { "Android microphone privacy is enabled or unavailable" }
    }

    override fun isMicrophonePrivacyBlocked(): Boolean {
        enforceCaller()
        return microphonePrivacyBlocked()
    }

    private fun microphonePrivacyBlocked(): Boolean {
        val identity = Binder.clearCallingIdentity()
        return try {
            ShellMicrophonePrivacy.isBlocked(shellContext)
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun probe(callback: IAudioBridgeCallback) {
        enforceCaller()
        synchronized(lock) {
            check(!cleanupFailed.get() && !probing && session == null && playbackRecovery == null) { "Stop the audio session before running diagnostics" }
            probing = true
        }
        worker.execute {
            val report = try {
                AudioDiagnostics(shellContext, routing, expectedUid).run()
            } catch (error: Throwable) {
                if (error is AudioRoutingCleanupException) terminateAfterCleanupFailure()
                JSONObject().put("status", "blocked").put("detail", describe(error)).toString()
            } finally {
                synchronized(lock) { probing = false }
            }
            runCatching { callback.onReport(report) }
        }
    }

    override fun startMicrophoneInjection(
        sampleRate: Int,
        channels: Int,
        targetPackage: String,
        forCall: Boolean,
        selectedAddress: String,
        owner: IBinder,
        callback: IAudioBridgeCallback,
    ): ParcelFileDescriptor {
        enforceCaller()
        requireMicrophonePermission()
        if (forCall) {
            check(originalContext.packageManager.checkPermission("android.permission.BLUETOOTH_CONNECT",
                BuildConfig.APPLICATION_ID) == PackageManager.PERMISSION_GRANTED) {
                "LibrePods Bluetooth permission was revoked"
            }
        }
        AudioBridgeRules.validateFormat(sampleRate, channels)
        synchronized(lock) {
            check(!cleanupFailed.get() && !probing && session == null && playbackRecovery == null) { "An audio test or injection session is already running" }
            val targetUid = if (forCall) expectedUid else {
                require(targetPackage.length in 1..255) { "Choose a recording application" }
                originalContext.packageManager.getApplicationInfo(targetPackage, 0).uid.also {
                    AudioBridgeRules.requireTarget(it)
                }
            }
            val identity = Binder.clearCallingIdentity()
            try {
                val route = if (forCall) routing.callInjection(sampleRate, channels, selectedAddress)
                    else routing.injection(sampleRate, channels, targetUid)
                try {
                    val pipe = ParcelFileDescriptor.createReliablePipe()
                    val created = InjectionSession(owner, callback, pipe[0], route, channels,
                        if (forCall) "Two-way call" else targetPackage,
                        if (forCall) null else targetPackage, targetUid)
                    session = created
                    try {
                        owner.linkToDeath(created, 0)
                        created.start()
                        return pipe[1]
                    } catch (error: Throwable) {
                        pipe[1].close()
                        created.close()
                        throw error
                    }
                } catch (error: Throwable) {
                    closeRoute(route)
                    throw error
                }
            } catch (error: Throwable) {
                if (error is AudioRoutingCleanupException) terminateAfterCleanupFailure()
                throw error
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }
    }

    override fun stopSession(owner: IBinder) {
        enforceCaller()
        val active = synchronized(lock) { session?.takeIf { it.owner == owner } } ?: return
        active.close() // A delayed Stop from a retired owner cannot stop its replacement.
    }

    override fun restoreNativePlayback(address: String, owner: IPlaybackRecoveryOwner): Boolean {
        enforceCaller()
        check(originalContext.packageManager.checkPermission("android.permission.BLUETOOTH_CONNECT", BuildConfig.APPLICATION_ID) ==
            PackageManager.PERMISSION_GRANTED) { "LibrePods Bluetooth permission was revoked" }
        val recovery = synchronized(lock) {
            check(!cleanupFailed.get() && !probing && session == null && playbackRecovery == null) { "Another audio operation is active" }
            A2dpPlaybackRecovery(shellContext, address, owner).also { playbackRecovery = it }
        }
        val identity = Binder.clearCallingIdentity()
        try {
            return recovery.run()
        } finally {
            Binder.restoreCallingIdentity(identity)
            synchronized(lock) { if (playbackRecovery === recovery) playbackRecovery = null }
        }
    }

    override fun confirmPairing(address: String, owner: IPlaybackRecoveryOwner): Boolean {
        enforceCaller()
        if (cleanupFailed.get() || originalContext.packageManager.checkPermission(
                "android.permission.BLUETOOTH_CONNECT", BuildConfig.APPLICATION_ID) != PackageManager.PERMISSION_GRANTED)
            return false
        val identity = Binder.clearCallingIdentity()
        return try {
            ShellPairing.confirm(shellContext, address, owner)
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun destroy() {
        val caller = Binder.getCallingUid()
        check(caller == expectedUid || caller == 2000 || caller == 0) { "Invalid bridge owner" }
        cleanupFailed.set(true)
        synchronized(lock) { playbackRecovery?.cancel() }
        session?.close()
        worker.shutdownNow()
        kotlin.system.exitProcess(0)
    }

    private inner class InjectionSession(
        val owner: IBinder,
        private val callback: IAudioBridgeCallback,
        private val input: ParcelFileDescriptor,
        private val route: AudioRouting.Route,
        private val channels: Int,
        private val target: String,
        private val targetPackage: String?,
        private val targetUid: Int,
    ) : IBinder.DeathRecipient, Closeable {
        private val closed = AtomicBoolean(false)
        @Volatile private var failure: String? = null
        fun start() {
            Thread({ pump() }, "LibrePods-mic-injection").start()
        }

        @Synchronized private fun duplexReady() {
            if (!closed.get()) callback.onSessionState("active_unverified",
                "Call audio is redirected to the selected AirPods. Verify both directions, phone silence and ordinary media playback.")
        }

        @Synchronized private fun fail(error: Throwable) {
            if (closed.get()) return
            if (failure == null) failure = describe(error)
            close()
        }

        private fun pump() {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                val track = checkNotNull(route.track)
                track.play()
                check(track.playState == AudioTrack.PLAYSTATE_PLAYING) { "Injection track did not start" }
                callback.onSessionState(if (route.call == null) "active_unverified" else "starting",
                    if (route.call == null) "$target injection started. Confirm the receiving application hears the AirPods microphone."
                    else "Waiting for AirPods microphone audio before redirecting the cellular call.")
                val frameBytes = channels * 2
                val buffer = ByteArray(8192)
                val poll = StructPollfd().apply { fd = input.fileDescriptor; events = OsConstants.POLLIN.toShort() }
                var carry = 0
                var lastPermissionCheck = 0L
                var lastInput = SystemClock.elapsedRealtime()
                ParcelFileDescriptor.AutoCloseInputStream(input).use { source ->
                    while (!closed.get()) {
                        val now = SystemClock.elapsedRealtime()
                        check(now - lastInput < 5000) { "Microphone PCM stopped arriving" }
                        if (now - lastPermissionCheck > 1000) {
                            requireMicrophonePermission()
                            check(!routing.manager.isMicrophoneMute) { "Android microphone privacy/mute is enabled" }
                            if (targetPackage != null) {
                                check(originalContext.packageManager.getApplicationInfo(targetPackage, 0).uid == targetUid) {
                                    "The recording application's identity changed"
                                }
                            }
                            lastPermissionCheck = now
                        }
                        if (Os.poll(arrayOf(poll), 250) == 0) continue
                        if (closed.get()) break
                        val count = source.read(buffer, carry, buffer.size - carry)
                        if (count < 0) break
                        if (count == 0) continue
                        lastInput = SystemClock.elapsedRealtime()
                        val total = carry + count
                        val aligned = total - total % frameBytes
                        var written = 0
                        val deadline = SystemClock.elapsedRealtime() + 1500
                        while (written < aligned && !closed.get()) {
                            val bytes = track.write(buffer, written, aligned - written, AudioTrack.WRITE_NON_BLOCKING)
                            check(bytes >= 0) { "Injection write failed ($bytes)" }
                            check(SystemClock.elapsedRealtime() < deadline) { "Injection stalled" }
                            if (bytes == 0) Thread.sleep(2) else {
                                written += bytes
                                route.call?.start(::duplexReady, ::fail)
                            }
                        }
                        carry = total - aligned
                        if (carry > 0) buffer.copyInto(buffer, 0, aligned, total)
                    }
                    check(carry == 0 || closed.get()) { "Microphone PCM ended with a partial sample" }
                }
            } catch (error: Throwable) {
                if (!closed.get()) failure = describe(error)
            } finally {
                close()
            }
        }

        override fun binderDied() = close()

        @Synchronized override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { input.close() }
            val restored = closeRoute(route)
            runCatching { owner.unlinkToDeath(this, 0) }
            // Only the closing owner clears itself, after cleanup; a subsequent start sees the volatile null.
            if (session === this) session = null
            val cleanup = if (restored.isSuccess) "Injection resources released"
                else "Injection stopped, but policy removal failed: ${describe(restored.exceptionOrNull()!!)}"
            runCatching { callback.onSessionState(if (failure != null || restored.isFailure) "failed" else "stopped",
                listOfNotNull(failure, cleanup).joinToString(". ")) }
        }
    }

    private fun closeRoute(route: AudioRouting.Route): Result<Unit> = runCatching { route.close() }
        .onFailure { terminateAfterCleanupFailure() }

    private fun terminateAfterCleanupFailure() {
        if (!cleanupFailed.compareAndSet(false, true)) return
        // Process death makes AudioService discard this helper's policy Binder if explicit removal failed.
        Thread({ SystemClock.sleep(100); Process.killProcess(Process.myPid()) }, "LibrePods-audio-cleanup").start()
    }

    private fun describe(error: Throwable): String {
        var cause = error
        while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
        return "${cause.javaClass.simpleName}: ${cause.message.orEmpty()}".take(1024)
    }
}
