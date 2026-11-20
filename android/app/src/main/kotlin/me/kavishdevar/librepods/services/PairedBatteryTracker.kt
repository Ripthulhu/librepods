package me.kavishdevar.librepods.services

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.StateFlow
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.bluetooth.aacp.types.MagicKeyType
import me.kavishdevar.librepods.core.bluetooth.ble.CaseBatteryReport
import me.kavishdevar.librepods.core.bluetooth.ble.ProximityReport
import me.kavishdevar.librepods.core.bluetooth.ble.RecentProximityReports
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.Device
import me.kavishdevar.librepods.presentation.overlays.IslandWindow
import java.util.concurrent.ConcurrentHashMap

private const val NEARBY_SCAN_TAG = "NearbyScan"

/**
 * Battery from BLE advertisements for paired pairs: the owned proximity reports (bud and case levels), the
 * encrypted AirPods Pro 3 case report, and replay of a recent report once a pair's keys or control socket arrive.
 */
@SuppressLint("MissingPermission")
internal class PairedBatteryTracker(
    private val destroying: () -> Boolean,
    /** Microphone capture currently blocks BLE work. */
    private val captureBlocked: () -> Boolean,
    private val devices: StateFlow<Map<MacAddress, Device<*, *, *>>>,
    private val islandWindow: () -> IslandWindow,
) {
    private val recentProximityReports = RecentProximityReports()
    private data class CaseBatteryObservation(val key: ByteArray, val block: ByteArray, val observedNanos: Long)
    private val caseBatteryObservations = ConcurrentHashMap<MacAddress, CaseBatteryObservation>()
    private val pairedObservations = mutableMapOf<MacAddress, Long>()

    /** Keeps a raw proximity frame so a pair whose keys arrive later can still use it. */
    fun observeProximity(address: String, manufacturer: ByteArray, observed: Long, observedAt: Long, nowNanos: Long) {
        recentProximityReports.observe(address, manufacturer, observed, observedAt, nowNanos)
    }

    fun expireProximity(nowNanos: Long) {
        recentProximityReports.expire(nowNanos)
    }

    fun clearProximity() {
        recentProximityReports.clear()
    }

    /**
     * Decodes [device]'s own proximity frame when it is newer than the last one accepted for it, and records it.
     * [onLongGap] runs when 30 s or more passed since that last frame.
     */
    fun acceptOwnedReport(device: AppleDevice, address: String, manufacturer: ByteArray, observed: Long,
                          observedAt: Long, onLongGap: () -> Unit): ProximityReport? {
        val previousObservation = pairedObservations[device.macAddress]
        if (previousObservation != null && observed <= previousObservation) return null
        if (previousObservation != null && observed - previousObservation >= 30_000_000_000L) onLongGap()
        val keys = device.state.value.magicKeys
        val irk = keys[MagicKeyType.IRK] ?: return null
        val encryption = keys[MagicKeyType.ENC_KEY] ?: return null
        val report = ProximityReport.decode(address, manufacturer, irk, encryption, observedAt)
        if (Log.isLoggable(NEARBY_SCAN_TAG, Log.DEBUG)) Log.d(NEARBY_SCAN_TAG, "Owned proximity: decoded=${report != null}")
        if (report == null) return null
        if (device.state.value.magicKeys[MagicKeyType.IRK]?.contentEquals(irk) != true ||
            device.state.value.magicKeys[MagicKeyType.ENC_KEY]?.contentEquals(encryption) != true ||
            device.bluetoothDevice.bondState != BluetoothDevice.BOND_BONDED) return null
        pairedObservations[device.macAddress] = observed
        return report
    }

    fun handleCaseBattery(manufacturer: ByteArray, observed: Long, observedAt: Long) {
        if (destroying() || captureBlocked()) return
        val block = CaseBatteryReport.encryptedBlock(manufacturer) ?: return
        val matches = devices.value.values.filterIsInstance<AppleDevice>().mapNotNull { device ->
            retireCaseBatteryObservation(device)
            if (device.bluetoothDevice.bondState != BluetoothDevice.BOND_BONDED) return@mapNotNull null
            val key = device.state.value.magicKeys[MagicKeyType.ENC_KEY]?.copyOf() ?: return@mapNotNull null
            val report = CaseBatteryReport.decode(manufacturer, key, device.metadata.value.model, observedAt)
                ?: return@mapNotNull null
            Triple(device, key, report)
        }
        // A case has no IRK-resolved identity. Ambiguous key/layout matches cannot select a pair.
        val (device, key, report) = matches.singleOrNull() ?: return
        synchronized(device.aacp) {
            if (destroying() || captureBlocked() || devices.value[device.macAddress] !== device ||
                device.bluetoothDevice.bondState != BluetoothDevice.BOND_BONDED ||
                device.metadata.value.model != AirPodsModel.AIRPODS_PRO_3 ||
                device.state.value.magicKeys[MagicKeyType.ENC_KEY]?.contentEquals(key) != true) return
            val previous = caseBatteryObservations[device.macAddress]
            if (previous?.key?.contentEquals(key) == true &&
                (previous.block.contentEquals(block) || observed <= previous.observedNanos)) return
            caseBatteryObservations[device.macAddress] = CaseBatteryObservation(key, block, observed)
            device.receiveCaseBattery(report.batteries, observedAt)
            islandWindow().updatePairedBattery(device)
        }
    }

    fun retireCaseBatteryObservation(device: AppleDevice) {
        val previous = caseBatteryObservations[device.macAddress] ?: return
        if (devices.value[device.macAddress] !== device || device.bluetoothDevice.bondState != BluetoothDevice.BOND_BONDED ||
            device.metadata.value.model != AirPodsModel.AIRPODS_PRO_3 ||
            device.state.value.magicKeys[MagicKeyType.ENC_KEY]?.contentEquals(previous.key) != true)
            caseBatteryObservations.remove(device.macAddress, previous)
    }

    fun replayRecentBattery(device: AppleDevice, socket: android.bluetooth.BluetoothSocket?,
                                    irk: ByteArray, encryption: ByteArray) {
        synchronized(device.aacp) {
            if (destroying() || captureBlocked() || devices.value[device.macAddress] !== device ||
                device.bluetoothDevice.bondState != BluetoothDevice.BOND_BONDED ||
                device.aacp.captureAudioSocket() !== socket ||
                device.state.value.magicKeys[MagicKeyType.IRK]?.contentEquals(irk) != true ||
                device.state.value.magicKeys[MagicKeyType.ENC_KEY]?.contentEquals(encryption) != true) return
            val observation = recentProximityReports.matching(irk, encryption, SystemClock.elapsedRealtimeNanos()).lastOrNull() ?: return
            if (observation.observedNanos <= (pairedObservations[device.macAddress] ?: -1L)) return
            device.receiveNearbyBattery(observation.report.batteries)
            pairedObservations[device.macAddress] = observation.observedNanos
            islandWindow().updatePairedBattery(device, observation.report.model)
        }
    }

    fun forget(address: MacAddress) {
        pairedObservations.remove(address)
        caseBatteryObservations.remove(address)
        recentProximityReports.clear()
    }

    fun clear() {
        recentProximityReports.clear()
        caseBatteryObservations.clear()
    }
}
