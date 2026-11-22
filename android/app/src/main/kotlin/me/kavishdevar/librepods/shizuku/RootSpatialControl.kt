/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Owns a short native route lease; periodic fresh route checks alone renew it. */
internal class RootSpatialControl(private val context: Context) : AutoCloseable {
    private data class Selection(val address: String, val owner: IPlaybackRecoveryOwner,
        val routeId: Int, val routeEpoch: Long, val choiceEpoch: Long, val choices: Int)
    private val lock = Any()
    private val framework = SpatialAudioAccess.Framework(context)
    private val audio = context.getSystemService(AudioManager::class.java)
    @Volatile private var selected: Selection? = null
    @Volatile private var closed = false
    private val heartbeat = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "atmos-spatial-owner").apply { isDaemon = true }
    }.apply { scheduleWithFixedDelay({ renew() }, LEASE_RENEW_MILLISECONDS, LEASE_RENEW_MILLISECONDS, TimeUnit.MILLISECONDS) }

    private fun route(address: String, owner: IPlaybackRecoveryOwner): Int {
        check(!closed && Process.myUid() == 0 && owner.asBinder().isBinderAlive && owner.isCurrent) { "owner_changed" }
        check(audio.mode == AudioManager.MODE_NORMAL && audio.activeRecordingConfigurations.isEmpty()) { "audio_busy" }
        val native = framework.read(address)
        check(native.nativePair && native.routeSelected && native.compatible == true) { "route_unavailable" }
        val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        check(outputs.size == 1 && outputs[0].address.equals(address, true)) { "route_ambiguous" }
        check(owner.isCurrent) { "owner_changed" }
        return outputs.single().id.also { check(it > 0) }
    }
    private fun snapshot() = if (SpatialNativeClient.load()) runCatching {
        SpatialBackendSnapshot.read(SpatialNativeClient.nativeSnapshot(), SystemClock.elapsedRealtimeNanos(), Process.myPid())
    }.getOrNull() else null

    // Check the installed module too, in case the helper's mount namespace hides vendor overlays.
    private fun backendAbsent() = listOf(
        "/data/adb/modules/pixel_ac4_headphone_preview",
        "/vendor/lib64/libcodec2_soft_pixel_ddpfloat.so",
        "/vendor/lib64/libcodec2_soft_pixel_ac4float.so",
    ).all { path ->
        try {
            Os.stat(path)
            false
        } catch (error: ErrnoException) {
            error.errno == OsConstants.ENOENT
        }
    }

    fun inspect(address: String, owner: IPlaybackRecoveryOwner): Bundle = runCatching {
        val normalized = A2dpRecoveryGuard(address).address
        val id = route(normalized, owner)
        val result = snapshot()
        if (result?.supported != true && backendAbsent())
            return SpatialBackendSnapshot.unavailable(SpatialBackendSnapshot.ABSENT)
        if (result == null) return SpatialBackendSnapshot.unavailable("loading")
        result.bundle().apply { putBoolean("route_verified", true); if (result.known && result.routeId != id) {
            putBoolean("known", false); putBoolean("available", false); putString("reason", "route_changed")
        } }
    }.getOrElse { SpatialBackendSnapshot.unavailable("route_unavailable") }

    fun set(address: String, owner: IPlaybackRecoveryOwner, spatial: Boolean, tracking: Boolean, epoch: Long): Bundle {
        if (epoch <= 0 || tracking && !spatial) return SpatialBackendSnapshot.unavailable("invalid_choice")
        val normalized = A2dpRecoveryGuard(address).address
        val id = runCatching { route(normalized, owner) }.getOrElse { return SpatialBackendSnapshot.unavailable("route_unavailable") }
        if (snapshot()?.supported != true && backendAbsent())
            return SpatialBackendSnapshot.unavailable(SpatialBackendSnapshot.ABSENT)
        if (!SpatialNativeClient.load()) return SpatialBackendSnapshot.unavailable("unsupported")
        val captured = select(normalized, owner, id, epoch, choiceBits(spatial, tracking))
            ?: return SpatialBackendSnapshot.unavailable("owner_changed")
        val serial = runCatching { submit(captured) }.getOrElse { retireSelection(captured); return SpatialBackendSnapshot.unavailable("unavailable") }
        return awaitApplied(captured, owner, serial)
            ?.bundle()?.apply { putBoolean("applied", true); putBoolean("route_verified", true); putLong("choice_epoch", epoch) }
            ?: SpatialBackendSnapshot.unavailable("request_failed")
    }

    private fun choiceBits(spatial: Boolean, tracking: Boolean): Int =
        (if (spatial) SpatialBackendSnapshot.Choice.SPATIAL.toInt() else 0) or
            (if (tracking) SpatialBackendSnapshot.Choice.TRACKING.toInt() else 0)

    /** Records the new selection, keeping the route epoch of an unchanged route; null when a newer epoch already owns it. */
    private fun select(address: String, owner: IPlaybackRecoveryOwner, routeId: Int, epoch: Long, choices: Int): Selection? =
        synchronized(lock) {
            val previous = selected
            if (previous != null && previous.address == address && epoch < previous.choiceEpoch) return null
            val sameRoute = previous?.takeIf { it.address == address && it.routeId == routeId && it.owner.asBinder() == owner.asBinder() }
            Selection(address, owner, routeId, sameRoute?.routeEpoch ?: SystemClock.elapsedRealtimeNanos(), epoch, choices)
                .also { selected = it }
        }

    /** Polls the native status until it reports this exact request applied, or the owner or deadline ends the wait. */
    private fun awaitApplied(captured: Selection, owner: IPlaybackRecoveryOwner, serial: Long): SpatialBackendSnapshot? {
        val deadline = SystemClock.elapsedRealtime() + APPLY_TIMEOUT_MILLISECONDS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (selected !== captured || !runCatching { owner.isCurrent }.getOrDefault(false)) return null
            val result = snapshot()
            if (result != null && reportsApplied(result, captured, serial)) return result
            Thread.sleep(APPLY_POLL_MILLISECONDS)
        }
        return null
    }

    /** The native side finished this request and now holds exactly the captured choice on the captured route. */
    private fun reportsApplied(result: SpatialBackendSnapshot, captured: Selection, serial: Long): Boolean {
        if (result.completed < serial || !result.known) return false
        val sameChoice = result.choiceEpoch == captured.choiceEpoch && result.choices == captured.choices
        val sameRoute = result.routeId == captured.routeId && result.routeEpoch == captured.routeEpoch
        return sameChoice && sameRoute
    }
    private fun submit(value: Selection): Long {
        check(selected === value && route(value.address, value.owner) == value.routeId && selected === value)
        val mac = value.address.split(':').map { it.toInt(16).toByte() }.toByteArray()
        return SpatialNativeClient.nativeRequest(value.routeId, value.routeEpoch, mac, value.choiceEpoch, value.choices)
            .also { check(it > 0) }
    }
    private fun renew() {
        val value = selected ?: return
        if (closed) return
        if (runCatching { submit(value) }.isFailure) retireSelection(value)
    }
    fun authorizes(address: String, epoch: Long): Boolean {
        val value = selected ?: return false
        if (value.address != address || value.choiceEpoch != epoch || value.choices != SPATIAL_WITH_TRACKING) return false
        return runCatching { route(address, value.owner) == value.routeId && snapshot()?.let {
            it.known && it.choiceEpoch == epoch && it.choices == SPATIAL_WITH_TRACKING && it.routeId == value.routeId && it.routeEpoch == value.routeEpoch
        } == true }.getOrDefault(false)
    }
    fun retire(owner: IPlaybackRecoveryOwner) {
        val old = selected?.takeIf { it.owner.asBinder() == owner.asBinder() } ?: return
        retireSelection(old)
    }
    private fun retireSelection(value: Selection) {
        val old = synchronized(lock) { selected?.takeIf { it === value }?.also { selected = null } } ?: return
        runCatching { SpatialNativeClient.nativeRelease(old.choiceEpoch, old.routeEpoch) }
    }
    override fun close() {
        closed = true; heartbeat.shutdownNow()
        selected?.let { retire(it.owner) }
    }

    private companion object {
        const val LEASE_RENEW_MILLISECONDS = 750L
        const val APPLY_TIMEOUT_MILLISECONDS = 2_000L
        const val APPLY_POLL_MILLISECONDS = 20L
        val SPATIAL_WITH_TRACKING = (SpatialBackendSnapshot.Choice.SPATIAL or SpatialBackendSnapshot.Choice.TRACKING).toInt()
    }
}
