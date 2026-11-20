package me.kavishdevar.librepods.dongle

/**
 * Wire constants of the dongle control protocol.
 *
 * Names follow the firmware spec `protocol/dongle-protocol.json` ("airpods-dongle-control") in Kotlin
 * casing: a spec section becomes a nested object and each `name` becomes an UPPER_SNAKE constant.
 * Sections marked "documented only" come from the firmware's written references
 * (CONTROL-EXTENSIONS.md, HOST-VOLUME.md) because the JSON spec does not describe them yet.
 */
internal object DongleControlProtocol {
    /** ASCII "APDG", bytes 1..4 of every report and bytes 0..3 of every phone transport frame. */
    val MAGIC = byteArrayOf(65, 80, 68, 71)
    const val PROTOCOL_VERSION = 1

    object Frame {
        const val SIZE = 64
        const val REPORT_ID_OFFSET = 0
        const val MAGIC_OFFSET = 1
        const val VERSION_OFFSET = 5
        const val LENGTH_OFFSET = 6
        /** Report id, magic, version and length. */
        const val HEADER_SIZE = 7
    }

    /** Report 2: basic commands and the status snapshot. */
    object Control {
        const val REPORT_ID = 2

        object CommandField {
            const val OPCODE_OFFSET = 7
            const val CONNECTION_GENERATION_OFFSET = 8
            const val SELECTED_ADDRESS_OFFSET = 12
            const val COMMAND_SEQUENCE_OFFSET = 18
        }

        object Opcode {
            const val RECONNECT_AIRPODS = 1
            const val PAIR_AIRPODS = 2
            const val CHANGE_LISTENING_MODE = 3
            const val OPEN_PHONE_PAIRING = 4
        }

        object StatusField {
            const val FLAGS_OFFSET = 7
            const val CONNECTION_GENERATION_OFFSET = 8
            const val SELECTED_ADDRESS_OFFSET = 12
            const val SELECTED_ADDRESS_SIZE = 6
            const val LISTENING_MODE_OFFSET = 18
            const val BATTERY_PERCENT_OFFSET = 19
            const val CHARGING_COMPONENTS_OFFSET = 22
            const val OUTPUT_CODEC_OFFSET = 23
            const val BATTERY_AGES_MILLISECONDS_OFFSET = 24
            const val LAST_COMMAND_SEQUENCE_OFFSET = 36
            const val LAST_COMMAND_RESULT_OFFSET = 40
            const val LAST_COMMAND_OPCODE_OFFSET = 41
            const val CAPABILITIES_OFFSET = 42
            const val PRESENT_COMPONENTS_OFFSET = 44
            const val CASE_OBSERVED_COMPONENTS_OFFSET = 45
            const val RESERVED_OFFSET = 46
        }

        object StatusFlag {
            const val A2DP_CONNECTED = 1
            const val AACP_READY = 2
            const val STREAMING = 4
            const val PHONE_CONNECTED = 8
            const val MICROPHONE_ACTIVE = 16
            const val PHONE_PAIRING = 32
            const val USB_PLAYBACK = 64
            /** The one flag bit the spec leaves undefined; it must be clear. */
            const val UNDEFINED = 128
        }

        object Capability {
            const val PAIR_AND_RECONNECT = 1
            const val CONFIRMED_NOISE_CONTROL = 2
            const val PHONE_PAIRING = 4
            const val SETTINGS_TRANSPORT = 8
            const val TYPED_FEATURES = 16
            const val SETTINGS_WORKFLOW_LEASE = 32
            const val BATTERY_OBSERVATION_FLAGS = 64
            const val USB_HOST_VOLUME_AUTHORITY = 128
            const val ACTIVE_AUDIO_RUNTIME_UPDATE = 256
            const val EXPLICIT_ROUTING_HANDOVER = 512
        }

        const val BATTERY_UNKNOWN = 255
        const val AGE_UNKNOWN = 0xffff_ffffL

        /** Components in status order: battery slot index and component bit. */
        object Component {
            const val LEFT_SLOT = 0
            const val RIGHT_SLOT = 1
            const val CASE_SLOT = 2
            const val SLOT_COUNT = 3
            /** Every bit above left (1), right (2) and case (4). */
            const val UNDEFINED_BITS = 0xf8
        }

        object ListeningMode {
            const val UNKNOWN = 0
            const val OFF = 1
            const val ANC = 2
            const val TRANSPARENCY = 3
            const val ADAPTIVE = 4
        }

        object Codec {
            const val UNKNOWN = 0
            const val SBC = 1
            const val AAC = 2
            const val AAC_ELD = 3
        }

        object Result {
            const val NONE = 0
            const val ACCEPTED = 1
            const val BUSY = 2
            const val STALE = 3
            const val UNSUPPORTED = 4
            const val DISCONNECTED = 5
            const val MALFORMED = 6
        }
    }

    /** Report 3: the fragmented settings RPC. */
    object Rpc {
        const val REPORT_ID = 3

        object Field {
            const val OPERATION_OFFSET = 7
            const val CONNECTION_GENERATION_OFFSET = 8
            const val TRANSACTION_SEQUENCE_OFFSET = 12
            const val TOTAL_LENGTH_OFFSET = 16
            const val FRAGMENT_OFFSET_OFFSET = 18
            const val FRAGMENT_LENGTH_OFFSET = 20
            const val RESPONSE_PHASE_OFFSET = 21
            const val PAYLOAD_OFFSET = 22
            /** A failed response carries its error code as the first payload byte. */
            const val ERROR_OFFSET = PAYLOAD_OFFSET
        }

        object Operation {
            const val SEND_ATT_REQUEST = 1
            const val SEND_AACP_PACKET = 2
            const val READ_CACHED_REPORT = 3
            const val SELECT_RESPONSE_PAGE = 4
            const val CANCEL_TRANSACTION = 5
            const val COMMIT_TRANSACTION = 6
            const val TYPED_FEATURE_REQUEST = 7
        }

        object Phase {
            const val IDLE = 0
            const val ASSEMBLING = 1
            const val RUNNING = 2
            const val COMPLETE = 3
            const val FAILED = 4
        }

        object Error {
            const val MALFORMED = 1
            const val BUSY = 2
            const val STALE = 3
            const val DISCONNECTED = 4
            const val TIMEOUT = 5
            const val TRANSPORT = 6
            const val UNSUPPORTED = 7
            const val CANCELLED = 8
        }

        const val MAXIMUM_FRAGMENT_LENGTH = 42
        const val MAXIMUM_PAYLOAD_LENGTH = 512
    }

    /** RFCOMM framing used by the bonded phone link. */
    object PhoneTransport {
        const val UUID = "c6c4f8a0-6d47-4bd1-9b6a-415044470001"
        const val HEADER_SIZE = 8
        const val MAGIC_OFFSET = 0
        const val VERSION_OFFSET = 4
        const val OPERATION_OFFSET = 5
        const val REPORT_ID_OFFSET = 6
        const val LENGTH_OFFSET = 7

        object Operation {
            const val READ_REPORT = 1
            const val WRITE_REPORT = 2
        }

        const val ERROR_OPERATION = 255
        const val RESPONSE_OPERATION_FLAG = 128

        object Error {
            const val MALFORMED = 1
            const val BUSY = 2
            const val UNAVAILABLE = 3
        }
    }

    /** First byte of a typed feature request (RPC operation 7). */
    object Feature {
        const val WRITE_SETTING = 1
        const val READ_SETTINGS_SNAPSHOT = 2
        const val CONFIGURE_AUDIO_PROFILE = 3
        const val SET_GAME_MODE = 4
        const val READ_PERFORMANCE = 5
        const val START_NEARBY_FINDER = 6
        const val READ_NEARBY_FINDER = 7
        const val STOP_NEARBY_FINDER = 8
        const val READ_AUDIO_PROFILE = 9
        const val SETTINGS_WORKFLOW_LEASE = 10
        const val READ_PHYSICAL_VOLUME = 16
        const val USB_HOST_VOLUME_AUTHORITY = 17
    }

    object CachedSlot {
        const val MODEL_METADATA = 1
        const val HEARING_AID = 2
        const val HEARING_ASSISTANCE_GATE = 3
        const val PME = 4
        const val EAR_STATE = 5
    }

    /** Age u32, revision u32, then the original AACP packet. */
    object CachedReport {
        const val AGE_MILLISECONDS_OFFSET = 0
        const val REVISION_OFFSET = 4
        const val ENVELOPE_SIZE = 8
    }

    object SettingsSnapshot {
        const val VERSION = 1
        const val HEADER_SIZE = 2
        const val COUNT_OFFSET = 1
        const val RECORD_SIZE = 13
        const val IDENTIFIER_OFFSET = 0
        const val AGE_MILLISECONDS_OFFSET = 1
        const val REVISION_OFFSET = 5
        const val VALUE_OFFSET = 9
    }

    object SettingsLeaseAction {
        const val ACQUIRE = 0
        const val RENEW = 1
        const val RELEASE = 2
    }

    const val SETTINGS_LEASE_RESPONSE_VERSION = 1

    /**
     * Value domains of the AACP settings the dongle relays (spec `settings[].domain`).
     * Setting ids themselves are [me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier].
     */
    object SettingValue {
        /** Ordinary toggles; ear detection reverses them (ControlCommandIdentifier.encodeBoolean). */
        const val TOGGLE_ON = 1L
        const val TOGGLE_OFF = 2L
        /** Microphone, press speed, hold duration and swipe duration choose from 0 (default) to 2. */
        const val THREE_WAY_CHOICE_MAXIMUM = 2L

        const val LISTENING_MODE_OFF = 1L
        const val LISTENING_MODE_ANC = 2L
        const val LISTENING_MODE_TRANSPARENCY = 3L
        const val LISTENING_MODE_ADAPTIVE = 4L

        /** Hold-mode cycle mask bits: off 1, ANC 2, transparency 4, adaptive 8; at least two selected. */
        const val HOLD_MODES_ALL = 15L
        const val HOLD_MODES_MINIMUM_SELECTED = 2

        /**
         * Hold actions as this client encodes them: right ear in the low byte, left ear in the high byte,
         * 1 digital assistant, 5 noise control. The firmware spec currently states the reverse; see
         * docs/control_commands.md and android/PROTOCOL.md for the evidence this client follows.
         */
        const val HOLD_ACTION_DIGITAL_ASSISTANT = 1L
        const val HOLD_ACTION_NOISE_CONTROL = 5L
        const val HOLD_ACTION_MASK = 0xffL
        const val RIGHT_HOLD_ACTION_SHIFT = 0
        const val LEFT_HOLD_ACTION_SHIFT = 8
        /** Reports may carry extra bytes above the two hold actions. */
        const val HOLD_ACTIONS_REPORT_MASK = 0xffffL

        const val CALL_CONTROLS_SWAPPED = 0x0200L
        const val CALL_CONTROLS_DEFAULT = 0x0300L

        /** Tone volume is sent as this flag plus a percentage. */
        const val TONE_VOLUME_FLAG = 0x5000L
        const val MAXIMUM_PERCENT = 100L
    }

    /** Documented only: USB identity and the companion HID collection (USB-CONTROL.md, firmware descriptors). */
    object Usb {
        const val VENDOR_ID = 0xcafe
        /** The personality with the USB audio interface. */
        const val AUDIO_PRODUCT_ID = 0x4015
        /** The control-only personality; it shares the board serial with the audio one. */
        const val CONTROL_ONLY_PRODUCT_ID = 0x4005
        const val VENDOR_USAGE_PAGE = 0xff31
        const val VENDOR_USAGE = 1
        /** Feature reports are 63 data bytes of 8 bits after the report id. */
        const val REPORT_SIZE_BITS = 8
        const val REPORT_COUNT = 63
    }

    /** Documented only: the 20-byte audio profile status (CONTROL-EXTENSIONS.md). */
    object AudioProfile {
        const val SIZE = 20
        const val VERSION = 1
        const val REQUESTED_CODEC_OFFSET = 1
        const val ACTUAL_CODEC_OFFSET = 2
        const val RATE_MODE_OFFSET = 3
        const val REQUESTED_BITRATE_OFFSET = 4
        const val ACTUAL_BITRATE_OFFSET = 8
        const val GAME_MODE_REQUESTED_OFFSET = 12
        const val GAME_MODE_STATE_OFFSET = 13
        const val INPUT_OWNERSHIP_OFFSET = 14
        const val FLAGS_OFFSET = 15
        const val DELAY_TENTHS_MILLISECONDS_OFFSET = 16
        const val STARTUP_PRIMING_MILLISECONDS_OFFSET = 18
        const val STARTUP_PRIMING_MILLISECONDS = 100
        const val DELAY_UNKNOWN = 0xffff

        /** Highest valid rate mode: 0 constant, 1 variable bitrate. */
        const val RATE_MODE_VARIABLE = 1
        /** Highest valid Game Mode state (restoring). */
        const val GAME_MODE_STATE_RESTORING = 6

        object InputOwnership {
            const val IDLE = 0
            const val USB = 1
            const val PHONE = 2
        }

        object Flag {
            const val PROFILE_PENDING = 1
            const val STREAMING = 2
            const val MICROPHONE = 4
            const val GAME_MODE_SUPPORTED = 8
            const val UNDEFINED_BITS = 0xf0
        }
    }

    /** Documented only: the 64-byte performance snapshot (CONTROL-EXTENSIONS.md). */
    object Performance {
        const val SIZE = 64
        const val VERSION = 1
        const val INPUT_OWNERSHIP_OFFSET = 1
        const val ACTUAL_CODEC_OFFSET = 2
        const val FLAGS_OFFSET = 3
        const val FIRST_FIELD_OFFSET = 4
        const val LAST_FIELD_OFFSET = 60
        const val FLAG_UNDEFINED_BITS = 0xf0
        const val UNAVAILABLE = 0xffff_ffffL

        /** Index into the u32 fields that start at [FIRST_FIELD_OFFSET]. */
        object FieldIndex {
            const val PCM_FRAMES_USED = 1
            const val PCM_FRAMES_CAPACITY = 2
            const val PCM_FRAMES_HIGH_WATER_MARK = 3
            const val LINKED_HEAP_CAPACITY = 11
            const val HEAP_ALLOCATED_BYTES = 12
            const val HEAP_REMAINING_BYTES = 13
        }
    }

    /** Documented only: the 24-byte nearby finder snapshot (CONTROL-EXTENSIONS.md). */
    object Nearby {
        const val SIZE = 24
        const val VERSION = 1
        const val STATE_OFFSET = 1
        const val RSSI_OFFSET = 2
        const val TREND_OFFSET = 3
        const val LAST_HEARD_AGE_OFFSET = 4
        const val MATCHED_REPORTS_OFFSET = 8
        const val PAUSE_FLAGS_OFFSET = 12
        const val SESSION_OFFSET = 16
        const val SMOOTHED_RSSI_OFFSET = 20

        const val STATE_MASK = 0x7f
        const val IDENTITY_AVAILABLE_FLAG = 0x80
        const val STATE_STOPPED = 0
        const val STATE_PAUSED = 4
        const val TREND_WEAKER = 3
        const val RSSI_UNKNOWN = 127
        const val SMOOTHED_RSSI_UNKNOWN = Int.MIN_VALUE
        /** Smoothed RSSI is Q8 fixed point. */
        const val SMOOTHED_RSSI_SCALE = 256.0
        const val AGE_UNKNOWN = 0xffff_ffffL
    }

    /** Documented only: the 24-byte physical volume snapshot (HOST-VOLUME.md). */
    object PhysicalVolume {
        const val SIZE = 24
        const val VERSION = 1
        const val FLAGS_OFFSET = 1
        const val CONFIRMED_LEVEL_OFFSET = 2
        const val DESIRED_LEVEL_OFFSET = 3
        const val OBSERVATION_REVISION_OFFSET = 4
        const val AUTHORITY_TOKEN_OFFSET = 8
        const val REMAINING_LEASE_MILLISECONDS_OFFSET = 12
        const val USB_MEDIA_GENERATION_OFFSET = 16
        const val RESERVED_OFFSET = 20

        const val LEVEL_UNKNOWN = 255
        const val MAXIMUM_LEVEL = 127
        const val LEASE_MILLISECONDS = 5_000L

        object Flag {
            const val KNOWN_PHYSICAL_GAIN = 1
            const val PENDING_REQUEST = 2
            const val IN_FLIGHT_REQUEST = 4
            const val ACTIVE_AUTHORITY = 8
            const val ELIGIBLE_USB_SCOPE = 16
            const val UNDEFINED_BITS = 0xe0
        }
    }

    /** Documented only: feature 17 request layout and actions (HOST-VOLUME.md). */
    object VolumeAuthority {
        const val REQUEST_SIZE = 7
        const val ACTION_OFFSET = 1
        const val TOKEN_OFFSET = 2
        const val LEVEL_OFFSET = 6

        object Action {
            const val ACQUIRE = 0
            const val RENEW = 1
            const val RELEASE = 2
            const val SET = 3
        }
    }
}
