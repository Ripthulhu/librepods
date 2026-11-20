package me.kavishdevar.librepods.dongle

import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier.ADAPTIVE_VOLUME_CONFIG
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier.ALLOW_AUTO_CONNECT
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier.ALLOW_OFF_OPTION
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier.AUTO_ANC_STRENGTH
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier.CALL_MANAGEMENT_CONFIG
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier.CHIME_VOLUME
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier.CLICK_HOLD_INTERVAL
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier.CLICK_HOLD_MODE
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier.CROWN_ROTATION_DIRECTION
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier.DOUBLE_CLICK_INTERVAL
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier.DYNAMIC_END_OF_CHARGE
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier.EAR_DETECTION_CONFIG
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier.IN_CASE_TONE_CONFIG
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier.IN_CASE_TONE_VOLUME
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier.LISTENING_MODE
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier.LISTENING_MODE_CONFIGS
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier.MIC_MODE
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier.ONE_BUD_ANC_MODE
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier.SLEEP_DETECTION_CONFIG
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier.SMART_ROUTING_MODE
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier.VOLUME_SWIPE_INTERVAL
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier.VOLUME_SWIPE_MODE
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.AirPodsSpecs
import me.kavishdevar.librepods.core.devices.BaseCapability
import me.kavishdevar.librepods.core.devices.noiseCycleRequiresAllowOff
import me.kavishdevar.librepods.core.devices.supportedNoiseModeCycleMask
import me.kavishdevar.librepods.dongle.DongleControlProtocol.Control.Capability
import me.kavishdevar.librepods.dongle.DongleControlProtocol.SettingValue

internal data class DongleAudioIntent(val codec: Int, val rateMode: Int, val bitrate: Long)

/** The dongle reports and writes AACP control ids as unsigned setting numbers. */
internal val ControlCommandIdentifier.settingId: Int get() = value.toInt() and 255

/** Adaptive listening and adaptive strength need at least this AirPods firmware major version. */
private const val ADAPTIVE_AUDIO_MINIMUM_FIRMWARE_MAJOR = 6
private const val SLEEP_DETECTION_MINIMUM_FIRMWARE_MAJOR = 8
/** Every hold-action pair this client writes: each ear is either the assistant or noise control. */
private val holdActionPairs = listOf(SettingValue.HOLD_ACTION_DIGITAL_ASSISTANT, SettingValue.HOLD_ACTION_NOISE_CONTROL)
    .flatMap { left -> listOf(SettingValue.HOLD_ACTION_DIGITAL_ASSISTANT, SettingValue.HOLD_ACTION_NOISE_CONTROL)
        .map { right -> (left shl SettingValue.LEFT_HOLD_ACTION_SHIFT) or (right shl SettingValue.RIGHT_HOLD_ACTION_SHIFT) } }
    .toSet()

internal fun validLiveSetting(id: Int, value: Long): Boolean = when (id) {
    MIC_MODE.settingId, DOUBLE_CLICK_INTERVAL.settingId, CLICK_HOLD_INTERVAL.settingId, VOLUME_SWIPE_INTERVAL.settingId ->
        value in 0L..SettingValue.THREE_WAY_CHOICE_MAXIMUM
    LISTENING_MODE.settingId -> value in SettingValue.LISTENING_MODE_OFF..SettingValue.LISTENING_MODE_ADAPTIVE
    EAR_DETECTION_CONFIG.settingId, ONE_BUD_ANC_MODE.settingId, CROWN_ROTATION_DIRECTION.settingId,
    SMART_ROUTING_MODE.settingId, VOLUME_SWIPE_MODE.settingId, ADAPTIVE_VOLUME_CONFIG.settingId,
    CONVERSATION_DETECT_CONFIG.settingId, IN_CASE_TONE_CONFIG.settingId, ALLOW_OFF_OPTION.settingId,
    SLEEP_DETECTION_CONFIG.settingId, ALLOW_AUTO_CONNECT.settingId, DYNAMIC_END_OF_CHARGE.settingId ->
        value in SettingValue.TOGGLE_ON..SettingValue.TOGGLE_OFF
    CLICK_HOLD_MODE.settingId -> value in holdActionPairs
    LISTENING_MODE_CONFIGS.settingId -> value in 0L..SettingValue.HOLD_MODES_ALL &&
        java.lang.Long.bitCount(value) >= SettingValue.HOLD_MODES_MINIMUM_SELECTED
    CALL_MANAGEMENT_CONFIG.settingId -> value in setOf(SettingValue.CALL_CONTROLS_SWAPPED, SettingValue.CALL_CONTROLS_DEFAULT)
    CHIME_VOLUME.settingId -> value in SettingValue.TONE_VOLUME_FLAG..(SettingValue.TONE_VOLUME_FLAG + SettingValue.MAXIMUM_PERCENT)
    AUTO_ANC_STRENGTH.settingId, IN_CASE_TONE_VOLUME.settingId -> value in 0L..SettingValue.MAXIMUM_PERCENT
    else -> false
}

/** Settings this client may write and remember before the AirPods have reported them. */
internal val dongleSavedChoiceIds = listOf(MIC_MODE, EAR_DETECTION_CONFIG, CLICK_HOLD_MODE, LISTENING_MODE_CONFIGS,
    VOLUME_SWIPE_INTERVAL, IN_CASE_TONE_CONFIG, ALLOW_OFF_OPTION, IN_CASE_TONE_VOLUME).map { it.settingId }.toSet()
/** The firmware's legacy 23-control catalog, in catalog index order; [controlMask] bits refer to these indices. */
private val settingIds = listOf(MIC_MODE, EAR_DETECTION_CONFIG, LISTENING_MODE, CLICK_HOLD_MODE, DOUBLE_CLICK_INTERVAL,
    CLICK_HOLD_INTERVAL, LISTENING_MODE_CONFIGS, ONE_BUD_ANC_MODE, CROWN_ROTATION_DIRECTION, CHIME_VOLUME,
    SMART_ROUTING_MODE, VOLUME_SWIPE_INTERVAL, CALL_MANAGEMENT_CONFIG, VOLUME_SWIPE_MODE, ADAPTIVE_VOLUME_CONFIG,
    CONVERSATION_DETECT_CONFIG, AUTO_ANC_STRENGTH, IN_CASE_TONE_CONFIG, ALLOW_OFF_OPTION, SLEEP_DETECTION_CONFIG,
    ALLOW_AUTO_CONNECT, DYNAMIC_END_OF_CHARGE, IN_CASE_TONE_VOLUME).map { it.settingId }

/** The firmware's reviewed control catalog; the core model still owns semantic capabilities. */
private fun controlMask(model: AirPodsModel): Int = when (model) {
    AirPodsModel.AIRPODS_1 -> 0x00000203
    AirPodsModel.AIRPODS_2 -> 0x00000603
    AirPodsModel.AIRPODS_3 -> 0x0020163b
    AirPodsModel.AIRPODS_4 -> 0x0028563b
    AirPodsModel.AIRPODS_4_ANC -> 0x006bd6ff
    AirPodsModel.AIRPODS_5 -> 0x0029d6ff
    AirPodsModel.AIRPODS_5_WCC -> 0x006bfeff
    AirPodsModel.AIRPODS_PRO_1 -> 0x002016ff
    AirPodsModel.AIRPODS_PRO_2_LIGHTNING, AirPodsModel.AIRPODS_PRO_2_USBC, AirPodsModel.AIRPODS_PRO_3 -> 0x007ffeff
    AirPodsModel.AIRPODS_MAX_LIGHTNING, AirPodsModel.AIRPODS_MAX_USBC -> 0x00201746
    AirPodsModel.AIRPODS_MAX_2 -> 0x0029d746
    AirPodsModel.UNKNOWN -> 0
}

internal fun currentDongleSetting(id: Int, raw: Long): Long =
    if (id == CLICK_HOLD_MODE.settingId) raw and SettingValue.HOLD_ACTIONS_REPORT_MASK else raw

internal fun DongleState.canEditSetting(id: Int): Boolean {
    val info = metadata ?: return false
    val index = settingIds.indexOf(id)
    val settingsCapabilities = Capability.SETTINGS_TRANSPORT or Capability.TYPED_FEATURES
    if (!connected || status?.controlsReady != true || status.microphoneActive ||
        status.capabilities and settingsCapabilities != settingsCapabilities ||
        index < 0 || info.model == AirPodsModel.UNKNOWN || info.model != AirPodsModel.fromModelNumber(info.modelNumber) ||
        controlMask(info.model) and (1 shl index) == 0) return false
    val caps = AirPodsSpecs.getSpec(info.model).baseCapabilities
    val firmwareMajor = info.firmwareMajor ?: 0
    val adaptiveStrengthUnavailable = id == AUTO_ANC_STRENGTH.settingId && (BaseCapability.ADAPTIVE_AUDIO !in caps ||
        firmwareMajor < ADAPTIVE_AUDIO_MINIMUM_FIRMWARE_MAJOR ||
        settings[LISTENING_MODE.settingId]?.value != SettingValue.LISTENING_MODE_ADAPTIVE)
    val sleepDetectionUnavailable = id == SLEEP_DETECTION_CONFIG.settingId &&
        (BaseCapability.SLEEP_DETECTION !in caps || firmwareMajor < SLEEP_DETECTION_MINIMUM_FIRMWARE_MAJOR)
    if (adaptiveStrengthUnavailable || sleepDetectionUnavailable) return false
    val actual = settings[id]?.value
    if (actual != null) return validLiveSetting(id, currentDongleSetting(id, actual))
    return id in dongleSavedChoiceIds && (id != ALLOW_OFF_OPTION.settingId || info.model == AirPodsModel.AIRPODS_PRO_3)
}

internal fun DongleState.canWriteSetting(id: Int, value: Long): Boolean {
    if (!canEditSetting(id) || !validLiveSetting(id, value)) return false
    val info = checkNotNull(metadata)
    val caps = AirPodsSpecs.getSpec(info.model).baseCapabilities
    val adaptive = BaseCapability.ADAPTIVE_AUDIO in caps && (info.firmwareMajor ?: 0) >= ADAPTIVE_AUDIO_MINIMUM_FIRMWARE_MAJOR
    val allowOffId = ALLOW_OFF_OPTION.settingId
    val offValue = requested[allowOffId] ?: settings[allowOffId]?.value ?: savedChoices[allowOffId]
    val listeningMode = settings[LISTENING_MODE.settingId]?.value
    val allowOff = when (offValue) {
        SettingValue.TOGGLE_ON -> true
        SettingValue.TOGGLE_OFF -> false
        else -> if (!info.noiseCycleRequiresAllowOff() || listeningMode == SettingValue.LISTENING_MODE_OFF) true else null
    }
    return when (id) {
        LISTENING_MODE.settingId -> (value != SettingValue.LISTENING_MODE_ADAPTIVE || adaptive) &&
            (value != SettingValue.LISTENING_MODE_OFF || allowOff == true)
        CLICK_HOLD_MODE.settingId -> {
            val rightAction = value shr SettingValue.RIGHT_HOLD_ACTION_SHIFT and SettingValue.HOLD_ACTION_MASK
            val leftAction = value shr SettingValue.LEFT_HOLD_ACTION_SHIFT and SettingValue.HOLD_ACTION_MASK
            BaseCapability.LISTENING_MODE in caps ||
                rightAction != SettingValue.HOLD_ACTION_NOISE_CONTROL && leftAction != SettingValue.HOLD_ACTION_NOISE_CONTROL
        }
        LISTENING_MODE_CONFIGS.settingId -> value.toInt() and info.supportedNoiseModeCycleMask(allowOff).inv() == 0
        else -> true
    }
}
