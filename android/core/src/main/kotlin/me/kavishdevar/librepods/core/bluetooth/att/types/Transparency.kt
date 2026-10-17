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

data class TransparencySettings(
    val enabled: Boolean,
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
    val ownVoiceAmplification: Float? = null
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

        other as TransparencySettings

        if (enabled != other.enabled) return false
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
        var result = enabled.hashCode()
        result = 31 * result + leftAmplification.hashCode()
        result = 31 * result + rightAmplification.hashCode()
        result = 31 * result + leftTone.hashCode()
        result = 31 * result + rightTone.hashCode()
        result = 31 * result + leftConversationBoost.hashCode()
        result = 31 * result + rightConversationBoost.hashCode()
        result = 31 * result + leftAmbientNoiseReduction.hashCode()
        result = 31 * result + rightAmbientNoiseReduction.hashCode()
        result = 31 * result + leftEQ.contentHashCode()
        result = 31 * result + rightEQ.contentHashCode()
        result = 31 * result + (ownVoiceAmplification?.hashCode() ?: 0)
        return result
    }
}

fun parseTransparencySettingsResponse(data: ByteArray): TransparencySettings? {
    if (data.size < 100 || data.size in 101..103) return null
    val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    val floatBytes = if (data.size >= 104) 104 else 100
    if ((0 until floatBytes step 4).any { !buffer.getFloat(it).isFinite() }) return null

    val enabled = buffer.float

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

    val ownVoiceAmplification = if (buffer.remaining() >= 4) {
        buffer.float
    } else {
        null
    }

    val avg = leftAmplification / 2 + rightAmplification / 2
    val diff = rightAmplification - leftAmplification
    if (!diff.isFinite()) return null

    return TransparencySettings(
        enabled = enabled > 0.5f,
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

fun sendTransparencySettings(
    currentData: ByteArray,
    transparencySettings: TransparencySettings,
    writer: (ATTHandle, ByteArray) -> Unit
) {
    val original = parseTransparencySettingsResponse(currentData) ?: return
    if (transparencySettings.leftEQ.size != 8 || transparencySettings.rightEQ.size != 8 ||
        (transparencySettings.ownVoiceAmplification != null && currentData.size < 104)
    ) return
    val buffer = ByteBuffer.wrap(currentData.copyOf()).order(ByteOrder.LITTLE_ENDIAN)

    if (transparencySettings.enabled != original.enabled) {
        buffer.putFloat(0, if (transparencySettings.enabled) 1.0f else 0.0f)
    }
    buffer.position(4)

    for (eq in transparencySettings.leftEQ) {
        buffer.putFloat(eq)
    }
    buffer.putFloat(transparencySettings.leftAmplification)
    buffer.putFloat(transparencySettings.leftTone)
    if (transparencySettings.leftConversationBoost != original.leftConversationBoost) {
        buffer.putFloat(44, if (transparencySettings.leftConversationBoost) 1.0f else 0.0f)
    }
    buffer.position(48)
    buffer.putFloat(transparencySettings.leftAmbientNoiseReduction)

    for (eq in transparencySettings.rightEQ) {
        buffer.putFloat(eq)
    }
    buffer.putFloat(transparencySettings.rightAmplification)
    buffer.putFloat(transparencySettings.rightTone)
    if (transparencySettings.rightConversationBoost != original.rightConversationBoost) {
        buffer.putFloat(92, if (transparencySettings.rightConversationBoost) 1.0f else 0.0f)
    }
    buffer.position(96)
    buffer.putFloat(transparencySettings.rightAmbientNoiseReduction)

    if (transparencySettings.ownVoiceAmplification != null) {
        buffer.putFloat(transparencySettings.ownVoiceAmplification)
    }

    val data = buffer.array()
    if (parseTransparencySettingsResponse(data) != null) writer(ATTHandle.TRANSPARENCY, data)
}

fun disableCustomizedTransparency(currentData: ByteArray, writer: (ATTHandle, ByteArray) -> Unit) {
    val settings = parseTransparencySettingsResponse(currentData) ?: return
    if (settings.enabled) sendTransparencySettings(currentData, settings.copy(enabled = false), writer)
}
