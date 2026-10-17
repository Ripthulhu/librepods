package me.kavishdevar.librepods.shizuku

import org.junit.Assert.*
import org.junit.Test

class A2dpRecoveryGuardTest {
    @Test fun cancellationCannotReconnectAnOldPair() {
        val old = A2dpRecoveryGuard("aa:bb:cc:dd:ee:ff")
        old.begin(true)
        assertTrue(old.mayDisconnect(false))
        old.disconnectedByUs()
        old.cancel()
        assertFalse(old.mayReconnect(false))
        val next = A2dpRecoveryGuard("AA:BB:CC:DD:EE:FE")
        assertTrue(next.mayReconnect(false))
        assertFalse(old.mayReconnect(false))
    }

    @Test fun ownerDeathOnlyRollsBackAnOriginallyConnectedPair() {
        val pending = A2dpRecoveryGuard("AA:BB:CC:DD:EE:FF")
        pending.begin(true)
        pending.ownerDied()
        assertFalse(pending.mayDisconnect(false))
        assertFalse(pending.mayReconnect(false))
        assertFalse(pending.mayRollback(false))
        val disconnected = A2dpRecoveryGuard("AA:BB:CC:DD:EE:FF")
        disconnected.begin(true)
        disconnected.disconnectedByUs()
        disconnected.ownerDied()
        assertTrue(disconnected.mayReconnect(false))
        assertTrue(disconnected.mayRollback(false))
        assertFalse(disconnected.mayReconnect(true))
        disconnected.cancel()
        assertFalse(disconnected.mayRollback(false))
        val initiallyDisconnected = A2dpRecoveryGuard("AA:BB:CC:DD:EE:FF")
        initiallyDisconnected.begin(false)
        initiallyDisconnected.ownerDied()
        assertFalse(initiallyDisconnected.mayRollback(false))
    }

    @Test fun onlyBondedClassicAacpDevicesPass() {
        val uuid = listOf(A2dpRecoveryGuard.AACP_UUID)
        assertTrue(A2dpRecoveryGuard.isAirPodsCandidate(true, true, uuid))
        assertFalse(A2dpRecoveryGuard.isAirPodsCandidate(false, true, uuid))
        assertFalse(A2dpRecoveryGuard.isAirPodsCandidate(true, false, uuid))
        assertFalse(A2dpRecoveryGuard.isAirPodsCandidate(true, true, emptyList()))
        assertThrows(IllegalArgumentException::class.java) { A2dpRecoveryGuard("invalid") }
    }
}
