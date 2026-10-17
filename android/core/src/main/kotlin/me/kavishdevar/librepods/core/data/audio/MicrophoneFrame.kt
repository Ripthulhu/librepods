package me.kavishdevar.librepods.core.data.audio

import me.kavishdevar.librepods.core.bluetooth.aacp.types.MessageOpcode
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class MicrophoneFrame(
    val timestamp: UInt,
    val accessUnit: ByteArray
) {
    companion object {
        fun parsePacket(packet: ByteArray): List<MicrophoneFrame> {
            if (packet.size < 8) {
                throw IllegalArgumentException("Microphone packet too short")
            }

            if (packet.take(6) != listOf<Byte>(4, 0, 4, 0, MessageOpcode.MICROPHONE_STREAM.value, 0)) {
                throw IllegalArgumentException("Not a microphoneState packet")
            }


            if (packet[6] != 0x01.toByte() || packet[7] != 0x00.toByte()) {
                return emptyList()
            }

            require(packet.size > 22) { "Microphone packet has no audio records" }
            var endOfRecords = 22
            while (endOfRecords < packet.size) {
                require(packet.size - endOfRecords >= 5) { "Truncated microphone record" }
                val size = packet[endOfRecords + 4].toInt() and 0xff
                require(size > 0 && size <= packet.size - endOfRecords - 5) { "Invalid microphone record length" }
                endOfRecords += 5 + size
            }

            val frames = mutableListOf<MicrophoneFrame>()

            var offset = 22

            while (offset + 5 <= packet.size) {
                val timestamp = ByteBuffer
                    .wrap(packet, offset, 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .int
                    .toUInt()

                val length = packet[offset + 4].toUByte().toInt()

                val start = offset + 5
                val end = start + length

                frames += MicrophoneFrame(
                    timestamp = timestamp,
                    accessUnit = packet.copyOfRange(start, end)
                )

                offset = end
            }

            return frames
        }
    }
}
