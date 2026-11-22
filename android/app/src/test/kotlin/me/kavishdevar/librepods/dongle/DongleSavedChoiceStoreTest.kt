package me.kavishdevar.librepods.dongle

import android.app.Application
import android.content.Context
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.devices.AirPodsModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class DongleSavedChoiceStoreTest {
    private val preferences = RuntimeEnvironment.getApplication()
        .getSharedPreferences("dongle-control", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
    private val store = DongleSavedChoiceStore(preferences)
    private val holdModes = ControlCommandIdentifier.LISTENING_MODE_CONFIGS.settingId
    private val allowOff = ControlCommandIdentifier.ALLOW_OFF_OPTION.settingId

    private fun status(vararg address: Int) =
        DongleProtocol.Status(1, address.toList(), 0, 0, listOf(null, null, null), 0, 0)

    @Test fun choicesAreKeptPerPairInTheExistingPreferenceFormat() {
        val first = status(1, 0, 0, 0, 0, 0xab)
        val second = status(2, 0, 0, 0, 0, 0)
        assertEquals("0100000000ab", store.peer(first))
        assertTrue(store.save("0100000000ab", holdModes, 14))
        assertEquals(14L, preferences.getLong("choice:0100000000ab:$holdModes", -1))
        assertEquals(mapOf(holdModes to 14L), store.load(first))
        assertTrue(store.load(second).isEmpty())
    }

    @Test fun placeholderAddressesHaveNoChoices() {
        assertNull(store.peer(null))
        assertNull(store.peer(status(0, 0, 0, 0, 0, 0)))
        assertNull(store.peer(status(255, 255, 255, 255, 255, 255)))
        assertNull(store.peer(status(1, 2, 3)))
        assertTrue(store.load(status(0, 0, 0, 0, 0, 0)).isEmpty())
    }

    @Test fun invalidOrUnreviewedStoredValuesAreIgnored() {
        val pair = status(1, 0, 0, 0, 0, 0)
        val listeningMode = ControlCommandIdentifier.LISTENING_MODE.settingId
        preferences.edit()
            .putLong("choice:010000000000:$holdModes", 1) // only one hold mode selected
            .putLong("choice:010000000000:$listeningMode", 2) // not a remembered setting
            .putString("choice:010000000000:$allowOff", "on") // wrong stored type
            .commit()
        assertTrue(store.load(pair).isEmpty())
    }

    @Test fun allowOffIsOnlyRememberedForAirPodsProThree() {
        assertTrue(store.persists(allowOff, AirPodsModel.AIRPODS_PRO_3))
        assertFalse(store.persists(allowOff, AirPodsModel.AIRPODS_PRO_2_USBC))
        assertFalse(store.persists(allowOff, null))
        assertTrue(store.persists(holdModes, null))
        assertFalse(store.persists(ControlCommandIdentifier.LISTENING_MODE.settingId, AirPodsModel.AIRPODS_PRO_3))
    }
}
