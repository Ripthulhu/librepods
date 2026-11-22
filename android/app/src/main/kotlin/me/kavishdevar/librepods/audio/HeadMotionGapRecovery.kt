/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.audio

/** A retry budget only. Call submitted after successful delivery; it never owns a stream or lease. */
internal class HeadMotionGapRecovery {
    @Volatile var latched = false
        private set
    private var available = true
    private var healthySince = 0L
    private var lastArrival = 0L
    private var lastSubmit = 0L

    @Synchronized fun submitted(arrivalNs: Long, nowNs: Long) {
        if (latched) return
        if (isArrivalInvalid(arrivalNs, nowNs) || isArrivalStale(arrivalNs, nowNs) ||
            isArrivalOutOfOrder(arrivalNs, nowNs)) {
            resetHealthy(); return
        }
        if (healthySince == 0L || arrivalNs - lastArrival >= FRESH_NS || nowNs - lastSubmit >= FRESH_NS)
            healthySince = nowNs
        lastArrival = arrivalNs; lastSubmit = nowNs
        if (nowNs - healthySince >= REPLENISH_NS) available = true
    }

    @Synchronized fun failed(verifiedSensorGap: Boolean): Boolean {
        resetHealthy()
        if (latched || !verifiedSensorGap || !available) { latched = true; return false }
        available = false
        return true
    }

    @Synchronized fun withheld() { resetHealthy() }

    private fun resetHealthy() { healthySince = 0; lastArrival = 0; lastSubmit = 0 }

    /** Missing clock or a receipt timestamp from the future. */
    private fun isArrivalInvalid(arrivalNs: Long, nowNs: Long) = arrivalNs <= 0 || nowNs <= 0 || arrivalNs > nowNs

    private fun isArrivalStale(arrivalNs: Long, nowNs: Long) = nowNs - arrivalNs > FRESH_NS

    /** Only meaningful once a previous submission has been recorded. */
    private fun isArrivalOutOfOrder(arrivalNs: Long, nowNs: Long) =
        lastArrival != 0L && (arrivalNs <= lastArrival || nowNs <= lastSubmit)

    /** Automatic reacquisition retains the same budget; explicit source/choice changes replace it. */
    class Scopes {
        private data class Source(val key: SpatialPoseProfileKey, val basis: List<List<Double>>,
            val referenceNs: Long?, val referenceCounter: Long?, val choiceEpoch: Long)
        private var socket: Any? = null
        private var source: Source? = null
        private var recovery = HeadMotionGapRecovery()

        @Synchronized fun forSource(socket: Any, key: SpatialPoseProfileKey,
            profile: SpatialPoseProfile, choiceEpoch: Long): HeadMotionGapRecovery {
            val next = Source(key.copy(rawRole = key.rawRole.toList()), profile.headFromRaw.map { it.toList() },
                profile.referenceTimestampNs, profile.referenceCounter, choiceEpoch)
            if (this.socket !== socket || source != next) {
                this.socket = socket; source = next; recovery = HeadMotionGapRecovery()
            }
            return recovery
        }
    }

    private companion object {
        const val FRESH_NS = 250_000_000L
        const val REPLENISH_NS = 30_000_000_000L
    }
}
