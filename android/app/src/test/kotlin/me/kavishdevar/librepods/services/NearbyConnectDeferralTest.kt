package me.kavishdevar.librepods.services

import android.app.Application
import android.bluetooth.BluetoothProfile
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.devices.AppleDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class NearbyConnectDeferralTest {
    private val address = MacAddress("00:11:22:33:44:55")
    private val device = mock(AppleDevice::class.java).also { `when`(it.macAddress).thenReturn(address) }
    private var now = 100_000L
    private var current = true
    private var audio = false
    private val deferral = NearbyConnectDeferral({ current }, { audio }, { now })

    @Test fun reopeningRightAfterALocalCloseDefersConnectForFourSeconds() {
        audio = true
        assertTrue(deferral.observeCase(device, open = true))
        assertFalse(deferral.observeCase(device, open = false))
        audio = false
        assertTrue(deferral.observeCase(device, open = true))
        assertTrue(deferral.shouldDefer(device))
        now += 3_999
        assertTrue(deferral.shouldDefer(device))
        now += 1
        assertFalse(deferral.shouldDefer(device))
    }

    @Test fun repeatedLidReportsAreNotNewOpenings() {
        assertTrue(deferral.observeCase(device, open = true))
        assertFalse(deferral.observeCase(device, open = true))
        deferral.forgetLid(address)
        assertTrue(deferral.observeCase(device, open = true))
    }

    @Test fun nativeConnectingDefersUntilItFailsAndAFailedReconnectClearsTheCaseGrace() {
        audio = true
        deferral.observeCase(device, open = false)
        audio = false
        deferral.observeMedia(device, BluetoothProfile.STATE_CONNECTING)
        assertEquals(BluetoothProfile.STATE_CONNECTING, deferral.mediaState(address))
        now += 17_999
        assertTrue(deferral.shouldDefer(device))

        deferral.observeMedia(device, BluetoothProfile.STATE_DISCONNECTED)
        assertFalse(deferral.shouldDefer(device))
        // The failed reconnect retired the earlier local close, so reopening is immediately connectable.
        assertTrue(deferral.observeCase(device, open = true))
        assertFalse(deferral.shouldDefer(device))
    }

    @Test fun connectedAudioNeverDefersAndRetiresPendingDeadlines() {
        deferral.observeMedia(device, BluetoothProfile.STATE_CONNECTING)
        audio = true
        assertFalse(deferral.shouldDefer(device))
        audio = false
        assertFalse("The connected output retired the connecting deadline", deferral.shouldDefer(device))
    }

    @Test fun aReplacedOrUnbondedPairIsIgnored() {
        current = false
        assertFalse(deferral.observeCase(device, open = true))
        deferral.observeMedia(device, BluetoothProfile.STATE_CONNECTING)
        assertNull(deferral.mediaState(address))
        current = true
        deferral.observeMedia(device, BluetoothProfile.STATE_CONNECTING)
        current = false
        assertFalse(deferral.shouldDefer(device))
    }

    @Test fun resettingADeviceDropsItsMediaAndCaseHistory() {
        deferral.observeMedia(device, BluetoothProfile.STATE_CONNECTING)
        deferral.resetDevice(address)
        assertNull(deferral.mediaState(address))
        assertFalse(deferral.shouldDefer(device))
    }
}
