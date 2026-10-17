/* Copyright (C) 2026 LibrePods contributors; GPL-3.0-or-later.
 * AAC-ELD configuration follows LibrePods' Android rewrite and NTPods. */
package me.kavishdevar.librepods.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import java.nio.ByteBuffer

/** One decoder per capture session. All operations belong to its worker thread. */
class EldDecoder(
    private val onFormat: (PcmFormat) -> Unit,
    private val onPcm: (ByteArray, Long) -> Unit,
) : AutoCloseable {
    private val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val info = MediaCodec.BufferInfo()
    private var format: PcmFormat? = null
    private var queuedSamples = 0L
    var decodedFrames = 0L
        private set

    init {
        try {
            val input = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AirPodsMicrophoneClock.CODING_RATE, 1)
            input.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD)
            input.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            input.setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0xf8.toByte(), 0xe6.toByte(), 0x30, 0)))
            // The ASC describes coding at 48 kHz; the physical uplink runs at 64 kHz.
            codec.configure(input, null, null, 0)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            throw e
        }
    }

    fun decode(unit: ByteArray, shouldStop: () -> Boolean) {
        require(unit.isNotEmpty() && unit.size <= 255)
        val deadline = SystemClock.elapsedRealtime() + 500
        var index: Int
        while (true) {
            if (shouldStop()) return
            index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) break
            drain()
            check(SystemClock.elapsedRealtime() < deadline) { "AAC-ELD decoder input stalled" }
        }
        val input = checkNotNull(codec.getInputBuffer(index)) { "Missing decoder input buffer" }
        input.clear()
        check(input.remaining() >= unit.size) { "AAC-ELD input buffer too small" }
        input.put(unit)
        codec.queueInputBuffer(index, 0, unit.size, queuedSamples * 1_000_000 / AirPodsMicrophoneClock.CODING_RATE, 0)
        queuedSamples += AirPodsMicrophoneClock.SAMPLES_PER_UNIT
        drain()
    }

    fun drain() {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val output = codec.outputFormat
                    val encoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING))
                        output.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    check(encoding == AudioFormat.ENCODING_PCM_16BIT) { "Decoder did not produce PCM16" }
                    check(output.getInteger(MediaFormat.KEY_SAMPLE_RATE) == AirPodsMicrophoneClock.CODING_RATE) {
                        "Unexpected AAC-ELD coding rate"
                    }
                    val next = PcmFormat(AirPodsMicrophoneClock.PHYSICAL_RATE, output.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    check(next.channels == 1) { "Microphone decoder did not produce mono" }
                    check(format == null || format == next) { "Microphone format changed during capture" }
                    format = next
                    onFormat(next)
                }
                index >= 0 -> {
                    try {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            val current = checkNotNull(format) { "PCM arrived before output format" }
                            check(info.size % current.frameSize == 0) { "Unaligned decoder PCM" }
                            val output = checkNotNull(codec.getOutputBuffer(index))
                            check(info.offset >= 0 && info.size <= output.capacity() - info.offset)
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            output.get(bytes)
                            val timestamp = AirPodsMicrophoneClock.presentationTimeUs(decodedFrames)
                            decodedFrames += info.size / current.frameSize
                            onPcm(bytes, timestamp)
                        }
                    } finally {
                        codec.releaseOutputBuffer(index, false)
                    }
                }
                else -> return
            }
        }
    }

    override fun close() {
        try { codec.stop() } finally { codec.release() }
    }
}
