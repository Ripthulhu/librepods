package me.kavishdevar.librepods.core.devices

import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier

internal fun normalizedControl(value: ByteArray): ByteArray =
    value.dropLastWhile { it == 0.toByte() }.toByteArray().takeIf { it.isNotEmpty() } ?: byteArrayOf(0)

internal fun AppleState.withControlReport(id: ControlCommandIdentifier, rawValue: ByteArray): AppleState {
    val value = normalizedControl(rawValue)
    // Adaptive strength can apply silently and later report a rounded value.
    val confirmsRequest = when (id) {
        ControlCommandIdentifier.AUTO_ANC_STRENGTH ->
            rawValue.isNotEmpty() && value.size == 1 && (value[0].toInt() and 255) in 0..100
        ControlCommandIdentifier.ALLOW_OFF_OPTION -> allowOffValue(rawValue) != null
        else -> requestedControls[id]?.contentEquals(value) == true
    }
    return copy(controlStates = controlStates + (id to value), requestedControls =
        if (confirmsRequest) requestedControls - id else requestedControls)
}

internal fun AppleState.withControlRequest(id: ControlCommandIdentifier, value: ByteArray,
    previousReport: ByteArray?): AppleState {
    // Reports received while a write was in progress remain authoritative.
    if (controlStates[id] !== previousReport) return this
    val requested = normalizedControl(value)
    return copy(requestedControls = if (controlStates[id]?.contentEquals(requested) == true)
        requestedControls - id else requestedControls + (id to requested))
}
