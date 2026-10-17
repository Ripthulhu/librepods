package me.kavishdevar.librepods.core.devices

import me.kavishdevar.librepods.core.bluetooth.aacp.packet.AACPPacket

/** Debug history is bounded and excludes microphone frames, keys and identifying records. */
internal fun retainedPacketHistory(packets: List<AACPPacket>): List<AACPPacket> = packets
    .filterNot { (it.opcode.value.toInt() and 255) in PRIVATE_OPCODES }
    .takeLast(256)

private val PRIVATE_OPCODES = setOf(0x31, 0x58, 0x1d, 0x2e, 0x2f, 0x29, 0x0c, 0x10, 0x11, 0x1a)
