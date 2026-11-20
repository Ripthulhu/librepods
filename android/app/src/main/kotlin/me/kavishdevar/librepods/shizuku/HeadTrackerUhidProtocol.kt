/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.shizuku

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Standard Android pose values only. This codec never interprets an earbud's raw sensor units. */
internal class HeadTrackerUhidProtocol(address: String) {
    private var started = false
    private var opened = false
    private var identityRead = false
    private var controlsRead = false
    private var controls = 0
    val reporting get() = started && opened && identityRead && controls and ACTIVE_CONTROLS == ACTIVE_CONTROLS
    val halHandshake get() = identityRead && controlsRead
    val intervalNanos get() = MINIMUM_REPORT_INTERVAL_NANOS +
        (controls shr REPORT_INTERVAL_SHIFT) * REPORT_INTERVAL_RANGE_NANOS / REPORT_INTERVAL_STEPS
    private val identity = ByteArray(IDENTITY_REPORT_BYTES).apply {
        this[0] = IDENTITY_REPORT_ID.toByte()
        SENSOR_DESCRIPTION.toByteArray(Charsets.US_ASCII).copyInto(this, IDENTITY_DESCRIPTION_OFFSET)
        this[IDENTITY_BLUETOOTH_MARKER_OFFSET] = 'B'.code.toByte(); this[IDENTITY_BLUETOOTH_MARKER_OFFSET + 1] = 'T'.code.toByte()
        A2dpRecoveryGuard(address).address.split(':').forEachIndexed { index, octet ->
            this[IDENTITY_BLUETOOTH_ADDRESS_OFFSET + index] = octet.toInt(16).toByte()
        }
    }

    fun create(): ByteArray = event(CREATE2).apply {
        "LibrePods head tracker".toByteArray(Charsets.US_ASCII).copyInto(this, CREATE_NAME_OFFSET)
        "librepods/head-tracker".toByteArray(Charsets.US_ASCII).copyInto(this, CREATE_PHYSICAL_PATH_OFFSET)
        little(this).putShort(CREATE_DESCRIPTOR_SIZE_OFFSET, descriptor.size.toShort())
            .putShort(CREATE_BUS_OFFSET, BUS_VIRTUAL).putInt(CREATE_VERSION_OFFSET, DEVICE_VERSION)
        descriptor.copyInto(this, CREATE_DESCRIPTOR_OFFSET)
    }

    fun handle(bytes: ByteArray): ByteArray? {
        require(bytes.size == EVENT_BYTES) { "short_uhid_event" }
        val input = little(bytes)
        return when (input.getInt(0)) {
            START -> {
                check(input.getLong(START_DEVICE_FLAGS_OFFSET) and REQUIRED_NUMBERED_REPORTS == REQUIRED_NUMBERED_REPORTS) {
                    "unnumbered_uhid_reports"
                }
                started = true; null
            }
            STOP -> { started = false; null }
            OPEN -> { opened = true; null }
            CLOSE -> { opened = false; null }
            GET_REPORT -> replyToGetReport(bytes, input)
            SET_REPORT -> replyToSetReport(bytes, input)
            else -> null
        }
    }

    private fun replyToGetReport(bytes: ByteArray, input: ByteBuffer): ByteArray {
        val number = bytes[REPORT_NUMBER_OFFSET].toInt() and 255
        val feature = bytes[REPORT_TYPE_OFFSET].toInt() == FEATURE_REPORT_TYPE
        val payload = when {
            feature && number == CONTROLS_REPORT_ID -> byteArrayOf(CONTROLS_REPORT_ID.toByte(), controls.toByte()).also { controlsRead = true }
            feature && number == IDENTITY_REPORT_ID -> identity.also { identityRead = true }
            else -> null
        }
        return event(GET_REPORT_REPLY).apply {
            little(this).putInt(REQUEST_ID_OFFSET, input.getInt(REQUEST_ID_OFFSET))
                .putShort(REPLY_ERROR_OFFSET, if (payload == null) INPUT_OUTPUT_ERROR else REPLY_SUCCESS)
                .putShort(REPORT_SIZE_OFFSET, (payload?.size ?: 0).toShort())
            payload?.copyInto(this, REPORT_DATA_OFFSET)
        }
    }

    private fun replyToSetReport(bytes: ByteArray, input: ByteBuffer): ByteArray {
        val valid = bytes[REPORT_NUMBER_OFFSET].toInt() == CONTROLS_REPORT_ID &&
            bytes[REPORT_TYPE_OFFSET].toInt() == FEATURE_REPORT_TYPE &&
            input.getShort(REPORT_SIZE_OFFSET).toInt() == CONTROLS_REPORT_BYTES &&
            bytes[REPORT_DATA_OFFSET].toInt() == CONTROLS_REPORT_ID
        if (valid) controls = bytes[REPORT_DATA_OFFSET + 1].toInt() and 255
        return event(SET_REPORT_REPLY).apply {
            little(this).putInt(REQUEST_ID_OFFSET, input.getInt(REQUEST_ID_OFFSET))
                .putShort(REPLY_ERROR_OFFSET, if (valid) REPLY_SUCCESS else INPUT_OUTPUT_ERROR)
        }
    }

    companion object {
        const val EVENT_BYTES = 4380
        const val FRAME_BYTES = 40
        const val FRAME_MAGIC = 0x31505448 // ASCII HTP1, little endian.
        const val FRESH_NANOS = 250_000_000L
        // shortcut: bridge short bursts with constant velocity; revisit when transport delivers steadily.
        const val MAX_EXTRAPOLATION_NANOS = 40_000_000L
        const val STARTUP_NANOS = 5_000_000_000L
        // struct uhid_event types from linux/uhid.h.
        const val START = 2; const val STOP = 3; const val OPEN = 4; const val CLOSE = 5
        const val GET_REPORT = 9; const val GET_REPORT_REPLY = 10; const val CREATE2 = 11
        const val INPUT2 = 12; const val SET_REPORT = 13; const val SET_REPORT_REPLY = 14
        private const val ROTATION_MAX = 3.14159265
        private const val ANGULAR_VELOCITY_MAX = 32.0
        private const val POSE_LOGICAL_MAXIMUM = 32767
        // ROTATION_MAX in the descriptor's unit of 10^-8 radians.
        private const val ROTATION_PHYSICAL_MAXIMUM = 314_159_265
        private const val POSE_VALUES = 6
        private const val DISCONTINUITY_MAXIMUM = 255
        // Float components can round a pi-length vector slightly above pi.
        private val ROTATION_TOLERANCE = Math.ulp(Math.PI.toFloat()).toDouble()

        // Byte offsets inside struct uhid_event: a 32-bit type, then the packed request of that type.
        private const val CREATE_NAME_OFFSET = 4
        private const val CREATE_PHYSICAL_PATH_OFFSET = 132
        private const val CREATE_DESCRIPTOR_SIZE_OFFSET = 260
        private const val CREATE_BUS_OFFSET = 262
        private const val CREATE_VERSION_OFFSET = 272
        private const val CREATE_DESCRIPTOR_OFFSET = 280
        private const val START_DEVICE_FLAGS_OFFSET = 4
        private const val REQUEST_ID_OFFSET = 4
        private const val REPORT_NUMBER_OFFSET = 8
        private const val REPORT_TYPE_OFFSET = 9
        private const val REPLY_ERROR_OFFSET = 8
        // GET_REPORT_REPLY and SET_REPORT share the size and data offsets.
        private const val REPORT_SIZE_OFFSET = 10
        private const val REPORT_DATA_OFFSET = 12
        private const val INPUT_SIZE_OFFSET = 4
        private const val INPUT_DATA_OFFSET = 6

        private const val BUS_VIRTUAL: Short = 6
        private const val DEVICE_VERSION = 1
        // UHID_DEV_NUMBERED_FEATURE_REPORTS | UHID_DEV_NUMBERED_INPUT_REPORTS.
        private const val REQUIRED_NUMBERED_REPORTS = 5L
        private const val FEATURE_REPORT_TYPE = 0
        private const val REPLY_SUCCESS: Short = 0
        private const val INPUT_OUTPUT_ERROR: Short = 5 // EIO

        // Reports declared by the descriptor below.
        private const val CONTROLS_REPORT_ID = 1
        private const val IDENTITY_REPORT_ID = 2
        // The pose input report shares report id 1 with the controls feature report.
        private const val POSE_REPORT_ID = CONTROLS_REPORT_ID
        private const val CONTROLS_REPORT_BYTES = 2
        private const val POSE_REPORT_BYTES: Short = 14
        private const val POSE_DISCONTINUITY_OFFSET = INPUT_DATA_OFFSET + 1 + POSE_VALUES * 2
        // Controls byte: bit 0 reporting state, bit 1 power state, bits 2 to 7 report interval.
        private const val ACTIVE_CONTROLS = 3
        private const val REPORT_INTERVAL_SHIFT = 2
        private const val MINIMUM_REPORT_INTERVAL_NANOS = 10_000_000L
        private const val REPORT_INTERVAL_RANGE_NANOS = 90_000_000L
        private const val REPORT_INTERVAL_STEPS = 63
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        // Identity report: report id, sensor description, then the 16-byte persistent unique id,
        // whose last eight bytes are "BT" and the Bluetooth address.
        private const val IDENTITY_REPORT_BYTES = 40
        private const val SENSOR_DESCRIPTION = "#AndroidHeadTracker#1.0"
        private const val IDENTITY_DESCRIPTION_OFFSET = 1
        private const val PERSISTENT_UNIQUE_ID_BYTES = 16
        private const val IDENTITY_BLUETOOTH_MARKER_OFFSET = 32
        private const val IDENTITY_BLUETOOTH_ADDRESS_OFFSET = 34

        data class Pose(val timestampNanos: Long, val discontinuity: Int, val values: FloatArray)

        fun little(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun event(type: Int): ByteArray = ByteArray(EVENT_BYTES).also { little(it).putInt(0, type) }

        fun decodePose(bytes: ByteArray, now: Long): Pose {
            require(bytes.size == FRAME_BYTES) { "pose_frame_size" }
            val input = little(bytes)
            require(input.int == FRAME_MAGIC) { "pose_frame_version" }
            val discontinuity = input.int
            val timestamp = input.long
            val values = FloatArray(POSE_VALUES) { input.float }
            require(discontinuity in 0..DISCONTINUITY_MAXIMUM && timestamp > 0 && timestamp <= now) { "pose_frame_metadata" }
            require(values.all { it.isFinite() }) { "nonfinite_pose" }
            fun norm(first: Int) = sqrt((first until first + 3).sumOf { values[it].toDouble() * values[it] })
            require(norm(0) <= Math.PI + ROTATION_TOLERANCE && norm(3) <= ANGULAR_VELOCITY_MAX) { "pose_out_of_range" }
            return Pose(timestamp, discontinuity, values)
        }

        /** Extrapolate only elapsed host age, preserving the measurement's original freshness. */
        fun poseAtHostTime(pose: Pose, atNs: Long): Pose? {
            if (atNs < pose.timestampNanos) return null
            val age = atNs - pose.timestampNanos
            if (age > MAX_EXTRAPOLATION_NANOS) return null
            val values = pose.values
            if (age == 0L || (3..5).all { values[it] == 0f }) return pose
            fun quaternion(x: Double, y: Double, z: Double): DoubleArray {
                val angle = sqrt(x*x + y*y + z*z)
                val scale = if (angle < 1e-12) 0.5 else sin(angle / 2) / angle
                return doubleArrayOf(cos(angle / 2), x*scale, y*scale, z*scale)
            }
            val q = quaternion(values[0].toDouble(), values[1].toDouble(), values[2].toDouble())
            val seconds = age / 1e9
            val d = quaternion(values[3]*seconds, values[4]*seconds, values[5]*seconds)
            // Body angular velocity is intrinsic: right-multiply the measured orientation.
            val product = doubleArrayOf(
                q[0]*d[0] - q[1]*d[1] - q[2]*d[2] - q[3]*d[3],
                q[0]*d[1] + q[1]*d[0] + q[2]*d[3] - q[3]*d[2],
                q[0]*d[2] - q[1]*d[3] + q[2]*d[0] + q[3]*d[1],
                q[0]*d[3] + q[1]*d[2] - q[2]*d[1] + q[3]*d[0],
            )
            val norm = sqrt(product.sumOf { it*it })
            val hemisphere = if (product[0] < 0) -1.0 else 1.0
            for (index in product.indices) product[index] *= hemisphere / norm
            val vectorNorm = sqrt((1..3).sumOf { product[it]*product[it] })
            val scale = if (vectorNorm < 1e-12) 2.0 else 2 * atan2(vectorNorm, product[0]) / vectorNorm
            val advanced = values.copyOf()
            for (index in 0..2) advanced[index] = (product[index + 1] * scale).toFloat()
            if ((0..2).all { advanced[it] == values[it] }) return pose
            return Pose(pose.timestampNanos, pose.discontinuity, advanced)
        }

        fun input(pose: Pose, destination: ByteArray = event(INPUT2)): ByteArray = destination.apply {
            require(size == EVENT_BYTES)
            little(this).putInt(0, INPUT2)
            val output = little(this).putShort(INPUT_SIZE_OFFSET, POSE_REPORT_BYTES)
            this[INPUT_DATA_OFFSET] = POSE_REPORT_ID.toByte()
            pose.values.forEachIndexed { index, value ->
                val scale = if (index < 3) ROTATION_MAX else ANGULAR_VELOCITY_MAX
                val encoded = (value.toDouble() * POSE_LOGICAL_MAXIMUM / scale).roundToInt()
                    .coerceIn(-POSE_LOGICAL_MAXIMUM, POSE_LOGICAL_MAXIMUM)
                output.putShort(INPUT_DATA_OFFSET + 1 + index * 2, encoded.toShort())
            }
            this[POSE_DISCONTINUITY_OFFSET] = pose.discontinuity.toByte()
        }

        // Byte-for-byte Android v1 descriptor already proven by the standalone synthetic probe.
        private val descriptor = hidDescriptor {
            item(USAGE_PAGE, SENSORS_USAGE_PAGE)
            item(USAGE, CUSTOM_SENSOR_USAGE)
            item(COLLECTION, APPLICATION_COLLECTION)
            // Feature report 2: sensor description and persistent unique id, both constant.
            item(REPORT_ID, IDENTITY_REPORT_ID)
            item(USAGE_TWO_BYTES, SENSOR_DESCRIPTION_USAGE)
            item(LOGICAL_MINIMUM, 0); item(LOGICAL_MAXIMUM_TWO_BYTES, 255)
            item(REPORT_SIZE, 8); item(REPORT_COUNT, SENSOR_DESCRIPTION.length)
            item(FEATURE, CONSTANT_VARIABLE)
            item(USAGE_TWO_BYTES, PERSISTENT_UNIQUE_ID_USAGE)
            item(REPORT_COUNT, PERSISTENT_UNIQUE_ID_BYTES)
            item(FEATURE, CONSTANT_VARIABLE)
            // Feature report 1: the host's controls byte.
            item(REPORT_ID, CONTROLS_REPORT_ID)
            item(USAGE_TWO_BYTES, REPORTING_STATE_USAGE)
            item(LOGICAL_MINIMUM, 0); item(LOGICAL_MAXIMUM, 1)
            item(REPORT_SIZE, 1); item(REPORT_COUNT, 1)
            item(COLLECTION, LOGICAL_COLLECTION)
            item(USAGE_TWO_BYTES, REPORTING_STATE_NO_EVENTS_USAGE)
            item(USAGE_TWO_BYTES, REPORTING_STATE_ALL_EVENTS_USAGE)
            item(FEATURE, DATA_ARRAY)
            item(END_COLLECTION)
            item(USAGE_TWO_BYTES, POWER_STATE_USAGE)
            item(COLLECTION, LOGICAL_COLLECTION)
            item(USAGE_TWO_BYTES, POWER_STATE_POWER_OFF_USAGE)
            item(USAGE_TWO_BYTES, POWER_STATE_FULL_POWER_USAGE)
            item(FEATURE, DATA_ARRAY)
            item(END_COLLECTION)
            item(USAGE_TWO_BYTES, REPORT_INTERVAL_USAGE)
            item(LOGICAL_MINIMUM, 0); item(LOGICAL_MAXIMUM, REPORT_INTERVAL_STEPS)
            item(PHYSICAL_MINIMUM, (MINIMUM_REPORT_INTERVAL_NANOS / NANOS_PER_MILLISECOND).toInt())
            item(PHYSICAL_MAXIMUM, ((MINIMUM_REPORT_INTERVAL_NANOS + REPORT_INTERVAL_RANGE_NANOS) / NANOS_PER_MILLISECOND).toInt())
            item(REPORT_SIZE, 6); item(REPORT_COUNT, 1)
            item(UNIT_TWO_BYTES, SECONDS_UNIT); item(UNIT_EXPONENT, unitExponent(-3))
            item(FEATURE, DATA_VARIABLE)
            // Input report 1: rotation vector in radians, angular velocity in radians per second,
            // and the discontinuity count.
            item(USAGE_TWO_BYTES, ROTATION_VECTOR_USAGE)
            item(LOGICAL_MINIMUM_TWO_BYTES, -POSE_LOGICAL_MAXIMUM); item(LOGICAL_MAXIMUM_TWO_BYTES, POSE_LOGICAL_MAXIMUM)
            item(PHYSICAL_MINIMUM_FOUR_BYTES, -ROTATION_PHYSICAL_MAXIMUM)
            item(PHYSICAL_MAXIMUM_FOUR_BYTES, ROTATION_PHYSICAL_MAXIMUM)
            item(UNIT, NO_UNIT); item(UNIT_EXPONENT, unitExponent(-8))
            item(REPORT_SIZE, 16); item(REPORT_COUNT, 3)
            item(INPUT, DATA_VARIABLE)
            item(USAGE_TWO_BYTES, ANGULAR_VELOCITY_USAGE)
            item(PHYSICAL_MINIMUM, -ANGULAR_VELOCITY_MAX.toInt()); item(PHYSICAL_MAXIMUM, ANGULAR_VELOCITY_MAX.toInt())
            item(UNIT_EXPONENT, unitExponent(0))
            item(INPUT, DATA_VARIABLE)
            item(USAGE_TWO_BYTES, DISCONTINUITY_COUNT_USAGE)
            item(LOGICAL_MINIMUM_TWO_BYTES, 0); item(LOGICAL_MAXIMUM_TWO_BYTES, DISCONTINUITY_MAXIMUM)
            item(PHYSICAL_MINIMUM, 0); item(PHYSICAL_MAXIMUM, 0)
            item(REPORT_SIZE, 8); item(REPORT_COUNT, 1)
            item(INPUT, DATA_VARIABLE)
            item(END_COLLECTION)
        }
    }
}

/** Appends HID 1.11 short items: the prefix byte's low two bits select 0, 1, 2 or 4 little-endian data bytes. */
private class HidDescriptorBuilder {
    private val bytes = ArrayList<Byte>()

    fun item(prefix: Int, data: Int = 0) {
        bytes += prefix.toByte()
        val dataBytes = when (prefix and SHORT_ITEM_SIZE_MASK) { 0 -> 0; 1 -> 1; 2 -> 2; else -> 4 }
        repeat(dataBytes) { bytes += (data shr (Byte.SIZE_BITS * it)).toByte() }
    }

    /** Unit exponents are signed four-bit values. */
    fun unitExponent(power: Int) = power and 0x0f

    fun build() = bytes.toByteArray()
}

private fun hidDescriptor(items: HidDescriptorBuilder.() -> Unit) = HidDescriptorBuilder().apply(items).build()

private const val SHORT_ITEM_SIZE_MASK = 0x03

// Short item prefixes (tag, type and data size) from the HID 1.11 specification.
private const val USAGE_PAGE = 0x05
private const val USAGE = 0x09
private const val USAGE_TWO_BYTES = 0x0a
private const val LOGICAL_MINIMUM = 0x15
private const val LOGICAL_MINIMUM_TWO_BYTES = 0x16
private const val LOGICAL_MAXIMUM = 0x25
private const val LOGICAL_MAXIMUM_TWO_BYTES = 0x26
private const val PHYSICAL_MINIMUM = 0x35
private const val PHYSICAL_MINIMUM_FOUR_BYTES = 0x37
private const val PHYSICAL_MAXIMUM = 0x45
private const val PHYSICAL_MAXIMUM_FOUR_BYTES = 0x47
private const val UNIT_EXPONENT = 0x55
private const val UNIT = 0x65
private const val UNIT_TWO_BYTES = 0x66
private const val REPORT_SIZE = 0x75
private const val INPUT = 0x81
private const val REPORT_ID = 0x85
private const val REPORT_COUNT = 0x95
private const val COLLECTION = 0xa1
private const val FEATURE = 0xb1
private const val END_COLLECTION = 0xc0

// Main item data flags.
private const val DATA_ARRAY = 0x00
private const val DATA_VARIABLE = 0x02
private const val CONSTANT_VARIABLE = 0x03
private const val APPLICATION_COLLECTION = 0x01
private const val LOGICAL_COLLECTION = 0x02
private const val NO_UNIT = 0x00
// SI linear system, time to the power of one.
private const val SECONDS_UNIT = 0x1001

// Sensors usage page (HID Usage Tables) as required by Android's head tracker HID protocol.
private const val SENSORS_USAGE_PAGE = 0x20
private const val CUSTOM_SENSOR_USAGE = 0xe1
private const val PERSISTENT_UNIQUE_ID_USAGE = 0x0302
private const val SENSOR_DESCRIPTION_USAGE = 0x0308
private const val REPORT_INTERVAL_USAGE = 0x030e
private const val REPORTING_STATE_USAGE = 0x0316
private const val POWER_STATE_USAGE = 0x0319
// Android maps custom values 1, 2 and 3 to rotation vector, angular velocity and discontinuity count.
private const val ROTATION_VECTOR_USAGE = 0x0544
private const val ANGULAR_VELOCITY_USAGE = 0x0545
private const val DISCONTINUITY_COUNT_USAGE = 0x0546
private const val REPORTING_STATE_NO_EVENTS_USAGE = 0x0840
private const val REPORTING_STATE_ALL_EVENTS_USAGE = 0x0841
private const val POWER_STATE_FULL_POWER_USAGE = 0x0851
private const val POWER_STATE_POWER_OFF_USAGE = 0x0855

/** Idle hosts have no freshness deadline. An enabled host must obtain a new, post-enable pose. */
internal class HeadTrackerPoseGate {
    private var enabled = false
    private var enabledAt = 0L
    private var lastTimestamp = 0L
    private var latest: HeadTrackerUhidProtocol.Companion.Pose? = null
    val hasPose get() = latest != null
    val deadline get() = if (!enabled) Long.MAX_VALUE else latest?.timestampNanos?.plus(ACTIVE_GAP_NANOS)
        ?: (enabledAt + HeadTrackerUhidProtocol.STARTUP_NANOS)

    fun reporting(value: Boolean, now: Long) {
        if (!value || value != enabled) { latest = null; enabledAt = now }
        enabled = value
    }

    fun accept(pose: HeadTrackerUhidProtocol.Companion.Pose, now: Long): Boolean {
        require(pose.timestampNanos > lastTimestamp) { "nonincreasing_pose_timestamp" }
        lastTimestamp = pose.timestampNanos
        if (!enabled || pose.timestampNanos < enabledAt) return false
        require(now - pose.timestampNanos <= HeadTrackerUhidProtocol.FRESH_NANOS) { "stale_pose" }
        checkFresh(now)
        latest = pose
        return true
    }

    fun checkFresh(now: Long) { check(now <= deadline) { if (latest == null) "pose_startup_timeout" else "pose_stale" } }
    fun pose(now: Long): HeadTrackerUhidProtocol.Companion.Pose? {
        checkFresh(now)
        return latest?.takeIf { now - it.timestampNanos <= HeadTrackerUhidProtocol.FRESH_NANOS }
    }
    fun input(now: Long): ByteArray? = pose(now)?.let { HeadTrackerUhidProtocol.input(it) }

    companion object {
        // A delayed delivery can resume the same sensor without extending the accepted pose age.
        const val ACTIVE_GAP_NANOS = 400_000_000L
    }
}

/** Keep the host's periodic cadence without accumulating each poll's rounding delay or catch-up bursts. */
internal class HeadTrackerReportPacer {
    private var enabled = false
    private var interval = 10_000_000L
    private var lastSlot: Long? = null
    var deadline = Long.MAX_VALUE
        private set

    fun reporting(value: Boolean, intervalNanos: Long, now: Long) {
        require(intervalNanos in 10_000_000L..100_000_000L)
        if (!value) { lastSlot = null; deadline = Long.MAX_VALUE }
        else if (!enabled) deadline = now
        else if (interval != intervalNanos) deadline = lastSlot?.let { it + intervalNanos } ?: now
        enabled = value; interval = intervalNanos
    }

    fun advance(now: Long) {
        check(enabled && now >= deadline)
        val next = deadline + interval
        deadline = if (next <= now) now + interval else next
        lastSlot = now
    }
}

/** Created only for a cached stats read, never for an individual pose. */
internal data class HeadTrackerTimingRange(
    val count: Long = 0, val minNanos: Long = 0, val meanNanos: Double = 0.0, val maxNanos: Long = 0,
)

internal data class HeadTrackerSinkStats(
    val acceptedPoses: Long = 0, val hidInputs: Long = 0, val heldRepeats: Long = 0,
    val intervalCount: Long = 0, val intervalMinNanos: Long = 0, val intervalMeanNanos: Double = 0.0,
    val intervalMaxNanos: Long = 0, val sourceAgeCount: Long = 0, val sourceAgeMinNanos: Long = 0,
    val sourceAgeMeanNanos: Double = 0.0, val sourceAgeMaxNanos: Long = 0,
    val sourceReuses: Long = 0, val extrapolatedInputs: Long = 0, val skippedInputs: Long = 0,
    val maxHidGapAtNanos: Long = 0, val sourceAgeAtMaxHidGapNanos: Long = 0,
    val previousSourceTimestampAtMaxHidGapNanos: Long = 0, val sourceTimestampAtMaxHidGapNanos: Long = 0,
    val newSourceAge: HeadTrackerTimingRange = HeadTrackerTimingRange(),
    val reusedSourceAge: HeadTrackerTimingRange = HeadTrackerTimingRange(),
    val pipeFrameHostAge: HeadTrackerTimingRange = HeadTrackerTimingRange(),
    val deadlineLateness: HeadTrackerTimingRange = HeadTrackerTimingRange(),
    val overdueSlots: Long = 0, val missedPeriods: Long = 0, val timingClockAnomalies: Long = 0,
)

/** Constant-space host timing only; intervals exclude host-disabled windows. */
internal class HeadTrackerSinkMetrics {
    private var accepted = 0L
    private var inputs = 0L
    private var repeats = 0L
    private var reuses = 0L
    private var extrapolated = 0L
    private var skipped = 0L
    private var lastPoseTimestamp: Long? = null
    private var lastInput: Long? = null
    private var maxGapAt = 0L
    private var sourceAgeAtMaxGap = 0L
    private var previousSourceAtMaxGap = 0L
    private var sourceAtMaxGap = 0L
    private var overdue = 0L
    private var missed = 0L
    private var clockAnomalies = 0L
    private class Range {
        var count = 0L; var min = 0L; var mean = 0.0; var max = 0L
        fun add(value: Long) {
            require(value >= 0)
            if (count++ == 0L) { min = value; max = value } else { min = minOf(min, value); max = maxOf(max, value) }
            mean += (value.toDouble() - mean) / count
        }
        fun snapshot() = HeadTrackerTimingRange(count, min, mean, max)
    }
    private val intervals = Range()
    private val ages = Range()
    private val newAges = Range()
    private val reusedAges = Range()
    private val pipeAges = Range()
    private val lateness = Range()

    private fun validHostAge(timestamp: Long, now: Long): Boolean {
        if (timestamp > 0 && now >= timestamp) return true
        ++clockAnomalies
        return false
    }

    private fun scheduled(deadline: Long, now: Long, interval: Long) {
        if (deadline == 0L && interval == 0L) return // Existing callers need not supply scheduling data.
        if (interval !in 10_000_000L..100_000_000L || !validHostAge(deadline, now)) {
            if (interval !in 10_000_000L..100_000_000L) ++clockAnomalies
            return
        }
        val delay = now - deadline
        lateness.add(delay)
        val periods = delay / interval
        if (periods > 0) { ++overdue; missed += periods }
    }

    @Synchronized fun accepted(poseTimestamp: Long = 0, receivedAt: Long = 0) {
        ++accepted
        // This is parsed-callback timestamp to pipe receipt, not socket or sensor transit time.
        if (poseTimestamp == 0L && receivedAt == 0L) return
        if (validHostAge(poseTimestamp, receivedAt)) pipeAges.add(receivedAt - poseTimestamp)
    }
    @Synchronized fun skipped(deadline: Long = 0, now: Long = 0, interval: Long = 0) {
        ++skipped
        if (lastInput != null) scheduled(deadline, now, interval)
    }
    @Synchronized fun idle() { lastInput = null }
    @Synchronized fun input(poseTimestamp: Long, now: Long, extrapolated: Boolean = false,
        deadline: Long = 0, interval: Long = 0) {
        require(poseTimestamp <= now)
        // Before the first fresh input, the pacer deadline still names HAL enable/startup.
        if (lastInput != null) scheduled(deadline, now, interval)
        if (validHostAge(poseTimestamp, now)) {
            (if (lastPoseTimestamp == poseTimestamp) reusedAges else newAges).add(now - poseTimestamp)
        }
        lastInput?.let {
            val gap = now - it
            val maximum = intervals.count == 0L || gap > intervals.max
            intervals.add(gap)
            if (maximum) {
                maxGapAt = now
                sourceAgeAtMaxGap = now - poseTimestamp
                previousSourceAtMaxGap = checkNotNull(lastPoseTimestamp)
                sourceAtMaxGap = poseTimestamp
            }
        }
        ages.add(now - poseTimestamp)
        if (lastPoseTimestamp == poseTimestamp) {
            ++reuses
            if (!extrapolated) ++repeats
        }
        if (extrapolated) ++this.extrapolated
        ++inputs; lastInput = now; lastPoseTimestamp = poseTimestamp
    }
    @Synchronized fun snapshot() = HeadTrackerSinkStats(accepted, inputs, repeats,
        intervals.count, intervals.min, intervals.mean, intervals.max,
        ages.count, ages.min, ages.mean, ages.max, reuses, extrapolated, skipped,
        maxGapAt, sourceAgeAtMaxGap, previousSourceAtMaxGap, sourceAtMaxGap,
        newAges.snapshot(), reusedAges.snapshot(), pipeAges.snapshot(), lateness.snapshot(), overdue, missed, clockAnomalies)
}

/** Confirmation belongs to one exact selected address; a stuck native read cannot renew it. */
internal class HeadTrackerRouteCache(private val address: String, private val now: () -> Long) {
    @Volatile private var checkedAt = Long.MIN_VALUE
    fun confirm() { checkedAt = now() }
    fun clear() { checkedAt = Long.MIN_VALUE }
    fun current(selected: String): Boolean {
        val checked = checkedAt
        return selected == address && checked != Long.MIN_VALUE && now() - checked in 0..500
    }
}
