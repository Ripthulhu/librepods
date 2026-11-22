package me.kavishdevar.librepods.dongle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DongleHearingRecordsTest {
    private fun profile(prefixSize: Byte, payload: Byte) = ByteArray(DongleHearingRecords.RECORD_SIZE).also {
        it[0] = 2
        it[1] = 2
        it[2] = prefixSize
        it.fill(payload, fromIndex = 4)
    }

    @Test fun aHearingReadbackMayReportADifferentWriterSize() {
        // Apple's writer sends 100 in byte 2; a fitted readback may report 96.
        assertTrue(DongleHearingRecords.hearingProfileMatches(profile(0x60, 7), profile(0x64, 7)))
    }

    @Test fun formatBytesAndPayloadMustStillMatch() {
        assertFalse(DongleHearingRecords.hearingProfileMatches(profile(0x64, 7), profile(0x64, 8)))
        val otherFormat = profile(0x64, 7).also { it[1] = 0 }
        assertFalse(DongleHearingRecords.hearingProfileMatches(otherFormat, profile(0x64, 7)))
    }

    @Test fun transparencyRecordsMustBeFullLength() {
        assertFalse(DongleHearingRecords.validTransparency(ByteArray(100)))
        assertTrue(DongleHearingRecords.validTransparency(ByteArray(DongleHearingRecords.RECORD_SIZE)))
    }
}
