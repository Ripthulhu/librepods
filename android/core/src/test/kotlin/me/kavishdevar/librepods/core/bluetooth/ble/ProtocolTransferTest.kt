package me.kavishdevar.librepods.core.bluetooth.ble

import me.kavishdevar.librepods.core.bluetooth.aacp.packet.BatteryInfoPacket
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.InformationPacket
import me.kavishdevar.librepods.core.bluetooth.aacp.packet.MagicKeyResponsePacket
import me.kavishdevar.librepods.core.bluetooth.aacp.types.MagicKeyType
import me.kavishdevar.librepods.core.devices.*
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class ProtocolTransferTest {
    private val irk = byteArrayOf(0x79, 4, 0x65, 0x1e, 0xe2.toByte(), 0xcc.toByte(), 0xd9.toByte(),
        0x26, 0xf2.toByte(), 0x6e, 0x20, 0xee.toByte(), 0x3e, 0xcc.toByte(), 0xde.toByte(), 0x79)
    private val key = ByteArray(16) { (it + 1).toByte() }
    private fun advertisement(flags: Int = 0x64, lid: Int = 0x38, levels: ByteArray = byteArrayOf(80, 70, 60),
                              model: Int = 0x2720): ByteArray {
        val plain = ByteArray(16).apply { levels.copyInto(this, 1) }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return ByteArray(27).apply {
            this[0] = 7; this[1] = 25; this[2] = 1
            this[3] = (model shr 8).toByte(); this[4] = model.toByte(); this[5] = flags.toByte()
            this[8] = lid.toByte(); cipher.doFinal(plain).copyInto(this, 11)
        }
    }

    @Test fun ownedEncryptedReportsKeepExactLevelsUnknownLidsAndClosedCaseAvailability() {
        val closed = requireNotNull(ProximityReport.decode("5A:16:2B:91:D1:CD", advertisement(), irk, key, 1000))
        assertEquals(listOf(80, 70, 60), closed.batteries.map { it.level })
        assertEquals(false, closed.lidOpen)
        assertTrue(closed.bothInCase)
        assertFalse(closed.mayReconnect)
        val unknown = requireNotNull(ProximityReport.decode("5A:16:2B:91:D1:CD", advertisement(flags = 0x20), irk, key, 2000))
        assertNull(unknown.lidOpen)
        assertTrue(unknown.mayReconnect)
        assertNull(ProximityReport.decode("5A:16:2B:91:D1:CC", advertisement(), irk, key, 1000))
        assertNull(ProximityReport.decode("DA:16:2B:91:D1:CD", advertisement(), irk, key, 1000))
        assertNull(ProximityReport.decode("5A:16:2B:91:D1:CD", advertisement(levels = byteArrayOf(101, 70, 60)), irk, key, 1000))
        assertNull(ProximityReport.decode("5A:16:2B:91:D1:CD", advertisement(model = 0xffff), irk, key, 1000))
        val compound = byteArrayOf(0x10, 1, 0x42) + advertisement()
        assertEquals(closed, ProximityReport.decode("5A:16:2B:91:D1:CD", compound, irk, key, 1000))
        val max = requireNotNull(ProximityReport.decode("5A:16:2B:91:D1:CD",
            advertisement(model = 0x1f20, levels = byteArrayOf(-1, 73, -1)), irk, key, 1000))
        assertEquals(BatteryComponent.HEADSET, max.batteries.single().component)
        assertEquals(73, max.batteries.single().level)
        assertNull(max.lidOpen)
        assertFalse(max.mayReconnect)
    }

    @Test fun positionalMetadataPreservesEmptyFieldsAndRejectsEveryTruncation() {
        val fields = listOf("Écouteurs", "A3063", "Apple", "EXAMPLE", "90.3431.6807", "", "1", "updater", "LEFT", "RIGHT", "9442752")
        val packet = byteArrayOf(4, 0, 4, 0, 0x1d, 0, 2, 0, 0, 4, 0) +
            fields.fold(byteArrayOf()) { bytes, field -> bytes + field.toByteArray() + byteArrayOf(0) }
        val parsed = InformationPacket.parse(packet).metadata
        assertEquals("", parsed.version2)
        assertEquals("LEFT", parsed.leftSerialNumber)
        assertEquals("RIGHT", parsed.rightSerialNumber)
        assertEquals("9A348", parsed.publicFirmware)
        assertEquals(9, parsed.firmwareMajor)
        for (length in 0 until packet.size) assertThrows(IllegalArgumentException::class.java) {
            InformationPacket.parse(packet.copyOf(length))
        }
        assertThrows(IllegalArgumentException::class.java) {
            InformationPacket.parse(packet.copyOf().also { it[11] = 0xff.toByte() })
        }
    }

    private fun batteryPacket(vararg rows: ByteArray) = byteArrayOf(4, 0, 4, 0, 4, 0, rows.size.toByte()) +
        rows.fold(byteArrayOf()) { bytes, row -> bytes + row }
    private fun row(component: Int, level: Int, status: Int = 2) = byteArrayOf(component.toByte(), 1, level.toByte(), status.toByte(), 1)

    @Test fun countedBatteryRecordsMergeOmissionsAndUnavailableSlotsWithoutRenewingTheirAge() {
        val previous = setOf(Battery(BatteryComponent.LEFT, 80, BatteryStatus.CHARGING, 1000),
            Battery(BatteryComponent.CASE, 60, BatteryStatus.CHARGING, 1000))
        val packet = batteryPacket(row(2, 70), row(8, 255, 4), row(99, 10))
        val merged = mergeBatteryReports(previous, BatteryInfoPacket.parse(packet).batteries).associateBy { it.component }
        assertEquals(previous.first(), merged[BatteryComponent.LEFT])
        assertEquals(70, merged[BatteryComponent.RIGHT]?.level)
        assertEquals(60, merged[BatteryComponent.CASE]?.level)
        assertEquals(1000L, merged[BatteryComponent.CASE]?.observedAt)
        assertFalse(requireNotNull(merged[BatteryComponent.CASE]).present)
        assertFalse(requireNotNull(merged[BatteryComponent.CASE]).isLive(1001))
        for (bad in listOf(packet.copyOf(packet.size - 1), packet.copyOf().also { it[6] = 4 },
            batteryPacket(row(4, 101)), batteryPacket(row(4, 81), row(4, 82)))) {
            assertThrows(IllegalArgumentException::class.java) { BatteryInfoPacket.parse(bad) }
        }
    }

    @Test fun keyRecordsValidateWholeResponseWithoutSignedLengthOrDuplicateConfusion() {
        fun keyRecord(type: Int, size: Int = 16) = byteArrayOf(type.toByte(), (type shr 8).toByte(),
            size.toByte(), (size shr 8).toByte()) + ByteArray(size) { (it + 1).toByte() }
        val packet = byteArrayOf(4, 0, 4, 0, 0x31, 0, 3) + keyRecord(1) + keyRecord(0x401, 256) + keyRecord(4)
        assertEquals(setOf(MagicKeyType.IRK, MagicKeyType.ENC_KEY), MagicKeyResponsePacket.parse(packet).magicKeys.keys)
        for (length in 0 until packet.size) assertThrows(IllegalArgumentException::class.java) {
            MagicKeyResponsePacket.parse(packet.copyOf(length))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MagicKeyResponsePacket.parse(byteArrayOf(4, 0, 4, 0, 0x31, 0, 2) + keyRecord(1) + keyRecord(1))
        }
        for (records in listOf(emptyList(), listOf(keyRecord(1)), listOf(keyRecord(4)),
            listOf(keyRecord(0x401, 256)), listOf(keyRecord(1), keyRecord(0x401, 256)))) {
            val incomplete = byteArrayOf(4, 0, 4, 0, 0x31, 0, records.size.toByte()) +
                records.fold(byteArrayOf()) { bytes, record -> bytes + record }
            assertThrows(IllegalArgumentException::class.java) { MagicKeyResponsePacket.parse(incomplete) }
        }
    }
}
