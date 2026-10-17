/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.repository

import android.content.Context
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.types.MagicKeyType
import me.kavishdevar.librepods.core.data.StemAction
import me.kavishdevar.librepods.core.data.apple.AppleCache
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.AppleSettings
import me.kavishdevar.librepods.database.LibrePodsDatabase
import me.kavishdevar.librepods.database.app.AppSettingsEntity
import me.kavishdevar.librepods.database.app.AppStateEntity
import me.kavishdevar.librepods.database.apple.AppleEntity
import java.util.Base64
import java.util.Locale

/** Preserve local choices and authenticated keys when upgrading the pre-rewrite app in place. */
internal object LegacySettingsMigration {
    suspend fun migrate(context: Context, database: LibrePodsDatabase) {
        val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val old = preferences.all
        if (old.isEmpty() || old["rewrite_migrated"] == true) return
        if (database.appSettingsDao().get() == null) database.appSettingsDao().upsert(AppSettingsEntity())
        if (database.appStateDao().get() == null) database.appStateDao().upsert(AppStateEntity(
            hasCompletedOnboarding = old["onboarding_complete"] == true,
            hasConnectedToAACP = old["connection_successful"] == true,
            firstSuccessfulConnectionTime = (old["first_connection_successful_time"] as? Long)?.takeIf { it > 0 },
            isPremium = !me.kavishdevar.librepods.BuildConfig.PLAY_BUILD && old["foss_upgraded"] == true,
        ))
        device(old)?.let { entity ->
            if (database.appleDao().get(entity.macAddress) == null) database.appleDao().upsert(entity)
        }
        check(preferences.edit().putBoolean("rewrite_migrated", true).commit())
    }

    internal fun device(old: Map<String, *>): AppleEntity? {
        val address = (old["mac_address"] as? String)?.takeIf {
            Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}").matches(it) && it != "00:00:00:00:00:00"
        }?.uppercase(Locale.ROOT) ?: return null
        fun bool(key: String, fallback: Boolean) = old[key] as? Boolean ?: fallback
        fun text(key: String) = old[key] as? String ?: ""
        fun action(key: String) = StemAction.fromString(text(key)) ?: StemAction.CYCLE_NOISE_CONTROL_MODES
        val keys = if (text("proximity_key_device_address").equals(address, ignoreCase = true)) {
            MagicKeyType.entries.mapNotNull { type ->
                val key = runCatching { Base64.getDecoder().decode(text(type.name)) }.getOrNull()
                key?.takeIf { it.size == 16 && it.any { byte -> byte != 0.toByte() } }?.let { type to it }
            }.toMap()
        } else emptyMap()
        val modelNumber = text("airpods_model_number")
        return AppleEntity(MacAddress(address), AppleSettings(
            disconnectWhenNotWearing = bool("disconnect_when_not_wearing", false),
            earDetectionEnabled = bool("automatic_ear_detection", true),
            headGesturesEnabled = bool("head_gestures", true),
            leftLongPressAction = action("left_long_press_action"),
            rightLongPressAction = action("right_long_press_action"),
            showIslandPopup = bool("show_island_popup", true),
            showBottomSheetPopup = bool("show_bottom_sheet_popup", true),
            takeoverWhenDisconnected = bool("takeover_when_disconnected", false),
            takeoverWhenIdle = bool("takeover_when_idle", false),
            takeoverWhenMusic = bool("takeover_when_music", false),
            takeoverWhenCall = bool("takeover_when_call", false),
            takeoverWhenRingingCall = bool("takeover_when_ringing_call", false),
            takeoverWhenMediaStart = bool("takeover_when_media_start", false),
            conversationAwarenessPauseMusicEnabled = bool("conversational_awareness_pause_music", false),
            relativeConversationAwarenessVolumeEnabled = bool("relative_conversational_awareness_volume", true),
            conversationAwarenessVolume = (old["conversational_awareness_volume"] as? Number)?.toFloat()?.coerceIn(0f, 100f) ?: 43f,
        ), AppleMetadata(
            name = text("name"), model = AirPodsModel.fromModelNumber(modelNumber), modelNumber = modelNumber,
            manufacturer = text("airpods_manufacturer"), serialNumber = text("airpods_serial_number"),
            leftSerialNumber = text("airpods_left_serial_number"), rightSerialNumber = text("airpods_right_serial_number"),
            version1 = text("airpods_version1"), version2 = text("airpods_version2"), version3 = text("airpods_version3"),
            hardwareRevision = text("airpods_hardware_revision"), updaterIdentifier = text("airpods_updater_identifier"),
        ), AppleCache(magicKeys = keys))
    }
}
