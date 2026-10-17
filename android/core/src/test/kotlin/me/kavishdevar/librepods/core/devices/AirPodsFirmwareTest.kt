package me.kavishdevar.librepods.core.devices

import org.junit.Assert.*
import org.junit.Test

class AirPodsFirmwareTest {
    @Test fun releaseSelectionMatchesNtpodsMetadataVectors() {
        for ((version1, version3, expected) in listOf(
            Triple("90.3431.6807", "6619472", "6F21"),
            Triple("6F21", "6619472", "6F21"),
            Triple("90.3431.6807", "9442752", "9A348"),
            Triple("", "6357536", "6B34"),
            Triple("9A348", "", "9A348"),
            Triple("9A348", "9A5347a", "9A5347a"),
            Triple("6.8.8", "", "6.8.8"),
            Triple("90.3431.6807", "", null),
            Triple("6619472", "unknown", null),
            Triple("90.3431.6807", "6619473", null),
            Triple("90.3431.6807", "4294967296", null),
            Triple("90.3431.6807", "0", null),
        )) assertEquals("version1=$version1 version3=$version3", expected,
            AirPodsFirmware.publicVersion(version1, version3))
    }

    @Test fun packedReleaseMajorsAndBoundsAreStrict() {
        assertEquals(9, AirPodsFirmware.major("9442752"))
        assertEquals(6, AirPodsFirmware.major("6357536"))
        assertEquals(6, AirPodsFirmware.major("6619472"))
        assertEquals(8, AirPodsFirmware.major(" 8B34 "))
        for (invalid in listOf("", "unknown9", "90.3388000000000000.1786", "90.3431.6807",
            "8.300.1", "8.2", "999A1", "0A1", "6619473", "4294967296", "-9442752", "+9442752",
            "9442752.0", " 9442752", "９４４２７５２", "999999999999999999999999", "0", "104857600"))
            assertNull(invalid, AirPodsFirmware.major(invalid))
    }
}
