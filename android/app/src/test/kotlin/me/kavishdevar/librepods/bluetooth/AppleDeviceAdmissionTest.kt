package me.kavishdevar.librepods.bluetooth

import org.junit.Assert.*
import org.junit.Test

class AppleDeviceAdmissionTest {
    @Test fun savedBondedAppleIdentityDoesNotWaitForServiceUuidDiscovery() {
        assertTrue(canLoadAppleDevice(bonded = true, savedAppleDevice = true, advertisesAacp = false))
        assertTrue(canLoadAppleDevice(bonded = true, savedAppleDevice = true, advertisesAacp = true))
    }

    @Test fun aNewBondedDeviceStillNeedsTheAacpServiceAndUnbondedIdentitiesNeverLoad() {
        assertFalse(canLoadAppleDevice(bonded = true, savedAppleDevice = false, advertisesAacp = false))
        assertTrue(canLoadAppleDevice(bonded = true, savedAppleDevice = false, advertisesAacp = true))
        for (saved in listOf(false, true)) for (aacp in listOf(false, true))
            assertFalse(canLoadAppleDevice(bonded = false, savedAppleDevice = saved, advertisesAacp = aacp))
    }
}
