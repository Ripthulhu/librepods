/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.bluetooth

enum class DeviceSettingsRefreshResult {
    RECONNECTED, ALREADY_RUNNING, NOT_CONNECTED, AUDIO_BUSY, CALL_ACTIVE, UNAVAILABLE, FAILED
}
