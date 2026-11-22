package me.kavishdevar.librepods.services

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import me.kavishdevar.librepods.database.app.AppSettingsEntity
import me.kavishdevar.librepods.repository.AppDataRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContextImpl
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class NearbyScanLifecycleTest {
    private val service = Robolectric.buildService(LibrePodsService::class.java).get()
    private val scanner = mock(BluetoothLeScanner::class.java)
    private val settings = MutableStateFlow(AppSettingsEntity())
    private val callbacks = mutableListOf<ScanCallback>()
    private val looper = shadowOf(Looper.getMainLooper())
    private val power = shadowOf(service.getSystemService(PowerManager::class.java))

    init {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        val manager = mock(BluetoothManager::class.java)
        val adapter = mock(BluetoothAdapter::class.java)
        `when`(manager.adapter).thenReturn(adapter)
        `when`(adapter.isEnabled).thenReturn(true)
        `when`(adapter.bluetoothLeScanner).thenReturn(scanner)
        Shadow.extract<ShadowContextImpl>(service.baseContext).setSystemService(Context.BLUETOOTH_SERVICE, manager)
        val repository = mock(AppDataRepository::class.java)
        `when`(repository.settings).thenReturn(settings)
        field("appDataRepository\$delegate").set(service, lazyOf(repository))
        bleScan().ready = true
        power.setIsInteractive(true)
        doAnswer { callbacks += it.getArgument<ScanCallback>(2); null }.`when`(scanner)
            .startScan(anyList(), any(ScanSettings::class.java), any(ScanCallback::class.java))
    }

    @After fun close() {
        bleScan().stopActive()
        (field("serviceScope").get(service) as CoroutineScope).cancel()
    }

    @Test fun rapidLockUnlockKeepsTheHealthyScanAndOnlySettledScreenOffChangesMode() {
        start()
        repeat(10) {
            screen(false)
            looper.idleFor(Duration.ofMillis(20))
            screen(true)
        }
        assertEquals(1, callbacks.size)
        verify(scanner, never()).stopScan(any(ScanCallback::class.java))
        screen(false)
        looper.idleFor(Duration.ofSeconds(30))
        assertEquals(2, callbacks.size)
        screen(true)
        assertEquals(3, callbacks.size)
    }

    @Test fun aFullRegistrationWindowKeepsTheHealthyScanUntilItsAllowanceReturns() {
        power.setIsInteractive(false)
        repeat(6) { index ->
            settings.value = settings.value.copy(bleScanMode = if (index % 2 == 0)
                ScanSettings.SCAN_MODE_BALANCED else ScanSettings.SCAN_MODE_LOW_LATENCY)
            start(updateMode = true)
        }
        assertEquals(5, callbacks.size)
        verify(scanner, times(4)).stopScan(any(ScanCallback::class.java))
        looper.idleFor(Duration.ofSeconds(30).plusMillis(1))
        assertEquals(6, callbacks.size)
        verify(scanner, times(5)).stopScan(any(ScanCallback::class.java))
    }

    @Test fun successfulRecoveryCancelsTheFailedScansOldRetry() {
        start()
        callbacks.single().onScanFailed(ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED)
        looper.idle()
        start()
        assertEquals(2, callbacks.size)
        looper.idleFor(Duration.ofSeconds(31))
        assertEquals(2, callbacks.size)
        verify(scanner).stopScan(any(ScanCallback::class.java))
    }

    @Test fun stoppingTheScannerCancelsItsPendingFailureRetry() {
        start()
        callbacks.single().onScanFailed(ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY)
        looper.idle()
        // The app has no nearby visits in this unit test, so stop the active scanner and clear its request.
        bleScan().stop()
        looper.idleFor(Duration.ofSeconds(31))
        assertEquals(1, callbacks.size)
    }

    private fun screen(on: Boolean) {
        power.setIsInteractive(on)
        (field("bluetoothReceiver").get(service) as BroadcastReceiver).onReceive(service,
            Intent(if (on) Intent.ACTION_SCREEN_ON else Intent.ACTION_SCREEN_OFF))
    }

    private fun start(updateMode: Boolean = false) = bleScan().start(updateMode)
    private fun bleScan() = field("bleScan").get(service) as BleScanController
    private fun field(name: String) = LibrePodsService::class.java.getDeclaredField(name).apply { isAccessible = true }
}
