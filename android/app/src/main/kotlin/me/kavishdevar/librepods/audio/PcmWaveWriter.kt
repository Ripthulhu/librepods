/* Copyright (C) 2026 LibrePods contributors; GPL-3.0-or-later. */
package me.kavishdevar.librepods.audio

import java.io.File
import java.io.RandomAccessFile

data class PcmFormat(val sampleRate: Int, val channels: Int) {
    init {
        require(sampleRate in 8_000..192_000 && channels in 1..2)
    }
    val frameSize: Int get() = channels * 2
    fun durationMs(bytes: Long): Long = bytes / frameSize * 1000 / sampleRate
}

/** PCM16 little-endian, with duration based on the physical presentation rate. */
class PcmWaveWriter(file: File, val format: PcmFormat) : AutoCloseable {
    private val stream = RandomAccessFile(file, "rw")
    var bytesWritten = 0L
        private set
    private var closed = false

    init {
        try {
            stream.setLength(0)
            stream.writeBytes("RIFF")
            writeInt(36)
            stream.writeBytes("WAVEfmt ")
            writeInt(16)
            writeShort(1)
            writeShort(format.channels)
            writeInt(format.sampleRate)
            writeInt(format.sampleRate * format.frameSize)
            writeShort(format.frameSize)
            writeShort(16)
            stream.writeBytes("data")
            writeInt(0)
        } catch (e: Exception) {
            stream.close()
            throw e
        }
    }

    fun write(pcm: ByteArray) {
        check(!closed)
        require(pcm.size % format.frameSize == 0) { "Unaligned PCM frame" }
        require(bytesWritten + pcm.size <= 0xffff_ffffL - 36) { "Recording exceeds WAV limit" }
        stream.write(pcm)
        bytesWritten += pcm.size
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            stream.seek(4)
            writeInt(36 + bytesWritten)
            stream.seek(40)
            writeInt(bytesWritten)
        } finally {
            stream.close()
        }
    }

    private fun writeInt(value: Long) {
        for (shift in 0..24 step 8) stream.write((value ushr shift).toInt() and 0xff)
    }
    private fun writeInt(value: Int) = writeInt(value.toLong())
    private fun writeShort(value: Int) {
        stream.write(value and 0xff)
        stream.write(value ushr 8 and 0xff)
    }
}
