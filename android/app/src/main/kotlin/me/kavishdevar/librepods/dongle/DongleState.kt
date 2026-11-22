package me.kavishdevar.librepods.dongle

import me.kavishdevar.librepods.core.bluetooth.att.types.HearingAidSettings
import me.kavishdevar.librepods.core.bluetooth.att.types.SavedHearingDiagnostics
import me.kavishdevar.librepods.core.devices.AccommodationsReport
import me.kavishdevar.librepods.core.devices.AccommodationsRequestState
import me.kavishdevar.librepods.core.devices.AccommodationsResult
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.HearingModes
import me.kavishdevar.librepods.core.devices.SettingsReadStatus

internal data class DongleChoice(val address: String, val name: String, val usb: Boolean = false)

internal data class DongleState(
    val selected: Boolean = false,
    val usb: Boolean = false,
    val attached: Boolean = false,
    val usbAudioAvailable: Boolean = false,
    val usbPermissionRequired: Boolean = false,
    val usbVolumeAvailable: Boolean = false,
    val usbVolumeEnabled: Boolean = false,
    val usbVolumePending: Boolean = false,
    val usbVolumeRequested: Boolean = false,
    val name: String = "AirPods Dongle",
    val connected: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val status: DongleProtocol.Status? = null,
    val metadata: AppleMetadata? = null,
    val settings: Map<Int, DongleProtocol.Setting> = emptyMap(),
    val requested: Map<Int, Long> = emptyMap(),
    val savedChoices: Map<Int, Long> = emptyMap(),
    val unconfirmed: Set<Int> = emptySet(),
    val automaticPause: Boolean = false,
    val hearingModes: HearingModes = HearingModes.empty(),
    val hearingProfile: HearingAidSettings? = null,
    val controlSession: Long = 0,
    val accommodationsRequest: AccommodationsRequestState = AccommodationsRequestState(),
    val hearingAidData: ByteArray = byteArrayOf(),
    val hearingAidReadStatus: SettingsReadStatus = SettingsReadStatus.WAITING,
    val transparencyData: ByteArray = byteArrayOf(),
    val transparencyReadStatus: SettingsReadStatus = SettingsReadStatus.WAITING,
    val transparencyResult: AccommodationsResult? = null,
    val diagnostics: SavedHearingDiagnostics? = null,
    val audio: DongleProtocol.AudioProfile? = null,
    val performance: DongleProtocol.Performance? = null,
    val finderOpen: Boolean = false,
    val nearby: DongleProtocol.Nearby? = null,
) {
    val accommodationsReport: AccommodationsReport? get() = hearingModes.accommodationsReport(controlSession)
}
