/* Copyright (C) 2026 LibrePods contributors; GPL-3.0-or-later. */
package me.kavishdevar.librepods.audio

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.data.apple.BuddyState
import me.kavishdevar.librepods.core.data.apple.MotionReport
import me.kavishdevar.librepods.core.data.apple.motionCounterStep
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.utils.HeadTracking
import java.io.File
import java.io.Writer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

internal data class SpatialMotionCaptureRequest(val expectedMac: String, val phase: String, val durationSeconds: Int = 10) {
    init {
        MacAddress(expectedMac)
        require(phase in setOf("neutral", "yaw", "pitch", "roll")) { "Phase must be neutral, yaw, pitch, or roll." }
        require(durationSeconds in 1..10) { "Capture duration must be between 1 and 10 seconds." }
    }
}

internal data class SpatialMotionCaptureResult(val file: File?, val samples: Int, val reason: String)

/** Explicit debug capture of uncalibrated fields; it never converts or submits a pose. */
internal class SpatialMotionCapture(
    private val directory: File,
    private val motion: Flow<MotionReport?> = HeadTracking.motion,
    private val arrivalNs: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    suspend fun capture(
        request: SpatialMotionCaptureRequest,
        device: AppleDevice,
        isCurrent: () -> Boolean,
        onFile: (File) -> Unit = {},
    ): SpatialMotionCaptureResult = withContext(Dispatchers.IO) {
        if (!capturing.compareAndSet(false, true)) return@withContext SpatialMotionCaptureResult(null, 0, "Another motion capture is running.")
        CaptureRun(request, device, isCurrent, onFile).execute()
    }

    /** One capture: owns the motion request and the output file until it ends. */
    private inner class CaptureRun(
        private val request: SpatialMotionCaptureRequest,
        private val device: AppleDevice,
        private val isCurrent: () -> Boolean,
        private val onFile: (File) -> Unit,
    ) {
        private val owner = Any()
        private var file: File? = null
        private var samples = 0
        private var previousCounter: Long? = null
        private var previousDeviceTime: Long? = null

        suspend fun execute(): SpatialMotionCaptureResult = try {
            check(device.macAddress.value.equals(request.expectedMac, ignoreCase = true)) { "The requested AirPods do not match." }
            check(isCurrent()) { "The expected AirPods are not connected." }
            val leftIsPrimary = device.state.value.leftIsPrimary
            check(device.startHeadTracking(owner)) { "Motion capture is unavailable." }
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create the private capture directory." }
            val output = File.createTempFile("${request.phase}-", ".jsonl", directory)
            file = output
            val reason = record(output, leftIsPrimary)
            SpatialMotionCaptureResult(output, samples, reason)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (expectedFailure: Exception) {
            // Every other failure ends this debug capture and is reported as its result.
            SpatialMotionCaptureResult(file, samples, expectedFailure.message ?: "Motion capture failed.")
        } finally {
            try { device.stopHeadTracking(owner) } finally { capturing.set(false) }
        }

        /** Writes the header and samples; returns why recording ended. */
        private suspend fun record(output: File, leftIsPrimary: Boolean): String {
            val metadata = device.metadata.value
            return output.bufferedWriter().use { writer ->
                val startedArrival = arrivalNs()
                check(startedArrival > 0) { "Host arrival clock must be positive and increasing." }
                writer.appendLine(JSONObject().apply {
                    put("type", "capture")
                    put("expected_mac", request.expectedMac.uppercase(Locale.ROOT))
                    put("phase", request.phase)
                    put("duration_seconds", request.durationSeconds)
                    put("model_number", metadata.modelNumber)
                    put("firmware", metadata.version1)
                    put("left_is_primary", leftIsPrimary)
                    put("started_arrival_ns", startedArrival)
                }.toString())
                onFile(output)
                val finished = withTimeoutOrNull(request.durationSeconds * 1_000L) {
                    recordSamples(writer, leftIsPrimary, startedArrival)
                    true
                } ?: false
                if (finished) "Sample limit reached." else "Capture duration complete."
            }
        }

        private suspend fun recordSamples(writer: Writer, leftIsPrimary: Boolean, startedArrival: Long) {
            var previousArrival = startedArrival
            coroutineScope {
                val guard = launch {
                    while (true) { requireCurrent(leftIsPrimary); delay(100) }
                }
                try {
                    // StateFlow's first value may belong to an earlier stream.
                    motion.drop(1).filterNotNull().filter { isNewReport(it) }.take(MAX_SAMPLES).collect { report ->
                        requireCurrent(leftIsPrimary)
                        val arrived = arrivalNs()
                        check(arrived > previousArrival) { "Host arrival clock must be positive and increasing." }
                        previousArrival = arrived
                        writer.appendLine(motionCaptureSample(report, arrived).toString())
                        samples++
                    }
                } finally { guard.cancel() }
            }
        }

        private fun requireCurrent(leftIsPrimary: Boolean) {
            if (device.state.value.leftIsPrimary != leftIsPrimary)
                throw CaptureStopped("Stopped: primary bud changed.")
            if (!isCurrent() || device.state.value.headTrackingState == BuddyState.INACTIVE)
                throw CaptureStopped("Stopped: connection or motion request ended.")
        }

        /** Skips repeated reports and stops when the wrapping 32-bit sensor counter moves backward. */
        private fun isNewReport(report: MotionReport): Boolean {
            val counter = report.counter
            val previous = previousCounter
            if (counter != null && previous != null) {
                val delta = motionCounterStep(previous, counter, previousDeviceTime, report.timestampNs)
                if (delta == 0L) return false
                if (delta > SENSOR_COUNTER_HALF_RANGE) throw CaptureStopped("Stopped: sensor counter moved backward.")
            }
            previousCounter = counter
            previousDeviceTime = report.timestampNs
            return true
        }
    }

    private class CaptureStopped(message: String) : Exception(message)

    companion object {
        const val MAX_SAMPLES = 500
        private const val SENSOR_COUNTER_HALF_RANGE = 0x7fffffffL
        private val capturing = AtomicBoolean(false)
    }
}
