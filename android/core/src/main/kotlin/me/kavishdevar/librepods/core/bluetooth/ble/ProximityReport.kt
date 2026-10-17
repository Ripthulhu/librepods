/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.core.bluetooth.ble

import me.kavishdevar.librepods.core.bluetooth.verifyRPA
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.Battery
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.core.devices.BatteryStatus
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

data class ProximityReport(val model: AirPodsModel, val batteries: Set<Battery>, val lidOpen: Boolean?,
    val bothInCase: Boolean, val connection: Int) {
    val mayReconnect: Boolean get() = model !in setOf(AirPodsModel.AIRPODS_MAX_LIGHTNING,
        AirPodsModel.AIRPODS_MAX_USBC, AirPodsModel.AIRPODS_MAX_2, AirPodsModel.UNKNOWN) && !bothInCase && lidOpen != false

    companion object {
        fun model(modelId: Int): AirPodsModel = when (modelId) {
            0x0220 -> AirPodsModel.AIRPODS_1
            0x0f20 -> AirPodsModel.AIRPODS_2
            0x1320 -> AirPodsModel.AIRPODS_3
            0x1920 -> AirPodsModel.AIRPODS_4
            0x1b20 -> AirPodsModel.AIRPODS_4_ANC
            0x0e20 -> AirPodsModel.AIRPODS_PRO_1
            0x1420 -> AirPodsModel.AIRPODS_PRO_2_LIGHTNING
            0x2420 -> AirPodsModel.AIRPODS_PRO_2_USBC
            0x2720 -> AirPodsModel.AIRPODS_PRO_3
            0x3020 -> AirPodsModel.AIRPODS_5_WCC
            0x3620 -> AirPodsModel.AIRPODS_5
            0x0a20 -> AirPodsModel.AIRPODS_MAX_LIGHTNING
            0x1f20 -> AirPodsModel.AIRPODS_MAX_USBC
            else -> AirPodsModel.UNKNOWN
        }

        fun decode(address: String, manufacturer: ByteArray, irk: ByteArray, encryption: ByteArray,
                   observedAt: Long): ProximityReport? {
            if (observedAt < 0 || !verifyRPA(address, irk) || encryption.size != 16 || encryption.all { it == 0.toByte() }) return null
            val data = NearbyAirPods.proximityRecord(manufacturer) ?: return null
            if (data[2] != 1.toByte()) return null
            val model = model(((data[3].toInt() and 255) shl 8) or (data[4].toInt() and 255))
            if (model == AirPodsModel.UNKNOWN) return null
            val plain = try {
                Cipher.getInstance("AES/ECB/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(encryption, "AES"))
                }.doFinal(data.copyOfRange(11, 27))
            } catch (_: Exception) { return null }
            if ((1..3).any { (plain[it].toInt() and 127) in 101..126 }) return null
            val flags = data[5].toInt() and 255
            val primaryLeft = flags and 0x20 != 0
            fun battery(component: BatteryComponent, index: Int): Battery {
                val raw = plain[index].toInt() and 255
                val level = raw and 127
                val status = if (level == 127) BatteryStatus.DISCONNECTED
                    else if (raw and 128 != 0) BatteryStatus.CHARGING else BatteryStatus.NOT_CHARGING
                return Battery(component, if (level == 127) 0 else level, status,
                    if (level == 127) null else observedAt)
            }
            val max = model == AirPodsModel.AIRPODS_MAX_LIGHTNING || model == AirPodsModel.AIRPODS_MAX_USBC
            if (max) {
                val slots = listOf(if (primaryLeft) 1 else 2, if (primaryLeft) 2 else 1, 3)
                val headset = slots.map { battery(BatteryComponent.HEADSET, it) }.firstOrNull { it.present }
                    ?: battery(BatteryComponent.HEADSET, 1)
                return ProximityReport(model, setOf(headset), null, false, data[10].toInt() and 255)
            }
            return ProximityReport(model, setOf(battery(BatteryComponent.LEFT, if (primaryLeft) 1 else 2),
                battery(BatteryComponent.RIGHT, if (primaryLeft) 2 else 1), battery(BatteryComponent.CASE, 3)),
                if (flags and 0x44 != 0) data[8].toInt() and 8 == 0 else null,
                flags and 4 != 0, data[10].toInt() and 255)
        }
    }
}
