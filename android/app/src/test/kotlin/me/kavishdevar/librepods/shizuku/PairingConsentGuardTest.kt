package me.kavishdevar.librepods.shizuku

import org.junit.Assert.*
import org.junit.Test

class PairingConsentGuardTest {
    @Test fun consentIsScopedToTheSelectedClassicDeviceWhileItIsActuallyBonding() {
        val guard = PairingConsentGuard("aa:bb:cc:dd:ee:01", 10_000)
        assertEquals("AA:BB:CC:DD:EE:01", guard.address)
        assertTrue(guard.mayConfirm(10_100, guard.address, classic = true, bonding = true, ownerCurrent = true))
        assertFalse(guard.mayConfirm(10_100, "AA:BB:CC:DD:EE:02", true, true, true))
        assertFalse(guard.mayConfirm(10_100, guard.address, false, true, true))
        assertFalse(guard.mayConfirm(10_100, guard.address, true, false, true))
        assertFalse(guard.mayConfirm(10_100, guard.address, true, true, false))
    }

    @Test fun expirationCancellationAndOwnerDeathCannotConfirmALatePairingRequest() {
        val guard = PairingConsentGuard("AA:BB:CC:DD:EE:01", 10_000)
        assertFalse(guard.mayConfirm(9_999, guard.address, true, true, true))
        assertTrue(guard.mayConfirm(10_999, guard.address, true, true, true))
        assertFalse(guard.mayConfirm(11_000, guard.address, true, true, true))
        guard.cancel()
        assertFalse(guard.mayConfirm(10_100, guard.address, true, true, true))
        for (address in listOf("invalid", "00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF"))
            assertThrows(IllegalArgumentException::class.java) { PairingConsentGuard(address, 10_000) }
    }
}
