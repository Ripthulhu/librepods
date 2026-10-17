/* Copyright (C) 2026 LibrePods contributors; GPL-3.0-or-later. */
package me.kavishdevar.librepods.audio

/** ASC coding rate differs from the source clock: Pro 3 produces 480 samples every 7.5 ms. */
object AirPodsMicrophoneClock {
    const val CODING_RATE = 48_000
    const val PHYSICAL_RATE = 64_000
    const val SAMPLES_PER_UNIT = 480
    fun presentationTimeUs(frames: Long): Long = frames * 1_000_000 / PHYSICAL_RATE
}

/** Aggregate timing only. Device-counter units remain unverified and never alter decoded samples. */
internal class MicrophoneClockStats(private val captureStartedNs: Long) {
    data class Snapshot(val firstPacketMs: Long, val warmupMs: Long, val steadyMs: Long,
        val accessUnits: Long, val decodedFrames: Long, val sourceHz: Long,
        val timestampTicks: Long, val timestampIntervals: Long, val repeats: Long, val backwards: Long)

    private var firstPacketNs: Long? = null
    private var anchorNs: Long? = null
    private var lastArrivalNs = 0L
    private var accessUnits = 0L
    private var decodedFrames = 0L
    private var anchorUnits = 0L
    private var anchorFrames = 0L
    private var lastTimestamp: Long? = null
    private var ticks = 0L
    private var intervals = 0L
    private var repeats = 0L
    private var backwards = 0L

    fun packet(timestamps: List<Long>, receivedNs: Long, frames: Long) {
        val first = firstPacketNs ?: receivedNs.also { firstPacketNs = it }
        require(receivedNs >= first && receivedNs >= lastArrivalNs && frames >= decodedFrames)
        lastArrivalNs = receivedNs
        accessUnits += timestamps.size
        decodedFrames = frames
        if (anchorNs == null) {
            if (receivedNs - first >= 1_000_000_000) {
                anchorNs = receivedNs
                anchorUnits = accessUnits
                anchorFrames = frames
                lastTimestamp = timestamps.lastOrNull()
            }
            return
        }
        for (timestamp in timestamps) {
            lastTimestamp?.let { previous ->
                val delta = (timestamp - previous) and 0xffff_ffffL
                when {
                    delta == 0L -> repeats++
                    delta <= 0x7fff_ffffL -> { ticks += delta; intervals++ }
                    else -> backwards++
                }
            }
            lastTimestamp = timestamp
        }
    }

    fun snapshot(): Snapshot? {
        val first = firstPacketNs ?: return null
        val anchor = anchorNs ?: return null
        val elapsed = lastArrivalNs - anchor
        if (elapsed < 1_000_000_000) return null
        val frames = decodedFrames - anchorFrames
        return Snapshot((first - captureStartedNs) / 1_000_000, (anchor - first) / 1_000_000,
            elapsed / 1_000_000, accessUnits - anchorUnits, frames, frames * 1_000_000_000 / elapsed,
            ticks, intervals, repeats, backwards)
    }
}
