package me.kavishdevar.librepods.core.bluetooth.aacp.packet

import me.kavishdevar.librepods.core.bluetooth.aacp.types.MessageOpcode
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.AirPodsSpecs
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.PacketDestination

data class InformationPacket(
    val metadata: AppleMetadata,
    override val payload: ByteArray
): AACPPacket {
    override val destination: PacketDestination = PacketDestination.HOST

    override val type: AACPPacketType = AACPPacketType.MESSAGE
    override val service: Byte = 0x04

    override val opcode = MessageOpcode.INFORMATION

    companion object {
        fun parse(
            packet: ByteArray,
        ): InformationPacket {
            require(packet.size >= 11 && packet.take(6) == listOf<Byte>(4, 0, 4, 0, 0x1d, 0)) {
                "Invalid information response"
            }
            val payload = packet.copyOfRange(6, packet.size)
            var index = 11
            val strings = List(11) {
                val start = index
                while (index < packet.size && packet[index] != 0.toByte()) index++
                require(index < packet.size) { "Unterminated information field" }
                val raw = packet.copyOfRange(start, index++)
                val value = raw.decodeToString()
                require(value.toByteArray().contentEquals(raw) && value.none { Character.isISOControl(it) }) {
                    "Invalid UTF-8 information field"
                }
                value
            }
            val model = AirPodsModel.fromModelNumber(strings[1])

            return InformationPacket(
                metadata = AppleMetadata(
                    name = strings.getOrNull(0) ?: "",
                    model = model,
                    modelName = AirPodsSpecs.getSpec(model).displayName,
                    modelNumber = strings.getOrNull(1) ?: "",
                    manufacturer = strings.getOrNull(2) ?: "",
                    serialNumber = strings.getOrNull(3) ?: "",
                    leftSerialNumber = strings.getOrNull(8) ?: "",
                    rightSerialNumber = strings.getOrNull(9) ?: "",
                    version1 = strings.getOrNull(4) ?: "",
                    version2 = strings.getOrNull(5) ?: "",
                    version3 = strings.getOrNull(10) ?: "",
                    hardwareRevision = strings.getOrNull(6) ?: "",
                    updaterIdentifier = strings.getOrNull(7) ?: ""
                ),
                payload = payload
            )
        }
    }
}

