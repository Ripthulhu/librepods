package me.kavishdevar.librepods.core.devices

import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.AACPPacket
import me.kavishdevar.librepods.core.bluetooth.aacp.types.AudioSource
import me.kavishdevar.librepods.core.bluetooth.aacp.types.AudioSourceType
import me.kavishdevar.librepods.core.bluetooth.aacp.types.CapabilityEntry
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ConnectedDevice
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.bluetooth.aacp.types.CustomEq
import me.kavishdevar.librepods.core.bluetooth.aacp.types.MagicKeyType
import me.kavishdevar.librepods.core.data.apple.BuddyState
import me.kavishdevar.librepods.core.data.audio.MicrophoneState
import me.kavishdevar.librepods.core.data.heartrate.HeartRateSample
import me.kavishdevar.librepods.core.data.recording.RecordingState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class AppleState(
    val isLocallyConnected: Boolean = false,

    val owns: Boolean = true,

    val componentState: Set<DeviceComponentState> = emptySet(),

    val conversationAwarenessState: Int = 0,

    val capabilities: Set<CapabilityEntry> = emptySet(),

    val battery: Set<Battery> = emptySet(),
    val controlStates: Map<ControlCommandIdentifier, ByteArray> = emptyMap(),
    val requestedControls: Map<ControlCommandIdentifier, ByteArray> = emptyMap(),

    val magicKeys: Map<MagicKeyType, ByteArray> = emptyMap(),

    val headTrackingState: BuddyState = BuddyState.INACTIVE,

    val loudSoundReductionEnabled: Boolean = false,
    val transparencyData: ByteArray = byteArrayOf(),
    val hearingAidData: ByteArray = byteArrayOf(),

    val customEq: CustomEq = CustomEq(1, 50, 50, 50),
    val requestedCustomEq: CustomEq? = null,

    val microphoneState: MicrophoneState = MicrophoneState(),
    val recordingState: RecordingState = RecordingState(),

    val audioSource: AudioSource = AudioSource(MacAddress("00:00:00:00:00:00"), AudioSourceType.NONE),
    var connectedDevices: List<ConnectedDevice> = emptyList(),

    val leftIsPrimary: Boolean = true,

    val headphoneAccommodation: FloatArray = FloatArray(8),
    val headphoneAccommodationEnabledForMedia: Boolean = false,
    val headphoneAccommodationEnabledForPhone: Boolean = false,

    val currentHeartRate: HeartRateSample? = null,
    val hrmState: BuddyState = BuddyState.INACTIVE,
    val heartRateInterval: Duration = 1.seconds,

    val aacpPackets: List<AACPPacket> = emptyList(),
): DeviceState
