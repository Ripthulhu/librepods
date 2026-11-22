package me.kavishdevar.librepods.services

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import me.kavishdevar.librepods.LibrePodsApplication
import me.kavishdevar.librepods.audio.ExperimentalAudioController
import me.kavishdevar.librepods.companion.CompanionHoldStore
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.devices.Device
import me.kavishdevar.librepods.dongle.DongleController
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContextImpl
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class DongleNativeConnectionServiceTest {
    private lateinit var service: LibrePodsService
    private val dongle = mock(DongleController::class.java)
    private val device = mock(AppleDevice::class.java)
    private val bluetooth = mock(BluetoothDevice::class.java)
    private val audioController = mock(ExperimentalAudioController::class.java)
    private val nativeBridge = mock(ShizukuAudioClient::class.java)
    private val holdStore = mock(CompanionHoldStore::class.java)
    private val connection = MutableStateFlow(ConnectionState.DISCONNECTED)
    private val inhibited = AtomicBoolean(false)
    private val address = MacAddress("00:11:22:33:44:55")
    private var outputPresent = true
    private var outputAddress = address.value

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        service = Robolectric.buildService(LibrePodsService::class.java).get()
        val app = LibrePodsApplication()
        ReflectionHelpers.setField(service, "mApplication", app)
        app.experimentalAudioControllers[address] = audioController
        holdsField("ready").set(companionHolds(), true)
        field("companionHoldStore\$delegate").set(service, lazyOf(holdStore))
        field("dongleControls\$delegate").set(service, lazyOf(dongle))
        `when`(dongle.inhibitsAutomaticNative()).thenAnswer { inhibited.get() }
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        val audio = mock(AudioManager::class.java)
        val route = mock(AudioDeviceInfo::class.java)
        `when`(audio.mode).thenReturn(AudioManager.MODE_NORMAL)
        `when`(route.type).thenReturn(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        `when`(route.address).thenAnswer { outputAddress }
        `when`(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)).thenAnswer {
            if (outputPresent) arrayOf(route) else emptyArray<AudioDeviceInfo>()
        }
        Shadow.extract<ShadowContextImpl>(service.baseContext).setSystemService(Context.AUDIO_SERVICE, audio)
        `when`(device.macAddress).thenReturn(address)
        `when`(device.bluetoothDevice).thenReturn(bluetooth)
        `when`(device.connectionState).thenReturn(connection)
        `when`(device.state).thenReturn(MutableStateFlow(AppleState()))
        `when`(bluetooth.address).thenReturn(address.value)
        `when`(bluetooth.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        `when`(audioController.canRefreshSettings()).thenReturn(true)
        `when`(audioController.bridge).thenReturn(nativeBridge)
        val aacp = mock(AACPManager::class.java)
        `when`(device.aacp).thenReturn(aacp)
        `when`(aacp.captureAudioSocket()).thenReturn(mock(BluetoothSocket::class.java))
        @Suppress("UNCHECKED_CAST")
        val devices = field("_devices").get(service) as MutableStateFlow<Map<MacAddress, Device<*, *, *>>>
        devices.value = mapOf(address to device)
        @Suppress("UNCHECKED_CAST")
        val audioDevices = field("audioDevices").get(service) as ConcurrentHashMap<MacAddress, AppleDevice>
        audioDevices[address] = device
    }

    @After fun close() {
        (field("serviceScope").get(service) as CoroutineScope).cancel()
        Dispatchers.resetMain()
    }

    @Test fun heldAwayPairWithItsOwnPhoneOutputGetsOnlyControlAndKeepsItsSavedHold() = runBlocking<Unit> {
        holds().add(address)
        automaticConnect()
        verify(device).connect(any<() -> Boolean>() ?: { false })
        verify(device, never()).connect()
        verify(device, never()).disconnect()
        verify(device, never()).disconnectControl()
        assertTrue(holds().contains(address))
        verifyNoInteractions(holdStore, nativeBridge)
    }

    @Test fun aHeldPairCannotAttachControlWithoutItsExactExistingNativeOutput() = runBlocking<Unit> {
        holds().add(address)
        outputPresent = false
        automaticConnect()
        outputPresent = true
        outputAddress = "00:11:22:33:44:66"
        automaticConnect()
        verify(device, never()).connect(any<() -> Boolean>() ?: { false })
        assertTrue(holds().contains(address))
        verifyNoInteractions(holdStore, nativeBridge)
    }

    @Test fun anActiveCompanionReleaseStillBlocksPassiveControlForAHeldPair() = runBlocking<Unit> {
        holds().add(address)
        operations()[address] = Any()
        automaticConnect()
        verify(device, never()).connect(any<() -> Boolean>() ?: { false })
        assertTrue(holds().contains(address))
        verifyNoInteractions(holdStore, nativeBridge)
    }

    @Test fun aReleaseStartingDuringHeldPassiveAttachmentRevokesOnlyThatPendingControl() = runBlocking<Unit> {
        holds().add(address)
        `when`(device.connect(any<() -> Boolean>() ?: { false })).thenAnswer {
            val current = it.getArgument<() -> Boolean>(0)
            assertTrue(current())
            operations()[address] = Any()
            assertFalse(current())
            false
        }
        automaticConnect()
        assertTrue(holds().contains(address))
        verify(device, never()).disconnect()
        verifyNoInteractions(holdStore, nativeBridge)
    }

    @Test fun losingTheNativeOutputRevokesHeldPassiveAttachmentWithoutRadioAcquisition() = runBlocking<Unit> {
        holds().add(address)
        `when`(device.connect(any<() -> Boolean>() ?: { false })).thenAnswer {
            val current = it.getArgument<() -> Boolean>(0)
            assertTrue(current())
            outputPresent = false
            assertFalse(current())
            false
        }
        automaticConnect()
        assertTrue(holds().contains(address))
        verify(device, never()).disconnect()
        verifyNoInteractions(holdStore, nativeBridge)
    }

    @Test fun losingTheBondRevokesHeldPassiveAttachment() = runBlocking<Unit> {
        holds().add(address)
        `when`(device.connect(any<() -> Boolean>() ?: { false })).thenAnswer {
            val current = it.getArgument<() -> Boolean>(0)
            assertTrue(current())
            `when`(bluetooth.bondState).thenReturn(BluetoothDevice.BOND_NONE)
            assertFalse(current())
            false
        }
        automaticConnect()
        assertTrue(holds().contains(address))
        verifyNoInteractions(holdStore, nativeBridge)
    }

    @Test fun replacingTheDeviceRevokesHeldPassiveAttachment() = runBlocking<Unit> {
        holds().add(address)
        `when`(device.connect(any<() -> Boolean>() ?: { false })).thenAnswer {
            val current = it.getArgument<() -> Boolean>(0)
            assertTrue(current())
            @Suppress("UNCHECKED_CAST")
            val devices = field("_devices").get(service) as MutableStateFlow<Map<MacAddress, Device<*, *, *>>>
            devices.value = emptyMap()
            assertFalse(current())
            false
        }
        automaticConnect()
        assertTrue(holds().contains(address))
        verifyNoInteractions(holdStore, nativeBridge)
    }

    @Test fun automaticRouteAttachmentDoesNotOpenOrDisconnectNativeControlWhileDongleOwnsTheChoice() = runBlocking {
        inhibited.set(true)
        automaticConnect()
        verify(device, never()).connect(any<() -> Boolean>() ?: { false })
        verify(device, never()).disconnectControl()
    }

    @Test fun automaticAttachmentStillWorksWithoutAnAttachedOrSelectedDongle() = runBlocking {
        automaticConnect()
        verify(device).connect(any<() -> Boolean>() ?: { false })
        verify(device, never()).disconnectControl()
    }

    @Test fun aDongleArrivingDuringAnAutomaticConnectRetiresItsSocketGuard() = runBlocking {
        var guard: (() -> Boolean)? = null
        `when`(device.connect(any<() -> Boolean>() ?: { false })).thenAnswer {
            guard = it.getArgument(0)
            assertTrue(guard!!())
            inhibited.set(true)
            assertFalse(guard!!())
            false
        }
        automaticConnect()
        assertNotNull(guard)
        verify(device, never()).disconnectControl()
    }

    @Test fun anAutomaticCompanionTakeoverCannotBypassTheDongleChoice() = runBlocking {
        inhibited.set(true)
        assertFalse(service.connectNearbyDevice(device, companionRequest = true))
        verify(device, never()).connect(any<() -> Boolean>() ?: { false }, any<(BluetoothSocket) -> Unit>() ?: {})
        verify(device, never()).disconnectControl()
    }

    @Test fun anExplicitNativeConnectStillOpensItsControlSocketWithADongleAttached() = runBlocking {
        inhibited.set(true)
        val permitted = AtomicBoolean(false)
        `when`(device.connect(any<() -> Boolean>() ?: { false }, any<(BluetoothSocket) -> Unit>() ?: {})).thenAnswer {
            val current = it.getArgument<() -> Boolean>(0)
            permitted.set(current())
            if (permitted.get()) connection.value = ConnectionState.CONNECTED
            permitted.get()
        }
        assertTrue(withTimeout(2_000) { service.connectNearbyDevice(device) })
        assertTrue(permitted.get())
        verify(device, never()).disconnectControl()
    }

    @Test fun aDongleArrivingDuringACompanionTakeoverRetiresItsPendingControlGuard() = runBlocking {
        val retired = AtomicBoolean(false)
        `when`(device.connect(any<() -> Boolean>() ?: { false }, any<(BluetoothSocket) -> Unit>() ?: {})).thenAnswer {
            val current = it.getArgument<() -> Boolean>(0)
            val before = current()
            inhibited.set(true)
            retired.set(before && !current())
            false
        }
        assertFalse(withTimeout(2_000) { service.connectNearbyDevice(device, companionRequest = true) })
        assertTrue(retired.get())
        verify(device, never()).disconnectControl()
    }

    private suspend fun automaticConnect(): Unit = suspendCoroutineUninterceptedOrReturn { continuation ->
        LibrePodsService::class.java.getDeclaredMethod("onDeviceConnected", BluetoothDevice::class.java,
            Continuation::class.java).apply { isAccessible = true }.invoke(service, bluetooth, continuation)
    }
    private fun companionHolds() = field("companionHolds").get(service) as CompanionHolds
    private fun holdsField(name: String) = CompanionHolds::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun handoffHost() = field("handoffHost").get(service) as CompanionHandoffHost
    private fun handoffField(name: String) = CompanionHandoffHost::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun field(name: String) = LibrePodsService::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun holds() = holdsField("released").get(companionHolds()) as MutableSet<MacAddress>

    @Suppress("UNCHECKED_CAST")
    private fun operations() = handoffField("operations").get(handoffHost()) as ConcurrentHashMap<MacAddress, Any>
}
