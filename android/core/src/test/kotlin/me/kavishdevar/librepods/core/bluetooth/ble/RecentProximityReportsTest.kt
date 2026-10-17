package me.kavishdevar.librepods.core.bluetooth.ble

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class RecentProximityReportsTest {
    private val address = "5A:16:2B:91:D1:CD"
    private val irk = byteArrayOf(0x79, 4, 0x65, 0x1e, 0xe2.toByte(), 0xcc.toByte(), 0xd9.toByte(),
        0x26, 0xf2.toByte(), 0x6e, 0x20, 0xee.toByte(), 0x3e, 0xcc.toByte(), 0xde.toByte(), 0x79)
    private val key = ByteArray(16) { (it + 1).toByte() }

    @Test fun preKeyRawReportBecomesUsableOnlyForMatchingCompleteKeysWithItsOriginalAge() {
        val recent = RecentProximityReports()
        val raw = advertisement(80)
        recent.observe(address, raw, 1_000_000_000, 1000, 2_000_000_000)
        raw.fill(0)
        assertTrue(recent.matching(byteArrayOf(), key, 2_000_000_000).isEmpty())
        assertTrue(recent.matching(irk, key.copyOf(15), 2_000_000_000).isEmpty())
        assertTrue(recent.matching(ByteArray(16) { 1 }, key, 2_000_000_000).isEmpty())
        val replay = recent.matching(irk, key, 2_000_000_000).single()
        assertEquals(1_000_000_000L, replay.observedNanos)
        assertEquals(listOf(80, 70, 60), replay.report.batteries.map { it.level })
        assertTrue(replay.report.batteries.all { it.observedAt == 1000L })
    }

    @Test fun latestFullFrameKeepsUnavailableSlotsInsteadOfResurrectingOlderChargingValues() {
        val recent = RecentProximityReports()
        recent.observe(address, advertisement(80 or 128), 1_000_000_000, 1000, 3_000_000_000)
        recent.observe(address, advertisement(127), 2_000_000_000, 2000, 3_000_000_000)
        val latest = recent.matching(irk, key, 3_000_000_000).last()
        assertEquals(2_000_000_000L, latest.observedNanos)
        assertFalse(latest.report.batteries.first().present)
        assertNull(latest.report.batteries.first().observedAt)
        assertEquals(70, latest.report.batteries.elementAt(1).level)
        recent.clear()
        assertTrue(recent.matching(irk, key, 3_000_000_000).isEmpty())
    }

    @Test fun cacheIsBoundedDeduplicatedAndRejectsExpiredFutureMalformedAndForeignReports() {
        val recent = RecentProximityReports()
        for (index in 1..20) recent.observe(address, advertisement(index), index * 1_000_000L,
            index.toLong(), 20_000_000)
        assertEquals(16, recent.matching(irk, key, 20_000_000).size)
        assertEquals(5L, recent.matching(irk, key, 20_000_000).first().report.batteries.first().observedAt)
        recent.observe(address, advertisement(20), 21_000_000, 21, 21_000_000)
        assertEquals(16, recent.matching(irk, key, 21_000_000).size)
        assertEquals(21L, recent.matching(irk, key, 21_000_000).last().report.batteries.first().observedAt)
        recent.expire(15_021_000_000)
        assertTrue(recent.matching(irk, key, 15_021_000_000).isEmpty())
        recent.observe(address, advertisement(80), 22_000_000, 22, 21_000_000)
        recent.observe(address, byteArrayOf(7, 25, 1), 20_000_000, 20, 21_000_000)
        recent.observe("5A:16:2B:91:D1:CC", advertisement(80), 20_000_000, 20, 21_000_000)
        assertTrue(recent.matching(irk, key, 21_000_000).isEmpty())
    }

    private fun advertisement(left: Int): ByteArray {
        val plain = ByteArray(16).apply { this[1] = left.toByte(); this[2] = 70; this[3] = 60 }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        }
        return ByteArray(27).apply {
            this[0] = 7; this[1] = 25; this[2] = 1; this[3] = 0x27; this[4] = 0x20; this[5] = 0x64
            cipher.doFinal(plain).copyInto(this, 11)
        }
    }
}
