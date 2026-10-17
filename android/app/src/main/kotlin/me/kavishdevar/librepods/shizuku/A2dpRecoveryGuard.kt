package me.kavishdevar.librepods.shizuku

import java.util.Locale

/** Cancellation never reconnects a retired route; owner death permits only the original pair's rollback. */
internal class A2dpRecoveryGuard(address: String) {
    val address = address.uppercase(Locale.ROOT).also {
        require(it.matches(Regex("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}")) &&
            it != "00:00:00:00:00:00" && it != "FF:FF:FF:FF:FF:FF") { "Invalid selected Bluetooth address" }
    }
    @Volatile private var cancelled = false
    @Volatile private var ownerAlive = true
    @Volatile private var initiallyConnected = false
    @Volatile private var disconnectIssued = false

    fun begin(connected: Boolean) { initiallyConnected = connected }
    fun cancel() { cancelled = true }
    fun ownerDied() { ownerAlive = false }
    fun mayDisconnect(competing: Boolean) = !cancelled && ownerAlive && initiallyConnected && !competing
    fun disconnectedByUs() { disconnectIssued = true }
    fun mayReconnect(competing: Boolean) = !cancelled && !competing &&
        (ownerAlive || (initiallyConnected && disconnectIssued))
    fun mayRollback(competing: Boolean) = initiallyConnected && disconnectIssued && mayReconnect(competing)
    fun liveOwner() = !cancelled && ownerAlive

    companion object {
        const val AACP_UUID = "74ec2172-0bad-4d01-8f77-997b2be0722a"
        fun isAirPodsCandidate(bonded: Boolean, classic: Boolean, uuids: Collection<String>) =
            bonded && classic && uuids.any { it.equals(AACP_UUID, ignoreCase = true) }
    }
}
