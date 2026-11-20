package me.kavishdevar.librepods.services

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.kavishdevar.librepods.LibrePodsApplication
import me.kavishdevar.librepods.companion.CompanionHoldStore
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.core.devices.Device
import me.kavishdevar.librepods.shizuku.BridgeState
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient
import me.kavishdevar.librepods.utils.NativeGameModeController
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
class CompanionReleaseServiceTest {
    private lateinit var service: LibrePodsService
    private lateinit var app: LibrePodsApplication
    private val bridge = mock(ShizukuAudioClient::class.java)
    private val game = mock(NativeGameModeController::class.java)
    private val device = mock(AppleDevice::class.java)
    private val bluetooth = mock(BluetoothDevice::class.java)
    private val address = MacAddress("AA:BB:CC:DD:EE:FF")
    private val outputPresent = AtomicBoolean(true)
    private val intentCurrent = AtomicBoolean(true)

    @Before fun setUp() = runBlocking<Unit> {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val real = Robolectric.buildService(LibrePodsService::class.java).get()
        service = spy(real)
        app = LibrePodsApplication()
        val holdStore = mock(CompanionHoldStore::class.java)
        // The service's helper classes call back into the real instance, not the spy, so both get the fakes.
        for (target in listOf(real, service)) {
            ReflectionHelpers.setField(target, "mApplication", app)
            field("companionBridge\$delegate").set(target, lazyOf(bridge))
            field("companionHoldStore\$delegate").set(target, lazyOf(holdStore))
        }
        holdsField("ready").set(companionHolds(), true)
        `when`(bridge.hasRootPrivileges).thenReturn(true)
        `when`(bridge.state).thenReturn(MutableStateFlow(BridgeState(connected = true)))
        `when`(game.state).thenReturn(MutableStateFlow(NativeGameModeController.State()))
        `when`(game.suspendForHandover()).thenReturn(true)
        app.nativeGameModeControllers[address] = game
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.READ_PHONE_STATE)
        val manager = mock(BluetoothManager::class.java)
        val adapter = mock(BluetoothAdapter::class.java)
        `when`(manager.adapter).thenReturn(adapter)
        `when`(adapter.isEnabled).thenReturn(true)
        val audio = mock(AudioManager::class.java)
        val output = mock(AudioDeviceInfo::class.java)
        `when`(audio.mode).thenReturn(AudioManager.MODE_NORMAL)
        `when`(output.type).thenReturn(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        `when`(output.address).thenReturn(address.value)
        `when`(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)).thenAnswer {
            if (outputPresent.get()) arrayOf(output) else emptyArray<AudioDeviceInfo>()
        }
        val context = Shadow.extract<ShadowContextImpl>(service.baseContext)
        context.setSystemService(Context.BLUETOOTH_SERVICE, manager)
        context.setSystemService(Context.AUDIO_SERVICE, audio)
        `when`(device.macAddress).thenReturn(address)
        `when`(device.bluetoothDevice).thenReturn(bluetooth)
        `when`(device.state).thenReturn(MutableStateFlow(AppleState()))
        `when`(device.aacp).thenReturn(mock(AACPManager::class.java))
        `when`(bluetooth.address).thenReturn(address.value)
        `when`(bluetooth.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        devices().value = mapOf(address to device)
        doNothing().`when`(service).updateDeviceNotification(device)
    }

    @After fun close() {
        (field("serviceScope").get(service) as CoroutineScope).cancel()
        Dispatchers.resetMain()
    }

    @Test fun theOwnedAclDisconnectCanRetireGameModeWithoutRevokingItsReleaseReceipt() = runTest {
        `when`(bridge.releaseNativePlayback(eq(address.value) ?: address.value, anyCurrent())).thenAnswer { invocation ->
            val current = invocation.getArgument<() -> Boolean>(1)
            assertTrue(current())
            outputPresent.set(false)
            disconnectCallback()
            assertFalse(app.nativeGameModeControllers.containsKey(address))
            assertTrue("Owned cleanup must not revoke the same release", current())
            true
        }
        assertEquals("released", release())
        verify(game).close()
        verify(game, never()).resumeAfterMicrophone()
        verify(device).disconnectControl()
        assertNoTakeoverMutations()
    }

    @Test fun replacingTheGameControllerStillRevokesTheRelease() = runTest {
        val replacement = mock(NativeGameModeController::class.java)
        `when`(bridge.releaseNativePlayback(eq(address.value) ?: address.value, anyCurrent())).thenAnswer { invocation ->
            val current = invocation.getArgument<() -> Boolean>(1)
            outputPresent.set(false)
            disconnectCallback()
            app.nativeGameModeControllers[address] = replacement
            assertFalse(current())
            false
        }
        assertEquals("failed", release())
        verifyNoInteractions(replacement)
        assertNoTakeoverMutations()
    }

    @Test fun aNewLocalIntentStillRevokesTheReleaseAfterOwnedCleanup() = runTest {
        `when`(bridge.releaseNativePlayback(eq(address.value) ?: address.value, anyCurrent())).thenAnswer { invocation ->
            val current = invocation.getArgument<() -> Boolean>(1)
            outputPresent.set(false)
            disconnectCallback()
            intentCurrent.set(false)
            assertFalse(current())
            false
        }
        assertEquals("failed", release())
        assertNoTakeoverMutations()
    }

    @Test fun gameRemovalBeforeOwnedReleaseStillBlocksAllProfileMutations() = runTest {
        `when`(game.suspendForHandover()).thenAnswer { disconnectCallback(); true }
        assertEquals("busy", release())
        verify(bridge, never()).releaseNativePlayback(anyString() ?: "", anyCurrent())
        assertNoTakeoverMutations()
    }

    @Test fun deviceRemovalRemainsFailClosedAfterOwnedCleanup() = runTest {
        `when`(bridge.releaseNativePlayback(eq(address.value) ?: address.value, anyCurrent())).thenAnswer { invocation ->
            val current = invocation.getArgument<() -> Boolean>(1)
            outputPresent.set(false)
            disconnectCallback()
            devices().value = emptyMap()
            assertFalse(current())
            false
        }
        assertEquals("failed", release())
        assertNoTakeoverMutations()
    }

    @Test fun bondLossRemainsFailClosedAfterOwnedCleanup() = runTest {
        `when`(bridge.releaseNativePlayback(eq(address.value) ?: address.value, anyCurrent())).thenAnswer { invocation ->
            val current = invocation.getArgument<() -> Boolean>(1)
            outputPresent.set(false)
            disconnectCallback()
            `when`(bluetooth.bondState).thenReturn(BluetoothDevice.BOND_NONE)
            assertFalse(current())
            false
        }
        assertEquals("failed", release())
        assertNoTakeoverMutations()
    }

    @Test fun aHelperReceiptWithoutActualOutputRemovalIsStillRejected() = runTest {
        `when`(bridge.releaseNativePlayback(eq(address.value) ?: address.value, anyCurrent())).thenAnswer {
            disconnectCallback()
            true
        }
        assertEquals("failed", release())
        assertTrue(outputPresent.get())
        assertNoTakeoverMutations()
    }

    private suspend fun assertNoTakeoverMutations() {
        verify(bridge, never()).connectNativePlayback(anyString() ?: "", anyCurrent())
        verify(bridge, never()).nativeCall(anyString() ?: "", anyBoolean(), anyCurrent())
        verify(device, never()).connect()
        verify(device, never()).connect(anyCurrent())
        verify(device, never()).disconnect()
    }

    private fun disconnectCallback() {
        LibrePodsService::class.java.declaredMethods.single {
            it.name.substringBefore('-') == "onDeviceDisconnected" && it.parameterTypes.contentEquals(arrayOf(String::class.java))
        }.apply { isAccessible = true }.invoke(service, address.value)
    }

    private suspend fun release(): String = handoffHost().companionOperation("release", address.value) { intentCurrent.get() }

    @Suppress("UNCHECKED_CAST")
    private fun devices() = field("_devices").get(service) as MutableStateFlow<Map<MacAddress, Device<*, *, *>>>
    private fun anyCurrent(): () -> Boolean = any<() -> Boolean>() ?: { true }
    private fun companionHolds() = field("companionHolds").get(service) as CompanionHolds
    private fun holdsField(name: String) = CompanionHolds::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun handoffHost() = field("handoffHost").get(service) as CompanionHandoffHost
    private fun field(name: String) = LibrePodsService::class.java.getDeclaredField(name).apply { isAccessible = true }
}
