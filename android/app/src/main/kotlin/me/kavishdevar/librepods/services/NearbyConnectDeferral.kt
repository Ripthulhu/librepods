package me.kavishdevar.librepods.services

import android.bluetooth.BluetoothProfile
import android.os.SystemClock
import android.util.Log
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import me.kavishdevar.librepods.core.devices.AppleDevice
import java.util.concurrent.ConcurrentHashMap

private const val NEARBY_TAKEOVER_TAG = "NearbyTakeover"

/**
 * Tracks each paired device's native media profile and authenticated case-lid reports, and decides when the
 * Nearby "Connect" action should briefly wait because Android or the case is still settling.
 */
internal class NearbyConnectDeferral(
    /** Not destroying, still the installed device for its address, and still bonded. */
    private val isPairCurrent: (AppleDevice) -> Boolean,
    private val isAudioConnected: (AppleDevice) -> Boolean,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private data class NearbyMedia(val device: AppleDevice, val state: Int, val deferUntil: Long,
        val localCloseUntil: Long = 0)
    private data class NearbyCase(val device: AppleDevice, val closedLocally: Boolean, val deferUntil: Long = 0)
    private val nearbyMedia = ConcurrentHashMap<MacAddress, NearbyMedia>()
    private val nearbyCases = ConcurrentHashMap<MacAddress, NearbyCase>()
    private val pairedLids = mutableMapOf<MacAddress, Boolean>()

    /** A presentation hint only: the Nearby label still requires the real audio-output predicate. */
    fun shouldDefer(device: AppleDevice): Boolean = runCatching {
        if (!isPairCurrent(device)) false
        else if (isAudioConnected(device)) {
            nearbyCases.computeIfPresent(device.macAddress) { _, case ->
                if (case.device === device) case.copy(deferUntil = 0) else case
            }
            nearbyMedia.computeIfPresent(device.macAddress) { _, media ->
                if (media.device === device) media.copy(deferUntil = 0) else media
            }
            false
        }
        else {
            val now = clock()
            val media = nearbyMedia[device.macAddress]?.takeIf { it.device === device }
            val case = nearbyCases[device.macAddress]?.takeIf { it.device === device }
            (case?.deferUntil ?: 0) > now || (media?.deferUntil ?: 0) > now
        }
    }.getOrDefault(false)

    fun observeMedia(device: AppleDevice, state: Int) {
        if (!isPairCurrent(device) ||
            state !in BluetoothProfile.STATE_DISCONNECTED..BluetoothProfile.STATE_DISCONNECTING) return
        val now = clock()
        val previous = nearbyMedia[device.macAddress]?.takeIf { it.device === device }
        if (previous?.state == state) return
        val connected = isAudioConnected(device)
        val failedReconnect = state == BluetoothProfile.STATE_DISCONNECTED && previous?.state == BluetoothProfile.STATE_CONNECTING
        // A close advertisement can arrive just after Android tears down its local profile.
        val localUntil = if (failedReconnect) 0 else if (state in setOf(BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_DISCONNECTING) &&
            (previous?.state == BluetoothProfile.STATE_CONNECTED || connected)) now + 2_000
            else previous?.localCloseUntil ?: 0
        val deferUntil = when (state) {
            BluetoothProfile.STATE_CONNECTING -> now + 18_000
            BluetoothProfile.STATE_CONNECTED -> if (connected) 0 else now + 1_000
            else -> 0
        }
        nearbyMedia[device.macAddress] = NearbyMedia(device, state, deferUntil, localUntil)
        if (failedReconnect) {
            nearbyCases.computeIfPresent(device.macAddress) { _, case ->
                if (case.device === device) case.copy(closedLocally = false, deferUntil = 0) else case
            }
        }
    }

    /** Called only for the already authenticated, ordered lid reports. Returns true for a new opening. */
    fun observeCase(device: AppleDevice, open: Boolean): Boolean {
        if (!isPairCurrent(device)) return false
        if (pairedLids.put(device.macAddress, open) == open) return false
        val now = clock()
        val previous = nearbyCases[device.macAddress]?.takeIf { it.device === device }
        val media = nearbyMedia[device.macAddress]?.takeIf { it.device === device }
        val local = isAudioConnected(device) || media?.state == BluetoothProfile.STATE_CONNECTED ||
            (media?.localCloseUntil ?: 0) > now
        // A close must not expose Connect between rapid lid reports or extend an existing opening's deadline.
        val activeDeadline = previous?.deferUntil?.takeIf { it > now } ?: 0
        val deferUntil = if (activeDeadline > 0) activeDeadline
            else if (open && previous?.closedLocally == true) now + 4_000 else 0
        nearbyCases[device.macAddress] = NearbyCase(device, !open && local, deferUntil)
        if (open && deferUntil > 0 && Log.isLoggable(NEARBY_TAKEOVER_TAG, Log.DEBUG))
            Log.d(NEARBY_TAKEOVER_TAG, "Deferring Connect briefly after a local case close")
        return open
    }

    /** The last native media profile state seen for [address], for diagnostics. */
    fun mediaState(address: MacAddress): Int? = nearbyMedia[address]?.state

    /** A long advertising gap means the next lid report must count as a change again. */
    fun forgetLid(address: MacAddress) {
        pairedLids.remove(address)
    }

    /** A newly installed device starts without the previous device's media or case history. */
    fun resetDevice(address: MacAddress) {
        nearbyMedia.remove(address)
        nearbyCases.remove(address)
    }

    fun forget(address: MacAddress) {
        pairedLids.remove(address)
        nearbyMedia.remove(address)
        nearbyCases.remove(address)
    }

    fun clearDeferrals() {
        nearbyMedia.clear()
        nearbyCases.clear()
    }

    fun clear() {
        pairedLids.clear()
        clearDeferrals()
    }
}
