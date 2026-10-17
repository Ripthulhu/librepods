package me.kavishdevar.librepods.core.bluetooth.att.types

import me.kavishdevar.librepods.core.bluetooth.att.ATTHandle
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HearingSettingsTest {
    private fun report(hearingAid: Boolean, ownVoice: Boolean = true): ByteArray {
        val data = ByteArray(if (!ownVoice) 100 else if (hearingAid) 104 else 108)
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        if (hearingAid) {
            buffer.put(byteArrayOf(2, 2, 0x64, 0))
        } else {
            buffer.putFloat(1f)
        }
        repeat(8) { buffer.putFloat(it + 1f) }
        buffer.putFloat(-0.25f).putFloat(-0.3f).putFloat(0f).putFloat(0.2f)
        repeat(8) { buffer.putFloat(it + 11f) }
        buffer.putFloat(0.75f).putFloat(0.8f).putFloat(1f).putFloat(0.9f)
        if (ownVoice) {
            buffer.putFloat(0.45f)
            if (!hearingAid) buffer.putInt(0x12345678)
        }
        return data
    }

    @Test fun transparencyRequiresTheWholeMandatoryReport() {
        val complete = report(false)
        for (length in 0 until 100) {
            assertNull("length=$length", parseTransparencySettingsResponse(complete.copyOf(length)))
        }
        assertNotNull(parseTransparencySettingsResponse(complete.copyOf(100)))
        for (length in 101..103) {
            assertNull("partial own voice length=$length", parseTransparencySettingsResponse(complete.copyOf(length)))
        }
        assertNotNull(parseTransparencySettingsResponse(complete.copyOf(104)))
    }

    @Test fun hearingAidRejectsAllTruncatedReports() {
        val complete = report(true)
        for (length in 0 until 104) {
            assertNull("length=$length", parseHearingAidSettingsResponse(complete.copyOf(length)))
        }
        assertNotNull(parseHearingAidSettingsResponse(complete.copyOf(104)))
    }

    @Test fun nonfiniteSettingsAreRejectedAtEveryKnownFloat() {
        for (hearingAid in listOf(false, true)) {
            for (offset in (if (hearingAid) 4 else 0) until 104 step 4) {
                for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
                    val malformed = report(hearingAid)
                    ByteBuffer.wrap(malformed).order(ByteOrder.LITTLE_ENDIAN).putFloat(offset, invalid)
                    if (hearingAid) assertNull(parseHearingAidSettingsResponse(malformed))
                    else assertNull(parseTransparencySettingsResponse(malformed))
                }
            }
        }
    }

    @Test fun transparencyEditPreservesOtherEarFieldsOptionalVoiceAndUnknownTail() {
        val data = report(false)
        val saved = data.copyOf()
        val parsed = parseTransparencySettingsResponse(data)!!
        var sent: ByteArray? = null
        sendTransparencySettings(data, parsed.copy(leftTone = -0.8f)) { handle, value ->
            assertEquals(ATTHandle.TRANSPARENCY, handle)
            sent = value
        }
        val expected = saved.copyOf()
        ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN).putFloat(40, -0.8f)
        assertArrayEquals(expected, sent)
        assertArrayEquals(saved, data)
        assertEquals(parsed.rightTone, parseTransparencySettingsResponse(sent!!)!!.rightTone, 0f)
    }

    @Test fun hearingAidBandEditPreservesIndependentRightEarAndDoesNotMutateTheReport() {
        val data = report(true)
        val saved = data.copyOf()
        val parsed = parseHearingAidSettingsResponse(data)!!
        val updated = parsed.copy(leftEQ = parsed.leftEQ.copyOf().also { it[3] = 8.5f })
        var sent: ByteArray? = null
        sendHearingAidSettings(data, updated) { handle, value ->
            assertEquals(ATTHandle.HEARING_AID, handle)
            sent = value
        }
        val expected = saved.copyOf()
        ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN).putFloat(16, 8.5f)
        assertArrayEquals(expected, sent)
        assertArrayEquals(saved, data)
        assertEquals(parsed.rightConversationBoost, parseHearingAidSettingsResponse(sent!!)!!.rightConversationBoost)
    }

    @Test fun legacyTransparencyWithoutOwnVoiceKeepsItsReportSize() {
        val data = report(false, ownVoice = false)
        val parsed = parseTransparencySettingsResponse(data)!!
        assertNull(parsed.ownVoiceAmplification)
        var sent: ByteArray? = null
        sendTransparencySettings(data, parsed.copy(leftConversationBoost = true)) { _, value -> sent = value }
        val expected = data.copyOf()
        ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN).putFloat(44, 1f)
        assertArrayEquals(expected, sent)
        assertEquals(100, sent!!.size)
    }

    @Test fun unrelatedEditsPreserveNoncanonicalReportedFlagValues() {
        for (hearingAid in listOf(false, true)) {
            val data = report(hearingAid)
            ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putFloat(44, 0.25f).putFloat(92, 0.75f)
            var sent: ByteArray? = null
            if (hearingAid) {
                val parsed = parseHearingAidSettingsResponse(data)!!
                sendHearingAidSettings(data, parsed.copy(leftTone = -0.8f)) { _, value -> sent = value }
            } else {
                val parsed = parseTransparencySettingsResponse(data)!!
                sendTransparencySettings(data, parsed.copy(leftTone = -0.8f)) { _, value -> sent = value }
            }
            val expected = data.copyOf()
            ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN).putFloat(40, -0.8f)
            assertArrayEquals(expected, sent)
        }
    }

    @Test fun amplificationReconstructionIsTheInverseOfReportParsing() {
        for (left in listOf(-1f, -0.25f, 0f, 0.75f, 1f)) {
            for (right in listOf(-1f, -0.25f, 0f, 0.75f, 1f)) {
                val transparency = report(false)
                ByteBuffer.wrap(transparency).order(ByteOrder.LITTLE_ENDIAN)
                    .putFloat(36, left).putFloat(84, right)
                val parsedTransparency = parseTransparencySettingsResponse(transparency)!!.withAmplification()
                assertEquals(left, parsedTransparency.leftAmplification, 0.000001f)
                assertEquals(right, parsedTransparency.rightAmplification, 0.000001f)

                val hearing = report(true)
                ByteBuffer.wrap(hearing).order(ByteOrder.LITTLE_ENDIAN)
                    .putFloat(36, left).putFloat(84, right)
                val parsedHearing = parseHearingAidSettingsResponse(hearing)!!.withAmplification()
                assertEquals(left, parsedHearing.leftAmplification, 0.000001f)
                assertEquals(right, parsedHearing.rightAmplification, 0.000001f)
            }
        }
    }

    @Test fun changingGainPreservesBalanceAndUnrelatedAsymmetricSettings() {
        val original = parseHearingAidSettingsResponse(report(true))!!
        val updated = original.withAmplification(amplification = 0.1f)
        assertEquals(original.balance, updated.rightAmplification - updated.leftAmplification, 0.000001f)
        assertEquals(0.1f, (updated.leftAmplification + updated.rightAmplification) / 2, 0.000001f)
        assertEquals(original.leftTone, updated.leftTone, 0f)
        assertEquals(original.rightTone, updated.rightTone, 0f)
        assertArrayEquals(original.rightEQ, updated.rightEQ, 0f)
        val balanced = updated.withAmplification(balance = -0.6f)
        assertEquals(-0.6f, balanced.rightAmplification - balanced.leftAmplification, 0.000001f)
        assertEquals(0.1f, (balanced.leftAmplification + balanced.rightAmplification) / 2, 0.000001f)
    }

    @Test fun disablingCustomizedTransparencyChangesOnlyItsEnableField() {
        val transparency = report(false)
        val original = transparency.copyOf()
        var sent: ByteArray? = null
        disableCustomizedTransparency(transparency) { handle, value ->
            assertEquals(ATTHandle.TRANSPARENCY, handle)
            sent = value
        }
        val expected = original.copyOf()
        ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN).putFloat(0, 0f)
        assertArrayEquals(expected, sent)
        assertArrayEquals(original, transparency)
        assertEquals(0.45f, parseTransparencySettingsResponse(sent!!)!!.ownVoiceAmplification!!, 0f)
    }

    @Test fun anAlreadyDisabledOrUnavailableTransparencyProfileNeedsNoWrite() {
        var writes = 0
        val writer: (ATTHandle, ByteArray) -> Unit = { _, _ -> writes++ }
        disableCustomizedTransparency(byteArrayOf(), writer)
        val disabled = report(false)
        ByteBuffer.wrap(disabled).order(ByteOrder.LITTLE_ENDIAN).putFloat(0, 0f)
        disableCustomizedTransparency(disabled, writer)
        assertEquals(0, writes)
    }

    @Test fun invalidEditsNeverReachTheWriter() {
        var writes = 0
        val writer: (ATTHandle, ByteArray) -> Unit = { _, _ -> writes++ }
        val transparency = report(false)
        val parsedTransparency = parseTransparencySettingsResponse(transparency)!!
        sendTransparencySettings(transparency, parsedTransparency.copy(leftTone = Float.NaN), writer)
        sendTransparencySettings(transparency, parsedTransparency.copy(leftEQ = FloatArray(7)), writer)
        val hearing = report(true)
        val parsedHearing = parseHearingAidSettingsResponse(hearing)!!
        sendHearingAidSettings(hearing, parsedHearing.copy(ownVoiceAmplification = Float.POSITIVE_INFINITY), writer)
        sendHearingAidSettings(hearing, parsedHearing.copy(rightEQ = FloatArray(9)), writer)
        assertEquals(0, writes)
    }

    @Test fun capturedHearingHeaderIsReadableButCannotBeChangedIntoAnEditableFormat() {
        val data = report(true).also {
            byteArrayOf(2, 0, 0x60, 0).copyInto(it)
        }
        val original = data.copyOf()
        val settings = parseHearingAidSettingsResponse(data)!!
        assertFalse(hearingAidSettingsWritable(data))
        var writes = 0
        sendHearingAidSettings(data, settings.copy(leftTone = 0.2f)) { _, _ -> writes++ }
        assertEquals(0, writes)
        assertArrayEquals(original, data)
    }

    @Test fun supportedHearingHeadersArePreservedAndUnknownFormatsNeverWrite() {
        for (format in listOf(0, 0x64)) {
            val data = report(true).also { it[2] = format.toByte() }
            assertTrue(hearingAidSettingsWritable(data))
            var sent: ByteArray? = null
            sendHearingAidSettings(data, parseHearingAidSettingsResponse(data)!!.copy(leftTone = 0.2f)) { _, value -> sent = value }
            assertArrayEquals(data.copyOfRange(0, 4), sent!!.copyOfRange(0, 4))
        }
        for (data in listOf(report(true).copyOf(108), report(true).also { it[2] = 0x61 })) {
            assertFalse(hearingAidSettingsWritable(data))
            var writes = 0
            sendHearingAidSettings(data, parseHearingAidSettingsResponse(report(true))!!) { _, _ -> writes++ }
            assertEquals(0, writes)
        }
    }
}
