/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.audio

import me.kavishdevar.librepods.core.data.apple.MotionReport
import me.kavishdevar.librepods.core.data.apple.motionCounterStep
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

internal enum class SpatialCalibrationFailure {
    INSUFFICIENT_SAMPLES, SENSOR_TIME_CHANGED, SENSOR_COUNTER_CHANGED, UNSUPPORTED_ATTITUDE,
    NEUTRAL_UNSTEADY, RETURN_UNSTEADY, RETURN_NOT_NEUTRAL, NEUTRAL_GRAVITY_INVALID,
    RETURN_GRAVITY_INVALID, UP_TOO_SMALL, UP_AXIS_NOT_HORIZONTAL, UP_AXIS_INCONSISTENT, INVALID_BASIS,
}

internal data class SpatialCalibrationResult(val profile: SpatialPoseProfile?, val failure: SpatialCalibrationFailure?)

/** Explicit, session-specific anatomical axes. Rows map raw-body vectors to right/front/up. */
internal class SpatialPoseProfile private constructor(val headFromRaw: List<List<Double>>,
    internal val referenceTimestampNs: Long? = null, internal val referenceCounter: Long? = null) {
    companion object {
        fun fromBasis(rows: List<List<Double>>): SpatialPoseProfile? {
            if (rows.size != 3 || rows.any { it.size != 3 || it.any { n -> !n.isFinite() } }) return null
            val axes = rows.map { V.of(it) }
            fun deviates(value: Double) = abs(value) > 1e-5
            val notUnitLength = axes.any { deviates(it.norm() - 1) }
            val notOrthogonal = deviates(axes[0].dot(axes[1])) || deviates(axes[0].dot(axes[2])) || deviates(axes[1].dot(axes[2]))
            val notRightHanded = deviates(axes[0].cross(axes[1]).dot(axes[2]) - 1)
            if (notUnitLength || notOrthogonal || notRightHanded) return null
            return SpatialPoseProfile(rows.map { it.toList() })
        }

        /** The caller must identify a level neutral hold, positive nose-UP-only excursion, and return.
         * The settled final 500 ms of neutral/return validate world +Z as up in this sensor session.
         * UP gravity is intentionally unused: it can lag the attitude during movement.
         */
        fun calibrate(neutral: List<MotionReport>, up: List<MotionReport>, returned: List<MotionReport>): SpatialPoseProfile? =
            calibrateResult(neutral, up, returned).profile

        fun calibrateResult(neutral: List<MotionReport>, up: List<MotionReport>, returned: List<MotionReport>): SpatialCalibrationResult {
            fun failed(reason: SpatialCalibrationFailure) = SpatialCalibrationResult(null, reason)
            val ready = neutral.dropWhile { it.attitudeQ15.all { n -> n == 0 } }
            if (ready.size < 5 || returned.size < 5 || up.size < 3) return failed(SpatialCalibrationFailure.INSUFFICIENT_SAMPLES)
            val all = ready + up + returned
            sequenceFailure(all)?.let { return failed(it) }
            val quaternions = all.map { attitude(it) ?: return failed(SpatialCalibrationFailure.UNSUPPORTED_ATTITUDE) }
            val neutralTail = settledTail(ready); val returnTail = settledTail(returned)
            if (neutralTail.size < 5 || returnTail.size < 5) return failed(SpatialCalibrationFailure.INSUFFICIENT_SAMPLES)
            val base = meanHold(neutralTail.map { checkNotNull(attitude(it)) })
                ?: return failed(SpatialCalibrationFailure.NEUTRAL_UNSTEADY)
            val finish = meanHold(returnTail.map { checkNotNull(attitude(it)) })
                ?: return failed(SpatialCalibrationFailure.RETURN_UNSTEADY)
            holdFailure(base, finish, neutralTail, returnTail)?.let { return failed(it) }
            return profileFromLookUp(quaternions.drop(ready.size).take(up.size), base, returned.last())
        }

        /** Builds the head axes from the look-up rotations: x from the pitch axis, z as world up seen from neutral. */
        private fun profileFromLookUp(lookUp: List<Q>, base: Q, last: MotionReport): SpatialCalibrationResult {
            fun failed(reason: SpatialCalibrationFailure) = SpatialCalibrationResult(null, reason)
            val z = base.inverse().rotate(V(0.0, 0.0, 1.0))
            val axes = lookUpAxes(lookUp, base, z) ?: return failed(SpatialCalibrationFailure.UP_AXIS_NOT_HORIZONTAL)
            if (axes.size < 3) return failed(SpatialCalibrationFailure.UP_TOO_SMALL)
            val x = axes.reduce(V::plus).unit()
            if (axes.any { angleBetween(it, x) > radians(5.0) }) return failed(SpatialCalibrationFailure.UP_AXIS_INCONSISTENT)
            val y = z.cross(x).unit()
            val basis = fromBasis(listOf(x.list(), y.list(), z.list())) ?: return failed(SpatialCalibrationFailure.INVALID_BASIS)
            return SpatialCalibrationResult(SpatialPoseProfile(basis.headFromRaw, last.timestampNs, last.counter), null)
        }

        /** Sensor time must rise and the counter advance between consecutive reports. */
        private fun sequenceFailure(reports: List<MotionReport>): SpatialCalibrationFailure? {
            for ((a, b) in reports.zipWithNext()) {
                if (!timeFollows(a.timestampNs, b.timestampNs)) return SpatialCalibrationFailure.SENSOR_TIME_CHANGED
                if (!counterFollows(a.counter, b.counter, a.timestampNs, b.timestampNs)) return SpatialCalibrationFailure.SENSOR_COUNTER_CHANGED
            }
            return null
        }

        private fun timeFollows(before: Long?, after: Long?) = before != null && after != null && before > 0 && after > before

        /** The return hold must match the neutral hold, and both must see level gravity. */
        private fun holdFailure(base: Q, finish: Q, neutralTail: List<MotionReport>, returnTail: List<MotionReport>): SpatialCalibrationFailure? = when {
            (base.inverse() * finish).rotationVector().norm() > radians(5.0) -> SpatialCalibrationFailure.RETURN_NOT_NEUTRAL
            !levelGravity(neutralTail, base) -> SpatialCalibrationFailure.NEUTRAL_GRAVITY_INVALID
            !levelGravity(returnTail, finish) -> SpatialCalibrationFailure.RETURN_GRAVITY_INVALID
            else -> null
        }

        /** Horizontal rotation axes of the look-up samples that moved at least 8 degrees, or null when one tilts sideways. */
        private fun lookUpAxes(rotations: List<Q>, base: Q, z: V): List<V>? = rotations.mapNotNull { q ->
            val delta = (base.inverse() * q).rotationVector()
            val angle = delta.norm()
            if (angle < radians(8.0)) return@mapNotNull null
            val horizontal = delta - z * delta.dot(z)
            if (horizontal.norm() / angle < cos(radians(10.0))) return null
            horizontal.unit()
        }

        private fun meanHold(values: List<Q>): Q? {
            val first = values.first()
            val mean = values.map { if (first.dot(it) < 0) -it else it }.reduce(Q::plus).unit()
            return mean.takeIf { q -> values.all { (q.inverse() * it).rotationVector().norm() <= radians(2.0) } }
        }

        private fun settledTail(reports: List<MotionReport>): List<MotionReport> {
            val start = checkNotNull(reports.last().timestampNs) - 500_000_000L
            return reports.takeLastWhile { checkNotNull(it.timestampNs) >= start }
        }

        private fun levelGravity(reports: List<MotionReport>, attitude: Q): Boolean {
            val gravity = reports.map {
                val v = V.of(it.gravityRaw.map(Int::toDouble))
                if (v.norm() < 0.001) return false
                v.unit()
            }
            val mean = gravity.reduce(V::plus)
            if (mean.norm() < 0.001) return false
            val direction = mean.unit()
            return gravity.all { angleBetween(it, direction) <= radians(5.0) } &&
                angleBetween(attitude.rotate(direction), V(0.0, 0.0, -1.0)) <= radians(5.0)
        }
    }
}

internal data class SpatialPose(val timestampNs: Long, val discontinuity: Int,
    val rotationVector: List<Float>, val angularVelocity: List<Float>, val intervalNs: Long)

/** Canonical Q15 conversion for a caller-validated session; no stream, device, or audio ownership.
 * The caller retires this instance on role, transport or known reference changes. Invalid input after
 * startup also retires it. A forward 100–250 ms report gap only re-primes the rate; it emits no pose.
 * Rates are head-body finite differences over intervalNs (normally ~20 ms),
 * not instantaneous raw-gyro rates. No prediction or latency compensation is applied.
 * Output reference is the first valid head pose, keeping ordinary turns away from HID's pi branch.
 * A caller-validated runtime fences startup zero placeholders and requires a fresh nonzero pair;
 * standalone calibration/probe callers stay strict. The saved anatomical basis never changes.
 * shortcut: full relative half-turns still need hemisphere-aware prediction in Android's native renderer.
 */
internal class SpatialPoseConverter(private val profile: SpatialPoseProfile, private val discontinuity: Int,
    private val fenceStartupZeros: Boolean = false) {
    private val rawFromHead = Q.fromMatrix(List(3) { r -> List(3) { c -> profile.headFromRaw[c][r] } })
    private var previous: Q? = null
    private var initialHeadInverse: Q? = null
    private var previousTime = 0L
    private var previousCounter: Long? = null
    private var producedPose = false
    var invalidReason: String? = null
        private set
    var verifiedSensorGap = false
        private set
    val invalidated get() = invalidReason != null

    init { require(discontinuity in 0..255) }

    fun invalidate() { if (!invalidated) invalidReason = "session_changed" }

    fun accept(report: MotionReport): SpatialPose? {
        if (invalidated) return null
        // Sensor startup uses zero-filled attitude; it cannot establish a reference or fresh rate.
        val attitudeWasZero = report.attitudeQ15.all { it == 0 }
        if (previous == null && attitudeWasZero && !fenceStartupZeros) return null
        val time = report.timestampNs
        val raw = attitude(report)
        if (time == null || time <= 0) return retire("sensor_time_unavailable")
        if (raw == null) return retire("unsupported_attitude")
        val head = (raw * rawFromHead).unit()
        val old = previous ?: return startReference(report, time, head, attitudeWasZero)
        return advance(report, time, head, old, attitudeWasZero)
    }

    /** The first usable report after calibration becomes the reference; it yields no pose. */
    private fun startReference(report: MotionReport, time: Long, head: Q, attitudeWasZero: Boolean): SpatialPose? {
        profile.referenceTimestampNs?.let { reference ->
            if (time == reference && report.counter == profile.referenceCounter) return null
            if (time <= reference) return retire("sensor_time_changed")
            if (!counterFollows(profile.referenceCounter, report.counter, reference, time)) return retire("sensor_counter_changed")
        }
        initialHeadInverse = if (fenceStartupZeros && attitudeWasZero) null else head.inverse()
        previous = head; previousTime = time; previousCounter = report.counter
        return null
    }

    private fun advance(report: MotionReport, time: Long, head: Q, old: Q, attitudeWasZero: Boolean): SpatialPose? {
        val dt = time - previousTime
        if (dt <= 0) return retire("sensor_time_changed")
        if (!counterFollows(previousCounter, report.counter, previousTime, time)) return retire("sensor_counter_changed")
        if (dt >= 250_000_000L) {
            val beforeCounter = previousCounter; val afterCounter = report.counter
            val step = if (beforeCounter != null && afterCounter != null)
                motionCounterStep(beforeCounter, afterCounter, previousTime, time) else 0L
            // Retire this reference regardless. Only a settled, finite, clock-corroborated
            // missing-report gap may ask its owner for an entirely new output lease.
            verifiedSensorGap = producedPose && step in 1..0x7fffffffL &&
                abs(dt - step * 5_000_000L) <= 5_000_000L &&
                (old.inverse() * head).rotationVector().norm().isFinite()
            return retire("sensor_gap")
        }
        if (fenceStartupZeros && !producedPose && (attitudeWasZero || initialHeadInverse == null)) {
            // A restarted stream can deliver a retained attitude before its zero-filled startup.
            // Keep validating every placeholder's metadata, but none can establish a reference or
            // rate. The first subsequent nonzero report only primes; another must prove continuity.
            if (dt > 100_000_000L && (previousCounter == null || report.counter == null)) return retire("sensor_gap")
            initialHeadInverse = if (attitudeWasZero) null else head.inverse()
            previous = head; previousTime = time; previousCounter = report.counter
            return null
        }
        val omega = (old.inverse() * head).rotationVector() * (1e9 / dt)
        if (!omega.norm().isFinite() || omega.norm() > 32.0) return retire("reference_discontinuity")
        if (dt > 100_000_000L) {
            // Recovery needs evidence of missing reports, not a clock/counter/reference reset.
            if (previousCounter == null || report.counter == null) return retire("sensor_gap")
            previous = head; previousTime = time; previousCounter = report.counter
            return null
        }
        val rotation = (checkNotNull(initialHeadInverse) * head).rotationVector()
        if (!rotation.norm().isFinite()) return retire("unsupported_attitude")
        previous = head; previousTime = time; previousCounter = report.counter
        producedPose = true
        return SpatialPose(time, discontinuity, rotation.floats(), omega.floats(), dt)
    }

    private fun retire(reason: String): SpatialPose? { invalidReason = reason; return null }
}

private fun attitude(report: MotionReport): Q? {
    // Pro 3 captures prove whole-XYZ canonical sign flips at raw w=0; q and -q are one rotation.
    return report.quaternionQ15PositiveW()?.let { Q(it[0], it[1], it[2], it[3]) }
}

private fun counterFollows(before: Long?, after: Long?, beforeTimeNs: Long?, afterTimeNs: Long?): Boolean =
    before == null || after != null && motionCounterStep(before, after, beforeTimeNs, afterTimeNs) in 1..0x7fffffffL
private fun radians(degrees: Double) = degrees * Math.PI / 180
private fun angleBetween(a: V, b: V) = acos(a.dot(b).coerceIn(-1.0, 1.0))

private data class V(val x: Double, val y: Double, val z: Double) {
    operator fun plus(b: V) = V(x + b.x, y + b.y, z + b.z)
    operator fun minus(b: V) = V(x - b.x, y - b.y, z - b.z)
    operator fun times(n: Double) = V(x * n, y * n, z * n)
    fun dot(b: V) = x * b.x + y * b.y + z * b.z
    fun cross(b: V) = V(y * b.z - z * b.y, z * b.x - x * b.z, x * b.y - y * b.x)
    fun norm() = sqrt(dot(this))
    fun unit() = this * (1 / norm())
    fun list() = listOf(x, y, z)
    fun floats() = listOf(x.toFloat(), y.toFloat(), z.toFloat())
    companion object { fun of(v: List<Double>) = V(v[0], v[1], v[2]) }
}

private data class Q(val w: Double, val x: Double, val y: Double, val z: Double) {
    operator fun plus(b: Q) = Q(w + b.w, x + b.x, y + b.y, z + b.z)
    operator fun unaryMinus() = Q(-w, -x, -y, -z)
    fun dot(b: Q) = w*b.w + x*b.x + y*b.y + z*b.z
    operator fun times(b: Q) = Q(w*b.w - x*b.x - y*b.y - z*b.z,
        w*b.x + x*b.w + y*b.z - z*b.y, w*b.y - x*b.z + y*b.w + z*b.x,
        w*b.z + x*b.y - y*b.x + z*b.w)
    fun inverse() = Q(w, -x, -y, -z)
    fun unit(): Q {
        val n = sqrt(w*w + x*x + y*y + z*z)
        return Q(w/n, x/n, y/n, z/n)
    }
    fun rotate(v: V): V = (this * Q(0.0, v.x, v.y, v.z) * inverse()).let { V(it.x, it.y, it.z) }
    fun rotationVector(): V {
        val sign = if (w < 0) -1.0 else 1.0
        val v = V(x * sign, y * sign, z * sign)
        val n = v.norm()
        return v * if (n < 1e-10) 2.0 else 2 * atan2(n, w * sign) / n
    }
    companion object {
        fun fromMatrix(m: List<List<Double>>): Q {
            val trace = m[0][0] + m[1][1] + m[2][2]
            if (trace > 0) {
                val s = sqrt(trace + 1) * 2
                return Q(s/4, (m[2][1]-m[1][2])/s, (m[0][2]-m[2][0])/s, (m[1][0]-m[0][1])/s).unit()
            }
            val i = (0..2).maxBy { m[it][it] }; val j = (i+1)%3; val k = (i+2)%3
            val s = sqrt(1 + m[i][i] - m[j][j] - m[k][k]) * 2
            val v = DoubleArray(3)
            v[i] = s/4; v[j] = (m[i][j]+m[j][i])/s; v[k] = (m[i][k]+m[k][i])/s
            return Q((m[k][j]-m[j][k])/s, v[0], v[1], v[2]).unit()
        }
    }
}
