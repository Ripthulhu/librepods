package me.kavishdevar.librepods.services

import android.util.Log
import me.kavishdevar.librepods.companion.CompanionHoldStore
import me.kavishdevar.librepods.core.bluetooth.MacAddress
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "LibrePodsService"

/**
 * The pairs whose audio the user handed to the companion PC. A held pair is not reconnected automatically.
 * Until the saved choice has been read ([ready]), every automatic control connection is blocked.
 */
internal class CompanionHolds(
    /** Resolved on every use so the service's lazy store is not created early. */
    private val store: () -> CompanionHoldStore,
) {
    private val released = ConcurrentHashMap.newKeySet<MacAddress>()
    private val lock = Any()
    @Volatile var ready = false
        private set

    fun isHeld(address: MacAddress): Boolean = released.contains(address)

    fun restore() = synchronized(lock) {
        ready = false
        try {
            val held = store().load().map(::MacAddress)
            released.clear()
            released.addAll(held)
            ready = true
        } catch (_: Exception) {
            Log.w(TAG, "Saved audio choice is unavailable; automatic control connections are blocked")
        }
    }

    /** Persists the new choice first; returns false and keeps the old one when it cannot be saved. */
    fun set(address: MacAddress, held: Boolean): Boolean = synchronized(lock) {
        if (!ready) return false
        if (released.contains(address) == held) return true
        val next = released.toMutableSet().apply { if (held) add(address) else remove(address) }
        try { store().save(next.map { it.value }.toSet()) }
        catch (_: Exception) { return false }
        if (held) released.add(address) else released.remove(address)
        true
    }
}
