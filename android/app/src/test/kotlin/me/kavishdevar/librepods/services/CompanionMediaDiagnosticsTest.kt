package me.kavishdevar.librepods.services

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import java.io.FileDescriptor
import java.io.PrintWriter
import java.io.StringWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import me.kavishdevar.librepods.companion.CompanionMediaSample
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.Device
import me.kavishdevar.librepods.shizuku.CompanionMediaInspection
import me.kavishdevar.librepods.shizuku.MediaInspectionReason
import me.kavishdevar.librepods.shizuku.MediaInspectionStage
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class CompanionMediaDiagnosticsTest {
    private lateinit var service: LibrePodsService
    private val bridge = mock(ShizukuAudioClient::class.java)
    private val device = mock(AppleDevice::class.java)
    private val bluetooth = mock(BluetoothDevice::class.java)
    private val adapter = mock(BluetoothAdapter::class.java)
    private val address = MacAddress("AA:BB:CC:DD:EE:FF")

    @Before fun setUp() {
        service = Robolectric.buildService(LibrePodsService::class.java).get()
        holdsField("ready").set(companionHolds(), true)
        field("companionBridge\$delegate").set(service, lazyOf(bridge))
        `when`(bridge.hasRootPrivileges).thenReturn(true)
        `when`(bridge.state).thenReturn(MutableStateFlow(me.kavishdevar.librepods.shizuku.BridgeState(connected = true)))
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val manager = mock(BluetoothManager::class.java)
        `when`(manager.adapter).thenReturn(adapter)
        `when`(adapter.isEnabled).thenReturn(true)
        val audio = mock(AudioManager::class.java)
        val output = mock(AudioDeviceInfo::class.java)
        `when`(output.type).thenReturn(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        `when`(output.address).thenReturn(address.value)
        `when`(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)).thenReturn(arrayOf(output))
        val context = Shadow.extract<ShadowContextImpl>(service.baseContext)
        context.setSystemService(Context.BLUETOOTH_SERVICE, manager)
        context.setSystemService(Context.AUDIO_SERVICE, audio)
        `when`(device.bluetoothDevice).thenReturn(bluetooth)
        `when`(bluetooth.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        @Suppress("UNCHECKED_CAST")
        val devices = field("_devices").get(service) as MutableStateFlow<Map<MacAddress, Device<*, *, *>>>
        devices.value = mapOf(address to device)
    }

    @Test fun admissionFailureShowsActualOutputAndTheExactGateWithoutCallingTheHelper() = runBlocking<Unit> {
        holdsField("ready").set(companionHolds(), false)
        assertFalse(inspect().known)
        val report = dump()
        assertTrue(report.contains("stage=ADMISSION reason=HOLD_UNAVAILABLE"))
        assertTrue(report.contains("hold_ready=false"))
        assertTrue(report.contains("actual_output_connected=true"))
        assertTrue(report.contains("bridge_connected=true root=true"))
        verify(bridge, never()).inspectCompanionMedia(anyString())
    }

    @Test fun helperFailureRemainsUnknownAndTheDumpContainsOnlyFixedDiagnosticValues() = runBlocking {
        `when`(bridge.inspectCompanionMedia(address.value)).thenReturn(
            CompanionMediaInspection.failure(MediaInspectionStage.SESSIONS, MediaInspectionReason.SECURITY).apply {
                putString("player", "private.player/title/key")
            })
        val sample = inspect()
        assertFalse(sample.known); assertFalse(sample.available)
        val report = dump()
        assertTrue(report.contains("stage=SESSIONS reason=SECURITY"))
        assertTrue(report.contains("known=false playing=false available=false"))
        assertFalse(report.contains("private.player")); assertFalse(report.contains(address.value))
        assertTrue(report.contains("age_ms=")); assertTrue(report.contains("pending=false"))
    }

    @Test fun staleHelperReadIsDistinguishedFromAnAbsentDeviceOrLostRootBridge() = runBlocking {
        `when`(bridge.inspectCompanionMedia(address.value)).thenReturn(
            CompanionMediaInspection.failure(MediaInspectionStage.CLIENT, MediaInspectionReason.BRIDGE_REPLACED))
        inspect()
        assertTrue(dump().contains("stage=CLIENT reason=BRIDGE_REPLACED"))
        `when`(bridge.hasRootPrivileges).thenReturn(false)
        // The test identity may permit rootless access, so exercise the unambiguous missing-device gate.
        @Suppress("UNCHECKED_CAST")
        val devices = field("_devices").get(service) as MutableStateFlow<Map<MacAddress, Device<*, *, *>>>
        devices.value = emptyMap()
        inspect()
        assertTrue(dump().contains("stage=ADMISSION reason=DEVICE_MISSING"))
        assertTrue(dump().contains("device_present=false"))
    }

    private suspend fun inspect(): CompanionMediaSample = handoffHost().companionMedia(address.value)

    private fun dump(): String = StringWriter().also {
        LibrePodsService::class.java.getDeclaredMethod("dump", FileDescriptor::class.java, PrintWriter::class.java,
            Array<String>::class.java).apply { isAccessible = true }
            .invoke(service, FileDescriptor(), PrintWriter(it), arrayOf("--companion-media"))
    }.toString()

    private fun companionHolds() = field("companionHolds").get(service) as CompanionHolds
    private fun holdsField(name: String) = CompanionHolds::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun handoffHost() = field("handoffHost").get(service) as CompanionHandoffHost
    private fun field(name: String) = LibrePodsService::class.java.getDeclaredField(name).apply { isAccessible = true }
}
