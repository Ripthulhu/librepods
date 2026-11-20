package me.kavishdevar.librepods.services

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.app.Application
import android.content.Context
import me.kavishdevar.librepods.shizuku.ShizukuAudioClient
import me.kavishdevar.librepods.shizuku.BridgeState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContextImpl
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.devices.*
import me.kavishdevar.librepods.database.app.AppSettingsEntity
import me.kavishdevar.librepods.repository.AppDataRepository
import me.kavishdevar.librepods.utils.MediaController
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class EarDetectionOwnershipTest {
    private val service = Robolectric.buildService(LibrePodsService::class.java).get()
    private val device = mock(AppleDevice::class.java)
    private val audio = mock(AudioManager::class.java)
    private val bridge = mock(ShizukuAudioClient::class.java)
    private val bridgeState = MutableStateFlow(BridgeState(connected = true))
    private val aacp = mock(AACPManager::class.java)
    private val transport = mock(BluetoothSocket::class.java)
    private val state = MutableStateFlow(AppleState())
    private val address = MacAddress("00:11:22:33:44:55")
    private val mediaAudio = MediaController::class.java.getDeclaredField("audioManager").apply { isAccessible = true }
    private val previousAudio = mediaAudio.get(null)
    private val previousPaused = MediaController.iPausedTheMedia
    private val previousPlayed = MediaController.userPlayedTheMedia
    private val full = setOf(DeviceComponentState(DeviceComponent.LEFT, ComponentStatus.IN_EAR),
        DeviceComponentState(DeviceComponent.RIGHT, ComponentStatus.IN_EAR))

    init {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mediaAudio.set(null, audio)
        MediaController.iPausedTheMedia = false
        MediaController.userPlayedTheMedia = false
        `when`(device.macAddress).thenReturn(address)
        val bluetooth = mock(BluetoothDevice::class.java)
        `when`(bluetooth.address).thenReturn(address.value)
        `when`(bluetooth.bondState).thenReturn(BluetoothDevice.BOND_BONDED)
        `when`(device.bluetoothDevice).thenReturn(bluetooth)
        `when`(device.state).thenReturn(state)
        `when`(device.aacp).thenReturn(aacp)
        `when`(aacp.captureAudioSocket()).thenReturn(transport)
        `when`(aacp.ownsAudioSocket(transport)).thenReturn(true)
        `when`(device.settings).thenReturn(MutableStateFlow(AppleSettings(showIslandPopup = false)))
        `when`(device.metadata).thenReturn(MutableStateFlow(AppleMetadata()))
        val repository = mock(AppDataRepository::class.java)
        `when`(repository.settings).thenReturn(MutableStateFlow(AppSettingsEntity()))
        field("appDataRepository\$delegate").set(service, lazyOf(repository))
        field("companionBridge\$delegate").set(service, lazyOf(bridge))
        `when`(bridge.state).thenReturn(bridgeState)
        Shadow.extract<ShadowContextImpl>(service.baseContext).setSystemService(Context.AUDIO_SERVICE, audio)
        @Suppress("UNCHECKED_CAST")
        val devices = field("_devices").get(service) as MutableStateFlow<Map<MacAddress, Device<*, *, *>>>
        devices.value = mapOf(address to device)
        runBlocking {
            `when`(bridge.pauseOwnedMedia(eq(address.value) ?: address.value, any<() -> Boolean>() ?: { false })).thenAnswer { invocation ->
                @Suppress("UNCHECKED_CAST")
                (invocation.arguments[1] as () -> Boolean)()
            }
            `when`(bridge.finishOwnedMedia(true)).thenReturn(true)
        }
    }

    @After fun close() {
        (field("serviceScope").get(service) as CoroutineScope).cancel()
        Dispatchers.resetMain()
        mediaAudio.set(null, previousAudio)
        MediaController.iPausedTheMedia = previousPaused
        MediaController.userPlayedTheMedia = previousPlayed
    }

    @Test fun controlOnlyRemoteWearReportsCannotConnectOrControlPhonePlayback() {
        route("AA:BB:CC:DD:EE:FF")
        `when`(audio.isMusicActive).thenReturn(true)
        change(emptySet(), full)
        change(full, emptySet())
        verify(audio, never()).dispatchMediaKeyEvent(any(KeyEvent::class.java))
        noProfileChanges()
        assertFalse(MediaController.iPausedTheMedia)
    }

    @Test fun localEarRemovalAndReinsertionUseOnlyTheAuthorizedExactPlayerLease() = runBlocking<Unit> {
        route(address.value)
        change(full, emptySet())
        verify(bridge).pauseOwnedMedia(eq(address.value) ?: address.value, any<() -> Boolean>() ?: { false })
        change(emptySet(), full)
        verify(bridge).finishOwnedMedia(true)
        verify(audio, never()).dispatchMediaKeyEvent(any(KeyEvent::class.java))
        noProfileChanges()
    }

    @Test fun disabledEarDetectionCannotPauseEvenWhenTheHostPolicyIsEnabled() = runBlocking<Unit> {
        route(address.value)
        state.value = state.value.copy(controlStates = mapOf(
            ControlCommandIdentifier.EAR_DETECTION_CONFIG to byteArrayOf(1)))
        change(full, emptySet())
        verify(bridge, never()).pauseOwnedMedia(anyString(), any<() -> Boolean>() ?: { false })
        verify(bridge, never()).finishOwnedMedia(true)
        noProfileChanges()
    }

    @Test fun disablingEarDetectionRetiresARemovalWaitingForTheBridge() = runBlocking<Unit> {
        route(address.value)
        bridgeState.value = BridgeState(connected = false)
        change(full, emptySet())
        state.value = state.value.copy(requestedControls = mapOf(
            ControlCommandIdentifier.EAR_DETECTION_CONFIG to byteArrayOf(1)))
        bridgeState.value = BridgeState(connected = true)
        verify(bridge, never()).pauseOwnedMedia(anyString(), any<() -> Boolean>() ?: { false })
        noProfileChanges()
    }

    @Test fun aRemoteRouteAfterLocalPauseDoesNotResumeOrReclaimWhenBudsAreWornAgain() {
        route(address.value)
        `when`(audio.isMusicActive).thenReturn(true)
        change(full, emptySet())
        clearInvocations(audio)
        route("AA:BB:CC:DD:EE:FF")
        change(emptySet(), full)
        verify(audio, never()).dispatchMediaKeyEvent(any(KeyEvent::class.java))
        noProfileChanges()
    }

    @Test fun eitherBudCanStayInUseWhileTheOtherIsInItsCase() = runBlocking<Unit> {
        route(address.value)
        for (worn in listOf(DeviceComponent.LEFT, DeviceComponent.RIGHT)) {
            val solo = wear(worn, ComponentStatus.IN_CASE)
            change(full, solo)
            change(solo, full)
        }
        verify(bridge, never()).pauseOwnedMedia(anyString(), any<() -> Boolean>() ?: { false })
        verify(bridge, never()).finishOwnedMedia(true)
        noProfileChanges()
    }

    @Test fun returningTheRemovedBudToItsCaseResumesOnlyTheOwnedPause() = runBlocking<Unit> {
        route(address.value)
        for (worn in listOf(DeviceComponent.LEFT, DeviceComponent.RIGHT)) {
            val removed = wear(worn, ComponentStatus.OUT_OF_EAR)
            change(full, removed)
            change(removed, wear(worn, ComponentStatus.IN_CASE))
        }
        verify(bridge, times(2)).pauseOwnedMedia(eq(address.value) ?: address.value, any<() -> Boolean>() ?: { false })
        verify(bridge, times(2)).finishOwnedMedia(true)
        noProfileChanges()
    }

    @Test fun bothBudsInTheCasePauseAndEitherBudCanResumeTheOwnedPlayer() = runBlocking<Unit> {
        route(address.value)
        val cased = full.map { it.copy(status = ComponentStatus.IN_CASE) }.toSet()
        for (worn in listOf(DeviceComponent.LEFT, DeviceComponent.RIGHT)) {
            val solo = wear(worn, ComponentStatus.IN_CASE)
            change(solo, cased)
            change(cased, solo)
        }
        verify(bridge, times(2)).pauseOwnedMedia(eq(address.value) ?: address.value, any<() -> Boolean>() ?: { false })
        verify(bridge, times(2)).finishOwnedMedia(true)
    }

    @Test fun outOfEarDisconnectedAndMissingBudsAreNotAssumedToBeInTheCase() = runBlocking<Unit> {
        route(address.value)
        for (worn in listOf(DeviceComponent.LEFT, DeviceComponent.RIGHT)) {
            for (status in listOf(ComponentStatus.OUT_OF_EAR, ComponentStatus.DISCONNECTED)) {
                change(full, wear(worn, status))
            }
            change(full, setOf(DeviceComponentState(worn, ComponentStatus.IN_EAR)))
        }
        verify(bridge, times(6)).pauseOwnedMedia(eq(address.value) ?: address.value, any<() -> Boolean>() ?: { false })
        verify(bridge, never()).finishOwnedMedia(true)
    }

    @Test fun aSoloBudCannotResumeAnUnownedOrFailedPause() = runBlocking<Unit> {
        route(address.value)
        val removed = wear(DeviceComponent.LEFT, ComponentStatus.OUT_OF_EAR)
        val solo = wear(DeviceComponent.LEFT, ComponentStatus.IN_CASE)
        change(removed, solo)
        `when`(bridge.pauseOwnedMedia(eq(address.value) ?: address.value, any<() -> Boolean>() ?: { false })).thenReturn(false)
        change(full, removed)
        change(removed, solo)
        verify(bridge, never()).finishOwnedMedia(true)
    }

    @Test fun aQueuedRemovalCannotPauseOnceTheOtherBudIsConfirmedInItsCase() = runBlocking<Unit> {
        route(address.value)
        bridgeState.value = BridgeState(connected = false)
        val removed = wear(DeviceComponent.LEFT, ComponentStatus.OUT_OF_EAR)
        change(full, removed)
        state.value = state.value.copy(componentState = wear(DeviceComponent.LEFT, ComponentStatus.IN_CASE))
        bridgeState.value = BridgeState(connected = true)
        verify(bridge, never()).pauseOwnedMedia(anyString(), any<() -> Boolean>() ?: { false })
        verify(bridge, never()).finishOwnedMedia(true)
    }

    @Test fun aSoloReadyReportWaitsForItsInFlightOwnedPauseBeforeResuming() = runBlocking<Unit> {
        route(address.value)
        var result: Continuation<Boolean>? = null
        doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            assertTrue((invocation.arguments[1] as () -> Boolean)())
            @Suppress("UNCHECKED_CAST")
            val continuation = invocation.rawArguments.last() as Continuation<Boolean>
            result = continuation
            COROUTINE_SUSPENDED
        }.`when`(bridge).pauseOwnedMedia(eq(address.value) ?: address.value, any<() -> Boolean>() ?: { false })
        for (succeeded in listOf(true, false)) {
            clearInvocations(bridge)
            result = null
            val removed = wear(DeviceComponent.LEFT, ComponentStatus.OUT_OF_EAR)
            change(full, removed)
            assertNotNull(result)
            change(removed, wear(DeviceComponent.LEFT, ComponentStatus.IN_CASE))
            verify(bridge, never()).finishOwnedMedia(true)
            checkNotNull(result).resume(succeeded)
            verify(bridge, times(if (succeeded) 1 else 0)).finishOwnedMedia(true)
        }
    }

    @Test fun aReplacementConnectionCannotResumeThePreviousConnectionsPause() = runBlocking<Unit> {
        route(address.value)
        change(full, emptySet())
        val replacement = mock(BluetoothSocket::class.java)
        `when`(aacp.ownsAudioSocket(transport)).thenReturn(false)
        `when`(aacp.ownsAudioSocket(replacement)).thenReturn(true)
        `when`(aacp.captureAudioSocket()).thenReturn(replacement)
        val solo = wear(DeviceComponent.LEFT, ComponentStatus.IN_CASE)
        change(emptySet(), solo, transport)
        change(emptySet(), solo, replacement)
        verify(bridge, never()).finishOwnedMedia(true)
    }

    @Test fun aSupersededWearReportCannotResumePlayback() = runBlocking<Unit> {
        route(address.value)
        change(full, emptySet())
        val solo = wear(DeviceComponent.LEFT, ComponentStatus.IN_CASE)
        state.value = state.value.copy(componentState = wear(DeviceComponent.LEFT, ComponentStatus.OUT_OF_EAR))
        dispatchChange(emptySet(), solo, transport)
        verify(bridge, never()).finishOwnedMedia(true)
    }

    private fun wear(worn: DeviceComponent, other: ComponentStatus) = full.map {
        if (it.component == worn) it else it.copy(status = other)
    }.toSet()

    private fun route(value: String) {
        val output = mock(AudioDeviceInfo::class.java)
        `when`(output.address).thenReturn(value)
        `when`(output.type).thenReturn(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        `when`(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)).thenReturn(arrayOf(output))
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        `when`(audio.getAudioDevicesForAttributes(any(AudioAttributes::class.java) ?: attributes)).thenReturn(listOf(output))
    }

    private fun change(previous: Set<DeviceComponentState>, current: Set<DeviceComponentState>, source: BluetoothSocket = transport) {
        state.value = state.value.copy(componentState = current)
        dispatchChange(previous, current, source)
    }

    private fun dispatchChange(previous: Set<DeviceComponentState>, current: Set<DeviceComponentState>, source: BluetoothSocket) {
        (field("wearMedia").get(service) as WearMediaController).onComponentStateChange(device, previous, current, source)
    }

    private fun noProfileChanges() {
        verify(device, never()).enableAudio()
        verify(device, never()).disableAudio()
        verify(device, never()).connectA2dp()
        verify(device, never()).disconnectAudio()
    }

    private fun field(name: String) = LibrePodsService::class.java.getDeclaredField(name).apply { isAccessible = true }
}
