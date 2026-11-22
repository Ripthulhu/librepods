package me.kavishdevar.librepods.services

import android.app.Application
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import me.kavishdevar.librepods.audio.ExperimentalAudioController
import me.kavishdevar.librepods.bluetooth.DeviceSettingsRefreshResult
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AppleState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class SettingsRefresherTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val address = MacAddress("00:11:22:33:44:55")
    private val device = mock(AppleDevice::class.java)
    private val aacp = mock(AACPManager::class.java)
    private val controller = mock(ExperimentalAudioController::class.java)
    private val request = mock(AACPManager.ControlSessionRefresh::class.java)
    @Volatile private var current = true
    private val refresher = SettingsRefresher(scope, { false }, { controller.takeIf { _ -> it == address } },
        { current }, { false }, { false }, { false })

    init {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        `when`(device.macAddress).thenReturn(address)
        `when`(device.aacp).thenReturn(aacp)
        `when`(device.state).thenReturn(MutableStateFlow(AppleState()))
        `when`(aacp.captureAudioSocket()).thenReturn(mock(BluetoothSocket::class.java))
        `when`(aacp.prepareControlSessionRefresh()).thenReturn(request)
        `when`(controller.canRefreshSettings()).thenReturn(true)
        `when`(controller.tryBeginSettingsRefresh()).thenReturn(true)
    }

    @After fun close() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    @Test fun secondRefreshOfTheSamePairIsRejectedAndTheReservationEndsOnce() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        `when`(aacp.refreshControlSession(any(AACPManager.ControlSessionRefresh::class.java) ?: request, any<() -> Boolean>() ?: { true })).thenAnswer {
            entered.complete(Unit)
            release.await(5, TimeUnit.SECONDS)
            true
        }
        val first = async(Dispatchers.IO) { refresher.refresh(device) }
        withTimeout(5_000) { entered.await() }

        assertTrue(refresher.anyActive())
        assertTrue(refresher.isRefreshing(address))
        assertEquals(DeviceSettingsRefreshResult.ALREADY_RUNNING, refresher.readiness(device))
        assertEquals(DeviceSettingsRefreshResult.ALREADY_RUNNING, refresher.refresh(device))

        release.countDown()
        assertEquals(DeviceSettingsRefreshResult.RECONNECTED, withTimeout(5_000) { first.await() })
        assertFalse(refresher.anyActive())
        verify(controller, times(1)).endSettingsRefresh()
        verify(aacp, never()).cancelControlSessionRefresh(any(AACPManager.ControlSessionRefresh::class.java) ?: request)
    }

    @Test fun watchdogClosesTheReopenWhenThePairStopsBeingCurrent() = runBlocking<Unit> {
        val cancelled = CountDownLatch(1)
        doAnswer { cancelled.countDown() }.`when`(aacp).cancelControlSessionRefresh(any(AACPManager.ControlSessionRefresh::class.java) ?: request)
        `when`(aacp.refreshControlSession(any(AACPManager.ControlSessionRefresh::class.java) ?: request, any<() -> Boolean>() ?: { true })).thenAnswer {
            current = false
            // Stands in for a blocked socket.connect() that only the watchdog's cancel can end.
            cancelled.await(5, TimeUnit.SECONDS)
            false
        }

        assertEquals(DeviceSettingsRefreshResult.NOT_CONNECTED, withTimeout(5_000) { refresher.refresh(device) })
        assertEquals(0, cancelled.count)
        assertFalse(refresher.anyActive())
        verify(controller, times(1)).endSettingsRefresh()
    }

    @Test fun controllerRefusalLeavesNoReservation() = runBlocking<Unit> {
        `when`(controller.tryBeginSettingsRefresh()).thenReturn(false)

        assertEquals(DeviceSettingsRefreshResult.AUDIO_BUSY, refresher.refresh(device))
        assertFalse(refresher.anyActive())
        verify(controller, never()).endSettingsRefresh()
        verify(aacp, never()).prepareControlSessionRefresh()
    }
}
