package me.kavishdevar.librepods.core.bluetooth.aacp

internal fun validRawAacpPacket(data: ByteArray): Boolean = data.size in 6..65_535 &&
    data[1] == 0.toByte() && data[3] == 0.toByte() && data[5] == 0.toByte()

internal fun validAacpMessage(data: ByteArray): Boolean = validRawAacpPacket(data) &&
    data[0] == 4.toByte() && data[2] == 4.toByte()
