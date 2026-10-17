package me.kavishdevar.librepods.core.bluetooth.aacp.packet

import me.kavishdevar.librepods.core.bluetooth.aacp.types.MessageOpcode
import me.kavishdevar.librepods.core.bluetooth.aacp.types.Opcode
import me.kavishdevar.librepods.core.devices.Battery
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.core.devices.BatteryStatus
import me.kavishdevar.librepods.core.devices.PacketDestination

data class BatteryInfoPacket(
    val batteries: Set<Battery>,
    override val payload: ByteArray,
): AACPPacket {
    override val destination: PacketDestination = PacketDestination.HOST

    override val type: AACPPacketType = AACPPacketType.MESSAGE
    override val service: Byte = 0x04

    override val opcode: Opcode = MessageOpcode.BATTERY_INFO

    companion object {
        fun parse(
            packet: ByteArray,
        ): BatteryInfoPacket {
            require(packet.size >= 7 && packet.take(6) == listOf<Byte>(4, 0, 4, 0, 4, 0)) { "Invalid battery response" }
            val payload = packet.copyOfRange(6, packet.size)
            val batteryCount = payload[0].toInt() and 255
            require(batteryCount > 0 && payload.size == 1 + batteryCount * 5) { "Incomplete battery response" }
            val batteries = mutableSetOf<Battery>()
            val components = mutableSetOf<BatteryComponent>()
            var offset = 1
            for (i in 0 until batteryCount) {
                val componentByte = payload[offset]
                val level = payload[offset + 2].toInt() and 255
                require(level <= 100 || level == 255) { "Invalid battery percentage" }
                val statusByte = payload[offset + 3].toInt() and 255
                offset += 5
                val component = when (componentByte.toInt()) {
                    1, 2, 4, 8 -> BatteryComponent.fromAirPodsByte(componentByte)
                    else -> continue
                }
                require(components.add(component)) { "Duplicate battery component" }
                val status = if (level == 255 || statusByte == 4) BatteryStatus.DISCONNECTED
                    else when (statusByte) {
                        1 -> BatteryStatus.CHARGING
                        5 -> BatteryStatus.OPTIMIZED_CHARGING
                        2 -> BatteryStatus.NOT_CHARGING
                        else -> BatteryStatus.UNKNOWN
                    }
                batteries.add(Battery(component, if (level == 255) 0 else level, status))
            }

            return BatteryInfoPacket(batteries, payload)
        }
    }
}
