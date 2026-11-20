/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.audio

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SPATIAL_MOTION_FRAME_VERSION = 1
private const val SPATIAL_MOTION_FRAME_BYTES = 48
private const val OWNER_CHECK_INTERVAL_MILLISECONDS = 100L

/** Version 1: 48 little-endian bytes using elapsedRealtimeNanos for the Bluetooth receipt timestamp. */
internal fun spatialMotionFrame(pose: SpatialPose, arrivalNs: Long): ByteArray =
    ByteBuffer.allocate(SPATIAL_MOTION_FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(SPATIAL_MOTION_FRAME_VERSION)
        putInt(pose.discontinuity)
        putLong(pose.timestampNs)
        putLong(arrivalNs)
        pose.rotationVector.forEach(::putFloat)
        pose.angularVelocity.forEach(::putFloat)
    }.array()

internal class ExternalSpatialMotion(
    private val scope: CoroutineScope,
    private val owner: IBinder,
    private val uid: Int,
    private val metadata: Bundle,
    private val current: () -> Boolean,
    private val produce: suspend ((SpatialPose, Long) -> Unit) -> Unit,
    private val release: () -> Unit,
) : ISpatialMotionLease.Stub() {
    private val live = AtomicBoolean(true)
    private val death = IBinder.DeathRecipient { retire() }
    private var output: ParcelFileDescriptor? = null
    private var writer: Job? = null

    fun watch(): Boolean {
        try {
            owner.linkToDeath(death, 0)
        } catch (expectedLinkFailure: Exception) {
            // Intentionally broad: native binder errors surface as RemoteException or assorted runtime exceptions.
            retire()
            return false
        }
        scope.launch {
            try {
                while (available()) delay(OWNER_CHECK_INTERVAL_MILLISECONDS)
            } finally {
                retire()
            }
        }.invokeOnCompletion { retire() }
        return available()
    }

    override fun identity(): Bundle {
        checkCaller()
        return if (available()) Bundle(metadata) else Bundle()
    }

    override fun isCurrent(): Boolean {
        checkCaller()
        return available()
    }

    @Synchronized override fun openPipe(): ParcelFileDescriptor? {
        checkCaller()
        if (!available() || output != null) return null
        val (input, output) = ParcelFileDescriptor.createPipe()
        try {
            val fd = output.fileDescriptor
            val flags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0)
            Os.fcntlInt(fd, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
            this.output = output
            return input
        } catch (failure: Exception) {
            runCatching { input.close() }
            runCatching { output.close() }
            retire()
            throw failure
        }
    }

    @Synchronized override fun activate(): Boolean {
        checkCaller()
        val fd = output?.fileDescriptor ?: return false
        if (!available() || writer != null) return false
        writer = scope.launch {
            try {
                produce { pose, arrivalNs ->
                    check(available())
                    val bytes = spatialMotionFrame(pose, arrivalNs)
                    check(Os.write(fd, bytes, 0, bytes.size) == bytes.size)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (expectedStreamEnd: Exception) {
                // Intentionally broad: produce is caller-supplied and may fail with anything.
                // EOF tells the client to retire or acquire a fresh source.
            }
        }.also { job -> job.invokeOnCompletion { retire() } }
        return true
    }

    override fun close() {
        checkCaller()
        retire()
    }

    private fun checkCaller() {
        check(Binder.getCallingUid() == uid) { "Spatial motion lease belongs to another UID" }
    }

    private fun available() = live.get() && owner.isBinderAlive && current()

    @Synchronized private fun retire() {
        if (!live.compareAndSet(true, false)) return
        runCatching { owner.unlinkToDeath(death, 0) }
        writer?.cancel()
        runCatching { output?.close() }
        release()
    }
}
