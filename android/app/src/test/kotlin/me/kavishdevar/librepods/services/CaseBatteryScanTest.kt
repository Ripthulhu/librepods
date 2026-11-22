package me.kavishdevar.librepods.services

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import me.kavishdevar.librepods.bluetooth.NearbyPairingController
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import me.kavishdevar.librepods.core.bluetooth.aacp.types.MagicKeyType
import me.kavishdevar.librepods.core.bluetooth.ble.CaseBatteryReport
import me.kavishdevar.librepods.core.devices.*
import me.kavishdevar.librepods.database.app.AppSettingsEntity
import me.kavishdevar.librepods.presentation.overlays.IslandWindow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class CaseBatteryScanTest {
    private val context = RuntimeEnvironment.getApplication()
    private val key = ByteArray(16) { it.toByte() }
    private val plain = byteArrayOf(0x29, 0x20, 0x0a, 0x3d, 0xde.toByte(), 0xdd.toByte(),
        0x51, 0x0b, 0, 0, 0, 0, 0xf7.toByte(), 0x82.toByte(), 0x16, 0)
    private val service = Robolectric.buildService(LibrePodsService::class.java).get()
    private val window = IslandWindow(context, AppSettingsEntity())
    private val pairing = mock(NearbyPairingController::class.java)
    private val sockets = mutableListOf<BluetoothSocket>()

    init {
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        field("islandWindow\$delegate").set(service, lazyOf(window))
        field("nearbyPairing").set(service, pairing)
        advance(2_000)
    }

    @After fun close() {
        installed.values.filterIsInstance<AppleDevice>().forEach { it.aacp.disconnect() }
        sockets.forEach { it.close() }
        window.close()
        (field("serviceScope").get(service) as CoroutineScope).cancel()
    }

    @Test fun actualScannerAssociatesSavedDisconnectedPairWithoutIRKOrPromotingBudIdentity() {
        val device = device()
        install(device)
        scan()
        val battery = device.state.value.battery.associateBy { it.component }
        assertEquals(61, battery[BatteryComponent.CASE]?.level)
        assertEquals(94, battery[BatteryComponent.LEFT]?.level)
        assertEquals(93, battery[BatteryComponent.RIGHT]?.level)
        assertEquals(ConnectionState.AVAILABLE, device.connectionState.value)
        assertEquals(0, device.connectionNumber.value)
        assertNull(device.aacp.captureAudioSocket())
        assertTrue(device.state.value.componentState.isEmpty())
        assertTrue("Cases cannot enter pairedObservations", (PairedBatteryTracker::class.java.getDeclaredField("pairedObservations")
            .apply { isAccessible = true }.get(field("batteryTracker").get(service)) as Map<*, *>).isEmpty())
        val deferral = field("nearbyDeferral").get(service)
        for (name in listOf("pairedLids", "nearbyCases", "nearbyMedia"))
            assertTrue("Cases cannot enter $name", (NearbyConnectDeferral::class.java.getDeclaredField(name)
                .apply { isAccessible = true }.get(deferral) as Map<*, *>).isEmpty())
        verifyNoInteractions(pairing)
    }

    @Test fun requiresOneCurrentBondedPro3PairWithItsOwnAdvertisingKey() {
        val first = device(model = AirPodsModel.UNKNOWN)
        install(first)
        scan(); assertTrue(first.state.value.battery.isEmpty())
        first.loadInitialState(AppleState(), AppleSettings(), AppleMetadata(model = AirPodsModel.AIRPODS_PRO_3))
        scan(); assertTrue(first.state.value.battery.isEmpty())
        first.loadInitialState(AppleState(magicKeys = mapOf(MagicKeyType.ENC_KEY to ByteArray(16))),
            AppleSettings(), AppleMetadata(model = AirPodsModel.AIRPODS_PRO_3))
        scan(); assertTrue(first.state.value.battery.isEmpty())
        reset(first)
        val second = device("00:11:22:33:44:66")
        install(first, second)
        scan(); assertTrue(first.state.value.battery.isEmpty()); assertTrue(second.state.value.battery.isEmpty())
        install(first)
        shadowOf(first.bluetoothDevice).setBondState(BluetoothDevice.BOND_NONE)
        scan(); assertTrue(first.state.value.battery.isEmpty())
        shadowOf(first.bluetoothDevice).setBondState(BluetoothDevice.BOND_BONDED)
        scan(encryption = ByteArray(16) { 42 }); assertTrue(first.state.value.battery.isEmpty())
        scan(); assertEquals(3, first.state.value.battery.size)
    }

    @Test fun repeatedPayloadAcrossRotatingAddressesNeverRenewsAgeEvenAfterExpiryOrScanRestart() {
        val device = device()
        install(device)
        scan()
        val initial = device.state.value.battery
        advance(1_000)
        scan(source = "D1:02:03:04:05:06")
        assertEquals(initial, device.state.value.battery)
        LibrePodsService::class.java.getDeclaredMethod("stopBleScanner").apply { isAccessible = true }.invoke(service)
        device.expireNearbyBattery(initial.first().observedAt!! + 90_000)
        val expired = device.state.value.battery
        assertTrue(expired.all { !it.present && it.status == BatteryStatus.DISCONNECTED })
        assertEquals(ConnectionState.DISCONNECTED, device.connectionState.value)
        advance(91_000)
        scan(source = "E1:02:03:04:05:06")
        assertEquals(expired, device.state.value.battery)
        assertEquals(ConnectionState.DISCONNECTED, device.connectionState.value)
        advance(1)
        scan(value = plain.copyOf().apply { this[12] = 0 })
        assertTrue("A changed opaque counter, including a reset, is a new observation", device.state.value.battery.all { it.present })
    }

    @Test fun rejectsExpiredFutureAndOutOfOrderScanDeliveriesAndBlockedOrRetiredService() {
        val device = device()
        install(device)
        val now = SystemClock.elapsedRealtimeNanos()
        scan(observed = now - 16_000_000_000L); scan(observed = now + 1)
        assertTrue(device.state.value.battery.isEmpty())
        field("destroying").setBoolean(service, true); scan(); assertTrue(device.state.value.battery.isEmpty())
        field("destroying").setBoolean(service, false)
        field("bleCaptureBlocked").setBoolean(service, true); scan(); assertTrue(device.state.value.battery.isEmpty())
        field("bleCaptureBlocked").setBoolean(service, false)
        scan()
        val initial = device.state.value.battery
        advance(1000)
        scan(value = plain.copyOf().apply { this[3] = 12 }, observed = now - 1)
        assertEquals(initial, device.state.value.battery)
        scan(value = plain.copyOf().apply { this[3] = 12 })
        assertEquals(12, device.state.value.battery.first { it.component == BatteryComponent.CASE }.level)
    }

    @Test fun absenceRetainsOnlyOriginalPercentageAndAgeAndChargingExpiresPerComponent() {
        val device = device()
        install(device)
        scan()
        val initial = device.state.value.battery.associateBy { it.component }
        advance(1_000)
        scan(value = plain.copyOf().apply { this[3] = -1; this[4] = 0x80.toByte(); this[5] = -1 })
        val current = device.state.value.battery.associateBy { it.component }
        for (component in listOf(BatteryComponent.CASE, BatteryComponent.RIGHT)) {
            assertEquals(initial.getValue(component).copy(status = BatteryStatus.DISCONNECTED, present = false), current[component])
        }
        val left = current.getValue(BatteryComponent.LEFT)
        assertEquals(0, left.level); assertEquals(BatteryStatus.CHARGING, left.status)
        device.expireNearbyBattery(left.observedAt!! + 90_000)
        assertEquals(left.copy(status = BatteryStatus.DISCONNECTED, present = false),
            device.state.value.battery.first { it.component == BatteryComponent.LEFT })
    }

    @Test fun identicalCaseValuesRefreshFreshnessWithoutAVisualChangeAndExpireFromTheLatestObservation() {
        val device = device()
        install(device)
        // Packet reception time makes the first reading older without relying on wall-clock sleeps.
        scan(observed = SystemClock.elapsedRealtimeNanos() - 1_000_000_000L)
        val first = device.state.value.battery
        advance(1_000)
        scan(value = plain.copyOf().apply { this[12] = (this[12] + 1).toByte() })
        val fresh = device.state.value.battery
        assertTrue(first.hasSameDisplayValues(fresh))
        assertNotEquals(first, fresh)
        device.expireNearbyBattery(first.first().observedAt!! + 90_000)
        assertEquals(fresh, device.state.value.battery)
        device.expireNearbyBattery(fresh.first().observedAt!! + 90_000)
        assertFalse(fresh.hasSameDisplayValues(device.state.value.battery))
        assertTrue(device.state.value.battery.all { !it.present })
    }

    @Test fun connectedAACPReadingsWinAndLaterControlReportsReplaceCaseFallback() {
        val device = device(connected = true)
        install(device)
        val socket = bind(device)
        device.aacp.processPacket(byteArrayOf(4, 0, 4, 0, 4, 0, 2, 4, 1, 99, 2, 1, 8, 1, 80, 1, 1), socket)
        val control = device.state.value.battery.associateBy { it.component }
        scan()
        val filled = device.state.value.battery.associateBy { it.component }
        assertEquals(control[BatteryComponent.LEFT], filled[BatteryComponent.LEFT])
        assertEquals(control[BatteryComponent.CASE], filled[BatteryComponent.CASE])
        assertEquals(93, filled[BatteryComponent.RIGHT]?.level)
        advance(1)
        scan(value = plain.copyOf().apply { this[5] = 91.toByte() })
        val updated = device.state.value.battery.associateBy { it.component }
        assertEquals(control[BatteryComponent.CASE], updated[BatteryComponent.CASE])
        assertEquals(91, updated[BatteryComponent.RIGHT]?.level)
        advance(1)
        scan(value = plain.copyOf().apply { this[5] = -1 })
        assertEquals(updated.getValue(BatteryComponent.RIGHT).copy(status = BatteryStatus.DISCONNECTED, present = false),
            device.state.value.battery.first { it.component == BatteryComponent.RIGHT })
        device.aacp.processPacket(byteArrayOf(4, 0, 4, 0, 4, 0, 1, 2, 1, 95, 2, 1), socket)
        val right = device.state.value.battery.first { it.component == BatteryComponent.RIGHT }
        device.expireNearbyBattery(right.observedAt!! + 90_000)
        assertEquals("Expiry of the older case fallback must not expire its AACP replacement", right,
            device.state.value.battery.first { it.component == BatteryComponent.RIGHT })
        assertEquals(ConnectionState.CONNECTED, device.connectionState.value)
    }

    @Test fun caseFallbackChargingExpiresEvenWhileBudControlSocketStaysConnected() {
        val device = device(connected = true)
        install(device); bind(device)
        scan()
        val initial = device.state.value.battery
        // A counter-only update can have the same wall-clock timestamp and equal battery values.
        device.receiveCaseBattery(initial.map { it.copy() }.toSet(), initial.first().observedAt!!)
        device.expireNearbyBattery(initial.first().observedAt!! + 90_000)
        assertTrue(device.state.value.battery.all { !it.present && it.status == BatteryStatus.DISCONNECTED })
        assertEquals(ConnectionState.CONNECTED, device.connectionState.value)
        assertNotNull(device.aacp.captureAudioSocket())
    }

    @Test fun delayedCaseSnapshotCannotReplaceNewerComponentOrMarkItAbsent() {
        val device = device()
        install(device)
        val now = System.currentTimeMillis()
        val recent = setOf(Battery(BatteryComponent.LEFT, 80, BatteryStatus.NOT_CHARGING, now),
            Battery(BatteryComponent.CASE, 70, BatteryStatus.NOT_CHARGING, now))
        device.receiveNearbyBattery(recent)
        // Test snapshot ordering directly; scanner mock/class loading can take longer than its apparent packet age.
        val report = requireNotNull(CaseBatteryReport.decode(
            advertisement(plain.copyOf().apply { this[3] = 1; this[4] = -1 }), key, AirPodsModel.AIRPODS_PRO_3, now - 1000))
        device.receiveCaseBattery(report.batteries, now - 1000)
        assertTrue(device.state.value.battery.containsAll(recent))
        assertEquals(93, device.state.value.battery.first { it.component == BatteryComponent.RIGHT }.level)
        assertTrue(device.state.value.battery.first { it.component == BatteryComponent.RIGHT }.observedAt!! < now)
    }

    @Test fun currentAdvertisingKeyDefinesDuplicateScopeAndInvalidFramesCannotConsumeIt() {
        val device = device()
        install(device)
        scan(raw = advertisement().apply { this[2] = 1 })
        scan(value = plain.copyOf().apply { this[4] = 0x7f })
        assertTrue(device.state.value.battery.isEmpty())
        scan()
        val replacement = ByteArray(16) { (it + 1).toByte() }
        reset(device, encryption = replacement)
        advance(1)
        scan(); assertTrue(device.state.value.battery.isEmpty())
        scan(encryption = replacement)
        assertEquals(3, device.state.value.battery.size)
        verifyNoInteractions(pairing)
    }

    private var installed: Map<MacAddress, Device<*, *, *>>
        @Suppress("UNCHECKED_CAST") get() = (field("_devices").get(service) as MutableStateFlow<Map<MacAddress, Device<*, *, *>>>).value
        @Suppress("UNCHECKED_CAST") set(value) { (field("_devices").get(service) as MutableStateFlow<Map<MacAddress, Device<*, *, *>>>).value = value }
    private fun install(vararg devices: AppleDevice) { installed = devices.associateBy { it.macAddress } }
    private fun device(address: String = "00:11:22:33:44:55", model: AirPodsModel = AirPodsModel.AIRPODS_PRO_3,
                       connected: Boolean = false): AppleDevice {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        val bluetooth = adapter.getRemoteDevice(address)
        shadowOf(bluetooth).setBondState(BluetoothDevice.BOND_BONDED)
        return AppleDevice(context, adapter, bluetooth, if (connected) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED)
            .also { reset(it, model = model) }
    }
    private fun reset(device: AppleDevice, model: AirPodsModel = AirPodsModel.AIRPODS_PRO_3, encryption: ByteArray = key) =
        device.loadInitialState(AppleState(magicKeys = mapOf(MagicKeyType.ENC_KEY to encryption)), AppleSettings(), AppleMetadata(model = model))
    private fun bind(device: AppleDevice): BluetoothSocket = Shadow.newInstanceOf(BluetoothSocket::class.java).apply {
        connect(); sockets += this
        AACPManager::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(device.aacp, this)
    }
    private fun advertisement(value: ByteArray = plain, encryption: ByteArray = key) = byteArrayOf(7, 17, 6) +
        Cipher.getInstance("AES/ECB/NoPadding").run { init(Cipher.ENCRYPT_MODE, SecretKeySpec(encryption, "AES")); doFinal(value) }
    private fun scan(value: ByteArray = plain, encryption: ByteArray = key, raw: ByteArray = advertisement(value, encryption),
                     source: String = "C1:02:03:04:05:06", observed: Long = SystemClock.elapsedRealtimeNanos()) {
        val advertised = mock(BluetoothDevice::class.java)
        `when`(advertised.address).thenReturn(source)
        val record = mock(ScanRecord::class.java)
        `when`(record.getManufacturerSpecificData(0x004c)).thenReturn(raw)
        val result = mock(ScanResult::class.java)
        `when`(result.device).thenReturn(advertised); `when`(result.scanRecord).thenReturn(record)
        `when`(result.rssi).thenReturn(-45); `when`(result.timestampNanos).thenReturn(observed)
        LibrePodsService::class.java.getDeclaredMethod("handleScanResult", ScanResult::class.java)
            .apply { isAccessible = true }.invoke(service, result)
    }
    private fun advance(millis: Long) = ShadowSystemClock.advanceBy(Duration.ofMillis(millis))
    private fun field(name: String) = LibrePodsService::class.java.getDeclaredField(name).apply { isAccessible = true }
}
