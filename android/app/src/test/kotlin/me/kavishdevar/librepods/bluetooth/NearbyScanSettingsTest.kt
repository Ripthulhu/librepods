package me.kavishdevar.librepods.bluetooth

import android.app.Application
import android.bluetooth.le.ScanSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class NearbyScanSettingsTest {
    @Test fun foregroundLidMonitoringDeliversPassiveAdvertisementsImmediately() {
        val settings = nearbyScanSettings(interactive = true, ScanSettings.SCAN_MODE_BALANCED)
        assertEquals(ScanSettings.SCAN_TYPE_PASSIVE, settings.scanType)
        assertEquals(ScanSettings.SCAN_MODE_LOW_LATENCY, settings.scanMode)
        assertEquals(0L, settings.reportDelayMillis)
        assertEquals(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, settings.callbackType)
    }

    @Test fun screenOffRetainsTheSelectedModeWithoutBatchingOrWaitingForResponses() {
        val settings = nearbyScanSettings(interactive = false, ScanSettings.SCAN_MODE_LOW_POWER)
        assertEquals(ScanSettings.SCAN_TYPE_PASSIVE, settings.scanType)
        assertEquals(ScanSettings.SCAN_MODE_LOW_POWER, settings.scanMode)
        assertEquals(0L, settings.reportDelayMillis)
    }

    @Test
    @Config(sdk = [35])
    fun olderAndroidKeepsImmediateReportsWithoutCallingTheNewScanTypeApi() {
        val settings = nearbyScanSettings(interactive = true, ScanSettings.SCAN_MODE_BALANCED)
        assertEquals(ScanSettings.SCAN_MODE_LOW_LATENCY, settings.scanMode)
        assertEquals(0L, settings.reportDelayMillis)
    }
}
