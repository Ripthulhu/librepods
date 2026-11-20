/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku

import android.os.Bundle
import android.os.Build
import android.os.Process
import androidx.annotation.Keep

@Keep
internal object SpatialNativeClient {
    private var loaded = false
    @Synchronized fun load(): Boolean {
        if (Process.myUid() != 0 || Build.VERSION.SDK_INT < 37) return false
        if (!loaded) loaded = runCatching { System.loadLibrary("pixel_spatial_control"); true }.getOrDefault(false)
        return loaded
    }
    @JvmStatic external fun nativeRequest(routeId: Int, routeEpoch: Long, mac: ByteArray, choiceEpoch: Long, choices: Int): Long
    @JvmStatic external fun nativeRelease(choiceEpoch: Long, routeEpoch: Long): Long
    @JvmStatic external fun nativeSnapshot(): LongArray
}

/** Cached native status is not proof that an audio buffer was committed. */
internal class SpatialBackendSnapshot private constructor(private val v: LongArray) {
    val supported get() = v[Slot.CAPABILITIES] == Capability.ALL
    val known get() = v[Slot.STATUS_READ_OK] == 1L && v[Slot.STATUS_ERROR] == 0L &&
        v[Slot.FLAGS] and StatusFlag.OWNED_WITH_KNOWN_CHOICE == StatusFlag.OWNED_WITH_KNOWN_CHOICE &&
        v[Slot.CACHED_OWNER_MATCHES] == 1L
    val routeId get() = v[Slot.ROUTE_ID].toInt()
    val routeEpoch get() = v[Slot.ROUTE_EPOCH]
    val choiceEpoch get() = v[Slot.CHOICE_EPOCH]
    val choices get() = v[Slot.DESIRED_CHOICES].toInt()
    val completed get() = v[Slot.COMPLETED_REQUEST]

    private fun flag(bit: Long) = v[Slot.FLAGS] and bit != 0L
    private fun actualChoice(bit: Long) = v[Slot.ACTUAL_CHOICES] and bit != 0L

    fun bundle() = Bundle().apply {
        val spatial = choices and Choice.SPATIAL.toInt() != 0
        val tracking = choices and Choice.TRACKING.toInt() != 0
        val actualAudio = known && v[Slot.FLAGS] and StatusFlag.RENDERED_AS_DESIRED == StatusFlag.RENDERED_AS_DESIRED &&
            v[Slot.APPLIED_CHOICE_EPOCH] == choiceEpoch && v[Slot.ACTUAL_CHOICES] == v[Slot.DESIRED_CHOICES]
        putInt("version", 1); putBoolean("supported", supported); putBoolean("known", known)
        putBoolean("available", supported && v[Slot.STATUS_READ_OK] == 1L)
        putBoolean("desired_spatial", spatial); putBoolean("desired_tracking", tracking)
        putBoolean("spatial_enabled", spatial); putBoolean("head_tracking_enabled", tracking)
        putLong("accepted_generation", choiceEpoch); putLong("applied_generation", v[Slot.APPLIED_CHOICE_EPOCH])
        putBoolean("actual_audio", actualAudio)
        putBoolean("output_committed", known && flag(StatusFlag.OUTPUT_COMMITTED))
        putBoolean("actual_spatial", actualChoice(Choice.SPATIAL))
        putBoolean("actual_tracking", actualChoice(Choice.TRACKING) && flag(StatusFlag.POSE_PRESENT))
        putInt("route_id", routeId); putLong("route_epoch", routeEpoch)
        putLong("rendered_at_ns", v[Slot.RENDERED_AT_NANOS]); putLong("committed_at_ns", v[Slot.COMMITTED_AT_NANOS])
        putInt("codec", v[Slot.CODEC].toInt()); putInt("native_failure", v[Slot.NATIVE_FAILURE].toInt())
        putBoolean("applied", false); putString("reason", if (supported) { if (known) "ok" else "waiting_for_owner" } else "unsupported")
    }

    /** Positions in the array SpatialControlJni.cpp `snapshot()` returns; see SpatialRenderControlWire.h. */
    internal object Slot {
        const val SNAPSHOT_VERSION = 0
        const val UPDATED_AT_NANOS = 1
        const val STATUS_READ_OK = 2
        const val STATUS_ERROR = 3
        const val FLAGS = 4
        const val CAPABILITIES = 5
        const val DESIRED_CHOICES = 6
        const val CHOICE_EPOCH = 7
        const val OWNER_PROCESS_ID = 8
        const val ROUTE_ID = 9
        const val ROUTE_EPOCH = 10
        const val CONTROL_GENERATION = 11
        const val SEQUENCE = 12
        const val APPLIED_CHOICE_EPOCH = 13
        const val ACTUAL_CHOICES = 14
        const val CODEC = 15
        const val OUTPUT_FRAMES = 16
        const val RENDERED_AT_NANOS = 17
        const val COMMITTED_AT_NANOS = 18
        const val NATIVE_FAILURE = 21
        const val COMPLETED_REQUEST = 22
        const val REQUEST_FENCE = 23
        const val HELPER_PROCESS_ID = 26
        const val CACHED_OWNER_MATCHES = 35
        const val COUNT = 36
        /** Slots that hold counters, ids or times and therefore can never be negative. */
        val NON_NEGATIVE = listOf(CHOICE_EPOCH, ROUTE_EPOCH, CONTROL_GENERATION, SEQUENCE, APPLIED_CHOICE_EPOCH,
            OUTPUT_FRAMES, RENDERED_AT_NANOS, COMMITTED_AT_NANOS, COMPLETED_REQUEST, REQUEST_FENCE)
    }

    /** `Choice` bits in SpatialRenderControlWire.h. Tracking is only valid together with spatial audio. */
    internal object Choice {
        const val SPATIAL = 1L
        const val TRACKING = 2L
        val VALID = setOf(0L, SPATIAL, SPATIAL or TRACKING)
    }

    /** `StatusFlag` bits in SpatialRenderControlWire.h. */
    internal object StatusFlag {
        const val OWNED = 2L
        const val DESIRED_KNOWN = 4L
        const val RENDERED = 8L
        const val OUTPUT_COMMITTED = 16L
        const val NATIVE_VERIFIED = 32L
        const val MATCHES_DESIRED = 64L
        const val POSE_PRESENT = 128L
        const val ALL = 255L
        const val OWNED_WITH_KNOWN_CHOICE = OWNED or DESIRED_KNOWN
        const val RENDERED_AND_VERIFIED = RENDERED or NATIVE_VERIFIED
        const val RENDERED_AS_DESIRED = RENDERED or OUTPUT_COMMITTED or NATIVE_VERIFIED or MATCHES_DESIRED
    }

    /** `Capability` bits in SpatialRenderControlWire.h; this client needs every one of them. */
    internal object Capability {
        const val ALL = 31L
    }

    companion object {
        const val ABSENT = "backend_absent"
        private const val SNAPSHOT_VERSION = 1L
        private const val MAXIMUM_STATUS_ERROR = 3L
        private const val MAXIMUM_CODEC = 2L
        private const val MAXIMUM_AGE_NANOS = 2_000_000_000L

        fun read(value: LongArray?, nowNs: Long, pid: Int): SpatialBackendSnapshot? {
            if (value == null || value.size != Slot.COUNT) return null
            if (nowNs <= 0 || pid <= 0) return null
            if (!isFreshFromThisHelper(value, nowNs, pid) || !fieldsInRange(value)) return null
            val consistent = ownershipConsistent(value, pid) && outputCommitConsistent(value) && desiredMatchConsistent(value)
            return if (consistent) SpatialBackendSnapshot(value.copyOf()) else null
        }

        private fun isFreshFromThisHelper(value: LongArray, nowNs: Long, pid: Int) =
            value[Slot.SNAPSHOT_VERSION] == SNAPSHOT_VERSION && value[Slot.UPDATED_AT_NANOS] > 0 &&
                nowNs - value[Slot.UPDATED_AT_NANOS] in 0..MAXIMUM_AGE_NANOS && value[Slot.HELPER_PROCESS_ID] == pid.toLong()

        private fun fieldsInRange(value: LongArray): Boolean {
            val flags = value[Slot.FLAGS]
            val ownedBits = flags and StatusFlag.OWNED_WITH_KNOWN_CHOICE
            return value[Slot.STATUS_READ_OK] in 0..1 && value[Slot.STATUS_ERROR] in 0..MAXIMUM_STATUS_ERROR &&
                flags and StatusFlag.ALL == flags && value[Slot.CAPABILITIES] and Capability.ALL == value[Slot.CAPABILITIES] &&
                value[Slot.DESIRED_CHOICES] in Choice.VALID && value[Slot.ACTUAL_CHOICES] in Choice.VALID &&
                value[Slot.CACHED_OWNER_MATCHES] in 0..1 && value[Slot.ROUTE_ID] in 0..Int.MAX_VALUE.toLong() &&
                value[Slot.CODEC] in 0..MAXIMUM_CODEC && Slot.NON_NEGATIVE.none { value[it] < 0 } &&
                value[Slot.COMPLETED_REQUEST] <= value[Slot.REQUEST_FENCE] &&
                (ownedBits == 0L || ownedBits == StatusFlag.OWNED_WITH_KNOWN_CHOICE)
        }

        /** An owned, known choice must name this helper, a real epoch and a real route. */
        private fun ownershipConsistent(value: LongArray, pid: Int): Boolean {
            if (value[Slot.FLAGS] and StatusFlag.OWNED_WITH_KNOWN_CHOICE != StatusFlag.OWNED_WITH_KNOWN_CHOICE) return true
            return value[Slot.OWNER_PROCESS_ID] == pid.toLong() && value[Slot.CHOICE_EPOCH] > 0 &&
                value[Slot.ROUTE_ID] > 0 && value[Slot.ROUTE_EPOCH] > 0
        }

        /** A committed output must be a verified render with a codec, frames and ordered times. */
        private fun outputCommitConsistent(value: LongArray): Boolean {
            if (value[Slot.FLAGS] and StatusFlag.OUTPUT_COMMITTED == 0L) return true
            return value[Slot.FLAGS] and StatusFlag.RENDERED_AND_VERIFIED == StatusFlag.RENDERED_AND_VERIFIED &&
                value[Slot.CODEC] in 1..MAXIMUM_CODEC && value[Slot.APPLIED_CHOICE_EPOCH] > 0 &&
                value[Slot.OUTPUT_FRAMES] > 0 && value[Slot.RENDERED_AT_NANOS] > 0 &&
                value[Slot.COMMITTED_AT_NANOS] >= value[Slot.RENDERED_AT_NANOS]
        }

        /** Claiming to match the desired choice needs native verification of that same epoch and choice. */
        private fun desiredMatchConsistent(value: LongArray): Boolean {
            if (value[Slot.FLAGS] and StatusFlag.MATCHES_DESIRED == 0L) return true
            return value[Slot.FLAGS] and StatusFlag.NATIVE_VERIFIED != 0L &&
                value[Slot.APPLIED_CHOICE_EPOCH] == value[Slot.CHOICE_EPOCH] &&
                value[Slot.ACTUAL_CHOICES] == value[Slot.DESIRED_CHOICES]
        }

        fun unavailable(reason: String) = Bundle().apply {
            putInt("version", 1); putBoolean("supported", false); putBoolean("known", false)
            putBoolean("available", false); putBoolean("applied", false); putString("reason", reason)
        }
    }
}
