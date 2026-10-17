package me.kavishdevar.librepods.core.bluetooth.aacp.packet

import me.kavishdevar.librepods.core.bluetooth.aacp.types.MagicKeyType
import me.kavishdevar.librepods.core.bluetooth.aacp.types.MessageOpcode
import me.kavishdevar.librepods.core.devices.PacketDestination


data class MagicKeyResponsePacket(
    val magicKeys: Map<MagicKeyType, ByteArray>,
    override val payload: ByteArray,
): AACPPacket {
    override val destination: PacketDestination = PacketDestination.HOST

    override val type: AACPPacketType = AACPPacketType.MESSAGE
    override val service: Byte = 0x04

    override val opcode = MessageOpcode.MAGIC_KEYS_RESPONSE

    companion object {
        fun parse(
            packet: ByteArray,
        ): MagicKeyResponsePacket {
            require(packet.size in 7..4096 && packet.take(6) == listOf<Byte>(4, 0, 4, 0, 0x31, 0)) { "Invalid key response" }
            val payload = packet.copyOfRange(6, packet.size)
            val count = payload[0].toInt() and 255
            require(count <= 32) { "Too many key records" }
            val keys = mutableMapOf<MagicKeyType, ByteArray>()
            var offset = 1
            repeat(count) {
                require(payload.size - offset >= 4) { "Truncated key record" }
                val type = (payload[offset].toInt() and 255) or ((payload[offset + 1].toInt() and 255) shl 8)
                val length = (payload[offset + 2].toInt() and 255) or ((payload[offset + 3].toInt() and 255) shl 8)
                offset += 4
                require(length <= payload.size - offset) { "Truncated key value" }
                val kind = MagicKeyType.entries.firstOrNull { it.value.toInt() == type }
                if (kind != null) {
                    require(length == 16 && kind !in keys) { "Invalid or duplicate proximity key" }
                    val key = payload.copyOfRange(offset, offset + length)
                    require(key.any { it != 0.toByte() }) { "Invalid zero proximity key" }
                    keys[kind] = key
                }
                offset += length
            }
            require(offset == payload.size) { "Trailing key response data" }
            // A rotated IRK and encryption key must replace the previous pair together.
            require(MagicKeyType.IRK in keys && MagicKeyType.ENC_KEY in keys) { "Incomplete proximity key pair" }
            return MagicKeyResponsePacket(keys, payload)
        }
    }
}
