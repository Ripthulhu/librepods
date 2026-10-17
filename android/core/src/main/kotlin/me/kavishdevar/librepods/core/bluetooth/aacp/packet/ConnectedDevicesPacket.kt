package me.kavishdevar.librepods.core.bluetooth.aacp.packet

import me.kavishdevar.librepods.core.bluetooth.aacp.types.ConnectedDevice
import me.kavishdevar.librepods.core.bluetooth.aacp.types.MessageOpcode
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.devices.PacketDestination

data class ConnectedDevicesPacket(
    val connectedDevices: List<ConnectedDevice>,
    override val payload: ByteArray,
): AACPPacket {
    override val destination: PacketDestination = PacketDestination.HOST

    override val type: AACPPacketType = AACPPacketType.MESSAGE
    override val service: Byte = 0x04

    override val opcode = MessageOpcode.CONNECTED_DEVICES

    companion object {
        fun parse(
            packet: ByteArray,
        ): ConnectedDevicesPacket {
            require(packet.size >= 9 && packet.take(6) == listOf<Byte>(4, 0, 4, 0, 0x2e, 0)) { "Invalid connected-host report" }

            val payload = packet.copyOfRange(6, packet.size)

            val deviceCount = payload[2].toInt() and 255
            require(payload.size >= 3 + deviceCount * 8) { "Truncated connected-host report" }
            val devices = mutableListOf<ConnectedDevice>()

            var offset = 3

            for (i in 0 until deviceCount) {
                val macAddress = MacAddress(payload.sliceArray(offset until offset + 6).toHexString(HexFormat{ upperCase = true }).chunked(2).joinToString(":"))

                val info1 = payload[offset + 6]
                val info2 = payload[offset + 7]

                require(devices.none { it.macAddress == macAddress }) { "Duplicate connected-host identity" }
                devices.add(ConnectedDevice(macAddress, info1, info2, null))
                offset += 8
            }

            return ConnectedDevicesPacket(devices, payload)
        }
    }
}
