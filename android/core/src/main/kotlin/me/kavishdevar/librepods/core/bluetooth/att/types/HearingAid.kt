/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.core.bluetooth.att.types

import me.kavishdevar.librepods.core.bluetooth.att.ATTHandle
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class HearingAidSettings(
    val leftEQ: FloatArray,
    val rightEQ: FloatArray,
    val leftAmplification: Float,
    val rightAmplification: Float,
    val leftTone: Float,
    val rightTone: Float,
    val leftConversationBoost: Boolean,
    val rightConversationBoost: Boolean,
    val leftAmbientNoiseReduction: Float,
    val rightAmbientNoiseReduction: Float,
    val netAmplification: Float,
    val balance: Float,
    val ownVoiceAmplification: Float
) {
    fun withAmplification(amplification: Float = netAmplification, balance: Float = this.balance) = copy(
        leftAmplification = amplification - balance / 2,
        rightAmplification = amplification + balance / 2,
        netAmplification = amplification,
        balance = balance
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as HearingAidSettings

        if (leftAmplification != other.leftAmplification) return false
        if (rightAmplification != other.rightAmplification) return false
        if (leftTone != other.leftTone) return false
        if (rightTone != other.rightTone) return false
        if (leftConversationBoost != other.leftConversationBoost) return false
        if (rightConversationBoost != other.rightConversationBoost) return false
        if (leftAmbientNoiseReduction != other.leftAmbientNoiseReduction) return false
        if (rightAmbientNoiseReduction != other.rightAmbientNoiseReduction) return false
        if (!leftEQ.contentEquals(other.leftEQ)) return false
        if (!rightEQ.contentEquals(other.rightEQ)) return false
        if (ownVoiceAmplification != other.ownVoiceAmplification) return false

        return true
    }

    override fun hashCode(): Int {
        var result = leftAmplification.hashCode()
        result = 31 * result + rightAmplification.hashCode()
        result = 31 * result + leftTone.hashCode()
        result = 31 * result + rightTone.hashCode()
        result = 31 * result + leftConversationBoost.hashCode()
        result = 31 * result + rightConversationBoost.hashCode()
        result = 31 * result + leftAmbientNoiseReduction.hashCode()
        result = 31 * result + rightAmbientNoiseReduction.hashCode()
        result = 31 * result + leftEQ.contentHashCode()
        result = 31 * result + rightEQ.contentHashCode()
        result = 31 * result + ownVoiceAmplification.hashCode()
        return result
    }
}

fun parseHearingAidSettingsResponse(data: ByteArray): HearingAidSettings? {
    if (data.size < 104) return null
    if (data[2].toInt() !in setOf(0, 0x64) &&
        !data.copyOfRange(0, 4).contentEquals(byteArrayOf(2, 0, 0x60, 0))) return null
    val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    if ((4 until 104 step 4).any { !buffer.getFloat(it).isFinite() }) return null

    buffer.get() // skip 0x02
    buffer.get() // skip 0x02
    buffer.getShort() // skip 0x60 0x00

    val leftEQ = FloatArray(8)
    for (i in 0..7) {
        leftEQ[i] = buffer.float
    }
    val leftAmplification = buffer.float
    val leftTone = buffer.float
    val leftConvFloat = buffer.float
    val leftConversationBoost = leftConvFloat > 0.5f
    val leftAmbientNoiseReduction = buffer.float

    val rightEQ = FloatArray(8)
    for (i in 0..7) {
        rightEQ[i] = buffer.float
    }
    val rightAmplification = buffer.float
    val rightTone = buffer.float
    val rightConvFloat = buffer.float
    val rightConversationBoost = rightConvFloat > 0.5f
    val rightAmbientNoiseReduction = buffer.float

    val ownVoiceAmplification = buffer.float

    val avg = leftAmplification / 2 + rightAmplification / 2
    val diff = rightAmplification - leftAmplification
    if (!diff.isFinite()) return null

    return HearingAidSettings(
        leftEQ = leftEQ,
        rightEQ = rightEQ,
        leftAmplification = leftAmplification,
        rightAmplification = rightAmplification,
        leftTone = leftTone,
        rightTone = rightTone,
        leftConversationBoost = leftConversationBoost,
        rightConversationBoost = rightConversationBoost,
        leftAmbientNoiseReduction = leftAmbientNoiseReduction,
        rightAmbientNoiseReduction = rightAmbientNoiseReduction,
        netAmplification = avg,
        balance = diff,
        ownVoiceAmplification = ownVoiceAmplification
    )
}

fun hearingAidSettingsWritable(data: ByteArray): Boolean =
    data.size == 104 && data[2].toInt() in setOf(0, 0x64) && parseHearingAidSettingsResponse(data) != null

fun sendHearingAidSettings(
    currentData: ByteArray,
    hearingAidSettings: HearingAidSettings,
    sender: (ATTHandle, ByteArray) -> Unit
) {
    if (!hearingAidSettingsWritable(currentData)) return
    val original = parseHearingAidSettingsResponse(currentData) ?: return
    if (hearingAidSettings.leftEQ.size != 8 || hearingAidSettings.rightEQ.size != 8
    ) return
    val buffer = ByteBuffer.wrap(currentData.copyOf()).order(ByteOrder.LITTLE_ENDIAN)

    // Left EQ
    for (i in 0..7) {
        buffer.putFloat(4 + i * 4, hearingAidSettings.leftEQ[i])
    }

    // Left ear adjustments
    buffer.putFloat(36, hearingAidSettings.leftAmplification)
    buffer.putFloat(40, hearingAidSettings.leftTone)
    if (hearingAidSettings.leftConversationBoost != original.leftConversationBoost) {
        buffer.putFloat(44, if (hearingAidSettings.leftConversationBoost) 1.0f else 0.0f)
    }
    buffer.putFloat(48, hearingAidSettings.leftAmbientNoiseReduction)

    // Right EQ
    for (i in 0..7) {
        buffer.putFloat(52 + i * 4, hearingAidSettings.rightEQ[i])
    }

    // Right ear adjustments
    buffer.putFloat(84, hearingAidSettings.rightAmplification)
    buffer.putFloat(88, hearingAidSettings.rightTone)
    if (hearingAidSettings.rightConversationBoost != original.rightConversationBoost) {
        buffer.putFloat(92, if (hearingAidSettings.rightConversationBoost) 1.0f else 0.0f)
    }
    buffer.putFloat(96, hearingAidSettings.rightAmbientNoiseReduction)

    // Own voice amplification
    buffer.putFloat(100, hearingAidSettings.ownVoiceAmplification)

    val data = buffer.array()
    if (parseHearingAidSettingsResponse(data) != null) sender(ATTHandle.HEARING_AID, data)
}
