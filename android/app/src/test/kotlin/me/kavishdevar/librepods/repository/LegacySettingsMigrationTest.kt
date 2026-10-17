package me.kavishdevar.librepods.repository

import me.kavishdevar.librepods.core.bluetooth.aacp.types.MagicKeyType
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class LegacySettingsMigrationTest {
    private val address = "12:34:56:78:9A:BC"
    private val key = Base64.getEncoder().encodeToString(ByteArray(16) { (it + 1).toByte() })

    @Test fun retainsLocalChoicesAndOnlyOwnerBoundValidKeys() {
        val entity = requireNotNull(LegacySettingsMigration.device(mapOf("mac_address" to address,
            "proximity_key_device_address" to address, "IRK" to key, "ENC_KEY" to "invalid",
            "show_island_popup" to false, "takeover_when_call" to false,
            "conversational_awareness_volume" to 200)))
        assertFalse(entity.settings.showIslandPopup)
        assertFalse(entity.settings.takeoverWhenCall)
        assertEquals(100f, entity.settings.conversationAwarenessVolume, 0.001f)
        assertEquals(setOf(MagicKeyType.IRK), entity.cache.magicKeys.keys)
        assertTrue(entity.cache.controlStates.isEmpty())
    }

    @Test fun refusesKeysFromAnotherPairAndMalformedOrEmptyIdentity() {
        assertNull(LegacySettingsMigration.device(mapOf("mac_address" to "bad")))
        assertNull(LegacySettingsMigration.device(mapOf("mac_address" to "00:00:00:00:00:00")))
        val entity = requireNotNull(LegacySettingsMigration.device(mapOf("mac_address" to address,
            "proximity_key_device_address" to "12:34:56:78:9A:BD", "IRK" to key)))
        assertTrue(entity.cache.magicKeys.isEmpty())
        assertFalse(entity.settings.takeoverWhenMediaStart)
    }

    @Test fun normalizesAcceptedIdentityToNativeAndroidAddress() {
        val entity = requireNotNull(LegacySettingsMigration.device(mapOf("mac_address" to address.lowercase(),
            "proximity_key_device_address" to address.lowercase(), "IRK" to key)))
        assertEquals(address, entity.macAddress.value)
        assertEquals(setOf(MagicKeyType.IRK), entity.cache.magicKeys.keys)
    }
}
