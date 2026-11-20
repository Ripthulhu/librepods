/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import rikka.shizuku.SystemServiceHelper
import java.io.Closeable
import java.io.FileDescriptor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal interface HeadTrackerSinkSession : Closeable {
    val reporting: RootHeadTrackerSink.Reporting
    val halHandshake: Boolean
    val isClosed: Boolean
    val stopReason: String?
    val stats: HeadTrackerSinkStats get() = HeadTrackerSinkStats()
}

internal fun selectedHeadTrackerRouteCurrent(address: String, owner: IPlaybackRecoveryOwner,
    nativeRoute: (String) -> Boolean): Boolean =
    if (owner is HeadTrackerRouteWitness) owner.selectedRouteCurrent(address) else nativeRoute(address)

/** Root-only sink. The caller transfers a readable pose pipe; this sink enables no audio/settings. */
internal class RootHeadTrackerSink(
    private val selectedRoute: (String) -> Boolean,
    private val caller: () -> Unit,
) : Closeable {
    constructor(caller: () -> Unit) : this(NativeA2dpRoute()::selected, caller)

    private var active: Session? = null
    private var closed = false
    data class Reporting(val enabled: Boolean, val intervalNanos: Long)

    /** onReportingChanged must enqueue work promptly; it runs after synchronous HID replies. */
    @Synchronized fun open(address: String, owner: IPlaybackRecoveryOwner, poses: ParcelFileDescriptor,
        onReportingChanged: (Reporting) -> Unit): Session {
        var session: Session? = null
        var opened = false
        try {
            caller()
            check(Process.myUid() == 0 && !closed && active == null) { "head_tracker_sink_unavailable" }
            val selected = A2dpRecoveryGuard(address).address
            requireCurrent(selected, owner)
            val descriptor = poses.fileDescriptor
            check(OsConstants.S_ISFIFO(Os.fstat(descriptor).st_mode)) { "pose_fd_not_pipe" }
            val flags = Os.fcntlInt(descriptor, OsConstants.F_GETFL, 0)
            check(flags and OsConstants.O_ACCMODE == OsConstants.O_RDONLY) { "pose_pipe_not_readable" }
            Os.fcntlInt(descriptor, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
            session = Session(selected, owner, poses, onReportingChanged)
            owner.asBinder().linkToDeath(session, 0)
            requireCurrent(selected, owner)
            active = session
            session.start()
            opened = true
            return session
        } finally {
            // Any failure, including errors, releases what was taken and propagates unchanged.
            if (!opened) {
                session?.let { runCatching { owner.asBinder().unlinkToDeath(it, 0) } }
                runCatching { poses.close() }
                if (active === session) active = null
            }
        }
    }

    private fun requireCurrent(address: String, owner: IPlaybackRecoveryOwner) {
        check(owner.asBinder().isBinderAlive && owner.isCurrent && selectedHeadTrackerRouteCurrent(address, owner, selectedRoute) &&
            owner.asBinder().isBinderAlive && owner.isCurrent) { "head_tracker_owner_or_route_changed" }
    }

    inner class Session internal constructor(private val address: String, private val owner: IPlaybackRecoveryOwner,
        private val poses: ParcelFileDescriptor, private val onReportingChanged: (Reporting) -> Unit) :
        HeadTrackerSinkSession, IBinder.DeathRecipient {
        private val stopped = AtomicBoolean(false)
        @Volatile override var reporting = Reporting(false, 10_000_000L)
            private set
        @Volatile override var halHandshake = false
            private set
        @Volatile override var isClosed = false
            private set
        @Volatile override var stopReason: String? = null
            private set
        private val metrics = HeadTrackerSinkMetrics()
        override val stats get() = metrics.snapshot()

        private fun stop(reason: String) {
            if (stopReason == null) stopReason = reason
            stopped.set(true)
        }
        override fun close() = stop("closed")
        override fun binderDied() = stop("owner_died")

        internal fun start() { thread(name = "head-tracker-uhid", isDaemon = true) { run() } }

        private fun send(descriptor: FileDescriptor, bytes: ByteArray) {
            while (!stopped.get()) {
                try {
                    check(Os.write(descriptor, bytes, 0, bytes.size) == bytes.size) { "short_uhid_write" }
                    return
                } catch (error: ErrnoException) { if (error.errno != OsConstants.EINTR) throw error }
            }
            throw IllegalStateException("head_tracker_cancelled")
        }

        private fun run() {
            var uhid: FileDescriptor? = null
            try {
                requireCurrent(address, owner)
                check(!stopped.get()) { "head_tracker_cancelled" }
                val descriptor = Os.open("/dev/uhid", OsConstants.O_RDWR or OsConstants.O_CLOEXEC or OsConstants.O_NONBLOCK, 0)
                uhid = descriptor
                check(OsConstants.S_ISCHR(Os.fstat(descriptor).st_mode)) { "uhid_not_character_device" }
                UhidPump(descriptor).run()
            } catch (expectedFailure: Exception) {
                // Any failure ends this sink; the owner learns it from isClosed and stopReason.
                if (!stopped.get()) { stop("sink_failed"); Log.w("RootHeadTrackerSink", "Head tracker sink stopped", expectedFailure) }
            } finally {
                stopped.set(true)
                reporting = reporting.copy(enabled = false)
                runCatching { poses.close() }
                // Closing this worker-owned FD unregisters UHID, including on owner/process death.
                uhid?.let { runCatching { Os.close(it) } }
                runCatching { onReportingChanged(reporting) }
                runCatching { owner.asBinder().unlinkToDeath(this, 0) }
                isClosed = true
                synchronized(this@RootHeadTrackerSink) { if (active === this) active = null }
            }
        }

        /** Serves one UHID device: answers host requests and forwards fresh poses until stopped. */
        private inner class UhidPump(private val descriptor: FileDescriptor) {
            private val protocol = HeadTrackerUhidProtocol(address)
            private val gate = HeadTrackerPoseGate()
            private val pacer = HeadTrackerReportPacer()
            private val inputEvent = HeadTrackerUhidProtocol.event(HeadTrackerUhidProtocol.INPUT2)
            private val events = ByteArray(HeadTrackerUhidProtocol.EVENT_BYTES)
            private val frame = ByteArray(HeadTrackerUhidProtocol.FRAME_BYTES)
            private var partial = 0
            private var nextOwnerCheck = 0L
            private val uhidPoll = StructPollfd().apply { fd = descriptor; this.events = OsConstants.POLLIN.toShort() }
            private val posePoll = StructPollfd().apply { fd = poses.fileDescriptor; this.events = OsConstants.POLLIN.toShort() }
            private val polls = arrayOf(uhidPoll, posePoll)
            private val ended = OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL

            fun run() {
                send(descriptor, protocol.create())
                while (!stopped.get()) {
                    if (!poll(checkOwnerWhenDue())) continue
                    check(!stopped.get()) { "head_tracker_cancelled" }
                    check(polls.none { it.revents.toInt() and ended != 0 }) { "head_tracker_fd_closed" }
                    if (uhidPoll.revents.toInt() and OsConstants.POLLIN != 0) answerUhidEvent()
                    if (readPoses() == MAX_POSES_PER_WAKE) checkPoseBacklog()
                    sendDuePose()
                }
            }

            /** Returns the current time after any owner check. */
            private fun checkOwnerWhenDue(): Long {
                var now = SystemClock.elapsedRealtimeNanos()
                if (now >= nextOwnerCheck) {
                    requireCurrent(address, owner)
                    check(!stopped.get()) { "head_tracker_cancelled" }
                    now = SystemClock.elapsedRealtimeNanos()
                    nextOwnerCheck = now + OWNER_CHECK_INTERVAL_NANOS
                }
                return now
            }

            /** Waits for the next event or deadline; returns false when the wait was interrupted. */
            private fun poll(now: Long): Boolean {
                var wake = minOf(nextOwnerCheck, gate.deadline)
                if (protocol.reporting && gate.hasPose) wake = minOf(wake, pacer.deadline)
                val timeout = ((wake - now).coerceAtLeast(0) + 999_999) / 1_000_000
                polls.forEach { it.revents = 0 }
                try { Os.poll(polls, timeout.coerceAtMost(MAX_POLL_MILLISECONDS).toInt()) }
                catch (error: ErrnoException) { if (error.errno == OsConstants.EINTR) return false else throw error }
                return true
            }

            private fun answerUhidEvent() {
                val count = Os.read(descriptor, events, 0, events.size)
                check(count == events.size) { "short_uhid_read" }
                protocol.handle(events)?.let { send(descriptor, it) }
                halHandshake = protocol.halHandshake
                val now = SystemClock.elapsedRealtimeNanos()
                val changed = Reporting(protocol.reporting, protocol.intervalNanos)
                if (changed != reporting) {
                    gate.reporting(changed.enabled, now)
                    pacer.reporting(changed.enabled, changed.intervalNanos, now)
                    if (!changed.enabled) metrics.idle()
                    reporting = changed
                    onReportingChanged(changed)
                }
            }

            /** Drains up to MAX_POSES_PER_WAKE complete frames; returns how many were accepted. */
            private fun readPoses(): Int {
                var received = 0
                while (!stopped.get() && received < MAX_POSES_PER_WAKE) {
                    val count = readPoseBytes() ?: break
                    check(count > 0) { "pose_pipe_eof" }
                    partial += count
                    if (partial == frame.size) {
                        val now = SystemClock.elapsedRealtimeNanos()
                        val pose = HeadTrackerUhidProtocol.decodePose(frame, now)
                        if (gate.accept(pose, now)) metrics.accepted(pose.timestampNanos, now)
                        partial = 0; ++received
                    }
                }
                return received
            }

            /** Retries interrupted reads; returns null once the pipe is drained or the sink stopped. */
            private fun readPoseBytes(): Int? {
                while (!stopped.get()) {
                    try { return Os.read(poses.fileDescriptor, frame, partial, frame.size - partial) }
                    catch (error: ErrnoException) {
                        if (error.errno == OsConstants.EAGAIN) return null
                        if (error.errno != OsConstants.EINTR) throw error
                    }
                }
                return null
            }

            /** A full batch must have emptied the pipe; anything left means the writer outpaces this sink. */
            private fun checkPoseBacklog() {
                posePoll.revents = 0
                Os.poll(arrayOf(posePoll), 0)
                check(posePoll.revents.toInt() and ended == 0) { "pose_pipe_closed" }
                check(posePoll.revents.toInt() and OsConstants.POLLIN == 0) { "pose_backlog" }
            }

            private fun sendDuePose() {
                val now = SystemClock.elapsedRealtimeNanos()
                gate.checkFresh(now)
                if (!protocol.reporting || !gate.hasPose || now < pacer.deadline) return
                val pose = gate.pose(now)
                val emitted = pose?.let { HeadTrackerUhidProtocol.poseAtHostTime(it, now) }
                if (pose == null || emitted == null) {
                    metrics.skipped(pacer.deadline, now, reporting.intervalNanos)
                } else {
                    HeadTrackerUhidProtocol.input(emitted, inputEvent)
                    val submittedAt = SystemClock.elapsedRealtimeNanos()
                    send(descriptor, inputEvent)
                    metrics.input(pose.timestampNanos, submittedAt, emitted !== pose,
                        pacer.deadline, reporting.intervalNanos)
                }
                pacer.advance(SystemClock.elapsedRealtimeNanos())
            }
        }
    }

    @Synchronized override fun close() { closed = true; active?.close() }

    private companion object {
        const val OWNER_CHECK_INTERVAL_NANOS = 250_000_000L
        const val MAX_POLL_MILLISECONDS = 250L
        const val MAX_POSES_PER_WAKE = 32
    }

    /** Exact selected native A2DP route only; no context attribution or mutating APIs. */
    internal class NativeA2dpRoute {
        // Hidden audio service reflection is how this sink reads the selected route; no public API exposes it.
        @SuppressLint("PrivateApi") private val api = Class.forName("android.media.IAudioService")
        @SuppressLint("PrivateApi") private val attributes = Class.forName("android.media.AudioDeviceAttributes")
        @SuppressLint("PrivateApi") private val service = Class.forName("android.media.IAudioService\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null,
                checkNotNull(SystemServiceHelper.getSystemService("audio")))
        private val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
        private val routes = api.getMethod("getDevicesForAttributes", AudioAttributes::class.java).also {
            check(it.returnType == List::class.java)
        }
        private val mode = api.getMethod("getMode").also { check(it.returnType == Int::class.javaPrimitiveType) }
        fun selected(address: String): Boolean {
            val devices = routes.invoke(service, media) as List<*>
            val selected = devices.singleOrNull() ?: return false
            return attributes.isInstance(selected) && attributes.getMethod("getRole").invoke(selected) == 2 &&
                attributes.getMethod("getType").invoke(selected) == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP &&
                address.equals(attributes.getMethod("getAddress").invoke(selected) as? String, true) && mode.invoke(service) == 0
        }
    }
}
