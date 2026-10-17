package me.kavishdevar.librepods.core.devices

import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier

internal fun supportsSilentAllowOff(modelNumber: String): Boolean =
    modelNumber in setOf("A3063", "A3064", "A3065")

internal fun allowOffValue(value: ByteArray?): Boolean? {
    if (value == null || value.isEmpty() || value.size > 4 || value.drop(1).any { it != 0.toByte() }) return null
    return when (value[0].toInt()) { 1 -> true; 2 -> false; else -> null }
}

/** A pending request is presentation intent; a fresh valid report retires it. */
fun AppleState.effectiveAllowOff(settings: AppleSettings, modelNumber: String): Boolean? =
    allowOffValue(requestedControls[ControlCommandIdentifier.ALLOW_OFF_OPTION])
        ?: allowOffValue(controlStates[ControlCommandIdentifier.ALLOW_OFF_OPTION])
        ?: settings.allowOffChoice.takeIf { supportsSilentAllowOff(modelNumber) }

internal fun AppleState.cachedControlStates(settings: AppleSettings, modelNumber: String): Map<ControlCommandIdentifier, ByteArray> =
    if (settings.allowOffChoice != null && supportsSilentAllowOff(modelNumber) &&
        ControlCommandIdentifier.ALLOW_OFF_OPTION in controlStates)
        controlStates - ControlCommandIdentifier.ALLOW_OFF_OPTION else controlStates

internal fun AppleSettings.withAllowOffRequest(modelNumber: String, state: AppleState,
    value: ByteArray, previousReport: ByteArray?): AppleSettings {
    if (!supportsSilentAllowOff(modelNumber) ||
        state.controlStates[ControlCommandIdentifier.ALLOW_OFF_OPTION] !== previousReport) return this
    val choice = allowOffValue(value) ?: return this
    return copy(allowOffChoice = choice)
}

internal fun AppleSettings.withAllowOffReport(modelNumber: String, value: ByteArray): AppleSettings {
    if (!supportsSilentAllowOff(modelNumber)) return this
    val choice = allowOffValue(value) ?: return this
    return copy(allowOffChoice = choice)
}
