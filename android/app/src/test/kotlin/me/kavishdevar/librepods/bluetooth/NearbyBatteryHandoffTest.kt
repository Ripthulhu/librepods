package me.kavishdevar.librepods.bluetooth

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.AACPManager
import me.kavishdevar.librepods.core.bluetooth.aacp.types.MagicKeyType
import me.kavishdevar.librepods.core.bluetooth.ble.RecentProximityReports
import me.kavishdevar.librepods.core.devices.*
import me.kavishdevar.librepods.database.app.AppSettingsEntity
import me.kavishdevar.librepods.presentation.overlays.IslandWindow
import me.kavishdevar.librepods.services.LibrePodsService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class NearbyBatteryHandoffTest {
    private val irk = byteArrayOf(0x79, 4, 0x65, 0x1e, 0xe2.toByte(), 0xcc.toByte(), 0xd9.toByte(),
        0x26, 0xf2.toByte(), 0x6e, 0x20, 0xee.toByte(), 0x3e, 0xcc.toByte(), 0xde.toByte(), 0x79)
    private val key = ByteArray(16) { (it + 1).toByte() }
    private val keys = mapOf(MagicKeyType.IRK to irk, MagicKeyType.ENC_KEY to key)

    @Test fun connectedInitialFillPreservesLiveReadingsAndRejectsUnavailableFutureOrStaleSeeds() {
        val device = createDevice()
        val socket = Shadow.newInstanceOf(BluetoothSocket::class.java).apply { connect() }
        bind(device, socket)
        val now = System.currentTimeMillis()
        val liveLeft = Battery(BatteryComponent.LEFT, 95, BatteryStatus.NOT_CHARGING, now)
        device.loadInitialState(AppleState(battery = setOf(liveLeft,
            Battery(BatteryComponent.RIGHT, 0, BatteryStatus.UNKNOWN, now),
            Battery(BatteryComponent.CASE, 10, BatteryStatus.DISCONNECTED, now - 100_000, present = false))),
            AppleSettings(), AppleMetadata())
        try {
            device.receiveNearbyBattery(setOf(Battery(BatteryComponent.LEFT, 80, BatteryStatus.CHARGING, now - 100),
                Battery(BatteryComponent.RIGHT, 72, BatteryStatus.CHARGING, now - 100),
                Battery(BatteryComponent.CASE, 65, BatteryStatus.CHARGING, now - 100)))
            val filled = device.state.value.battery.associateBy { it.component }
            assertEquals(liveLeft, filled[BatteryComponent.LEFT])
            assertEquals(72, filled[BatteryComponent.RIGHT]?.level)
            assertEquals(65, filled[BatteryComponent.CASE]?.level)
            assertEquals(now - 100, filled[BatteryComponent.CASE]?.observedAt)
            val before = device.state.value.battery
            device.receiveNearbyBattery(setOf(Battery(BatteryComponent.RIGHT, 0, BatteryStatus.DISCONNECTED),
                Battery(BatteryComponent.CASE, 1, BatteryStatus.CHARGING, now + 60_000),
                Battery(BatteryComponent.HEADSET, 1, BatteryStatus.CHARGING, now - 100_000)))
            assertEquals(before, device.state.value.battery)
        } finally { device.aacp.disconnect(); socket.close() }
    }

    @Test fun realServiceReplayRequiresCurrentOwnerBondKeysTransportAndLifetimeAndUsesOnlyLatestFrame() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        val context = RuntimeEnvironment.getApplication()
        val service = Robolectric.buildService(LibrePodsService::class.java).get()
        val window = IslandWindow(context, AppSettingsEntity())
        field("islandWindow\$delegate").set(service, lazyOf(window))
        val device = createDevice()
        val socket = Shadow.newInstanceOf(BluetoothSocket::class.java).apply { connect() }
        bind(device, socket)
        val now = System.currentTimeMillis()
        val liveRight = Battery(BatteryComponent.RIGHT, 95, BatteryStatus.NOT_CHARGING, now)
        val initial = setOf(liveRight, Battery(BatteryComponent.CASE, 10, BatteryStatus.DISCONNECTED, now - 100_000, present = false))
        fun load(currentKeys: Map<MagicKeyType, ByteArray> = keys) = device.loadInitialState(
            AppleState(battery = initial, magicKeys = currentKeys), AppleSettings(), AppleMetadata())
        load()
        @Suppress("UNCHECKED_CAST")
        val devices = field("_devices").get(service) as MutableStateFlow<Map<MacAddress, Device<*, *, *>>>
        devices.value = mapOf(device.macAddress to device)
        val recent = field("recentProximityReports").get(service) as RecentProximityReports
        val nanos = SystemClock.elapsedRealtimeNanos()
        recent.observe("5A:16:2B:91:D1:CD", advertisement(80, 70, 60), nanos - 1_000_000_000, now - 1000, nanos)
        recent.observe("5A:16:2B:91:D1:CD", advertisement(127, 72, 65), nanos - 500_000_000, now - 500, nanos)
        val replay = LibrePodsService::class.java.getDeclaredMethod("replayRecentBattery", AppleDevice::class.java,
            BluetoothSocket::class.java, ByteArray::class.java, ByteArray::class.java).apply { isAccessible = true }
        fun apply(expected: BluetoothSocket? = socket) { replay.invoke(service, device, expected, irk, key) }
        fun unchanged() = assertEquals(initial, device.state.value.battery)
        try {
            field("destroying").setBoolean(service, true); apply(); unchanged(); field("destroying").setBoolean(service, false)
            field("bleCaptureBlocked").setBoolean(service, true); apply(); unchanged(); field("bleCaptureBlocked").setBoolean(service, false)
            devices.value = emptyMap(); apply(); unchanged()
            devices.value = mapOf(device.macAddress to createDevice()); apply(); unchanged()
            devices.value = mapOf(device.macAddress to device)
            shadowOf(device.bluetoothDevice).setBondState(BluetoothDevice.BOND_NONE); apply(); unchanged()
            shadowOf(device.bluetoothDevice).setBondState(BluetoothDevice.BOND_BONDED)
            apply(null); unchanged()
            load(mapOf(MagicKeyType.IRK to irk)); apply(); unchanged()
            load(mapOf(MagicKeyType.IRK to irk, MagicKeyType.ENC_KEY to ByteArray(16) { 9 })); apply(); unchanged()
            load(); apply()
            val filled = device.state.value.battery.associateBy { it.component }
            assertEquals(liveRight, filled[BatteryComponent.RIGHT])
            assertEquals(65, filled[BatteryComponent.CASE]?.level)
            assertEquals(now - 500, filled[BatteryComponent.CASE]?.observedAt)
            assertFalse("An unavailable latest slot must not resurrect an older percentage", BatteryComponent.LEFT in filled)

            // Cached keys without a control socket use the same trust rule as ordinary owned BLE.
            device.aacp.disconnect(socket)
            load()
            @Suppress("UNCHECKED_CAST")
            (field("pairedObservations").get(service) as MutableMap<MacAddress, Long>).clear()
            apply(null)
            assertEquals(65, device.state.value.battery.first { it.component == BatteryComponent.CASE }.level)
            val beforeExpiry = device.state.value.battery
            ShadowSystemClock.advanceBy(Duration.ofSeconds(16))
            apply(null)
            assertEquals("Expired raw reports cannot refresh observation ages", beforeExpiry, device.state.value.battery)
        } finally {
            device.aacp.disconnect(); socket.close(); recent.clear(); window.close()
            (field("serviceScope").get(service) as CoroutineScope).cancel()
        }
    }

    private fun createDevice(): AppleDevice {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        val bluetooth = adapter.getRemoteDevice("00:11:22:33:44:55")
        shadowOf(bluetooth).setBondState(BluetoothDevice.BOND_BONDED)
        return AppleDevice(context, adapter, bluetooth, ConnectionState.CONNECTED)
    }
    private fun bind(device: AppleDevice, socket: BluetoothSocket) {
        AACPManager::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(device.aacp, socket)
    }
    private fun field(name: String) = LibrePodsService::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun advertisement(left: Int, right: Int, case: Int): ByteArray {
        val plain = ByteArray(16).apply { this[1] = left.toByte(); this[2] = right.toByte(); this[3] = case.toByte() }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        }
        return ByteArray(27).apply {
            this[0] = 7; this[1] = 25; this[2] = 1; this[3] = 0x27; this[4] = 0x20; this[5] = 0x64
            cipher.doFinal(plain).copyInto(this, 11)
        }
    }
}
