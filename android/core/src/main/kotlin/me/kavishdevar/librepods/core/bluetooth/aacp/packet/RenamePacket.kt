package me.kavishdevar.librepods.core.bluetooth.aacp.packet

import me.kavishdevar.librepods.core.bluetooth.aacp.types.MessageOpcode
import me.kavishdevar.librepods.core.devices.PacketDestination

data class RenamePacket(
    val name: String,
    override val destination: PacketDestination = PacketDestination.DEVICE,
): AACPPacket {
    override val type: AACPPacketType = AACPPacketType.MESSAGE
    override val service: Byte = 0x04

    override val opcode = MessageOpcode.RENAME

    private val encodedName = name.toByteArray(Charsets.UTF_8).also {
        require(name.isNotBlank() && name.none { character -> Character.isISOControl(character) } &&
            it.size in 1..64 && it.decodeToString() == name) { "Name must contain 1–64 UTF-8 bytes without control characters" }
    }
    override val payload: ByteArray = byteArrayOf(0x01, encodedName.size.toByte(), 0x00) + encodedName

    companion object {
        fun create(
            name: String,
            destination: PacketDestination = PacketDestination.DEVICE
        ): RenamePacket {
            return RenamePacket(
                name = name,
                destination = destination,
            )
        }
    }
}
