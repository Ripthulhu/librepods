package me.kavishdevar.librepods.core.devices

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import me.kavishdevar.librepods.core.bluetooth.aacp.publishForTransport
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalSerializationApi::class)
class AllowOffTest {
    private val id = ControlCommandIdentifier.ALLOW_OFF_OPTION
    private val model = "A3063"

    @Test fun nullableChoiceRoundTripsWithSettingsAndOldMissingFieldsRemainUnknown() {
        val oldDefaults = Cbor.encodeToByteArray(AppleSettings())
        assertNull(Cbor.decodeFromByteArray<AppleSettings>(oldDefaults).allowOffChoice)
        for (choice in listOf(true, false)) {
            val saved = AppleSettings(allowOffChoice = choice, conversationAwarenessVolume = 17f)
            assertEquals(saved, Cbor.decodeFromByteArray<AppleSettings>(Cbor.encodeToByteArray(saved)))
        }
    }

    @Test fun silentRequestSurvivesReconnectAsPerPairPreferenceWithoutInventingAReport() {
        val before = AppleState().withControlReport(id, byteArrayOf(2))
        val previousReport = before.controlStates[id]
        val requested = before.withControlRequest(id, byteArrayOf(1), previousReport)
        val settings = AppleSettings().withAllowOffRequest(model, requested, byteArrayOf(1), previousReport)
        assertArrayEquals(byteArrayOf(2), requested.controlStates[id])
        assertEquals(true, requested.effectiveAllowOff(settings, model))
        val restored = Cbor.decodeFromByteArray<AppleSettings>(Cbor.encodeToByteArray(settings))
        val reconnected = AppleState()
        assertEquals(true, reconnected.effectiveAllowOff(restored, model))
        assertTrue(reconnected.controlStates.isEmpty())
        assertTrue(reconnected.requestedControls.isEmpty())
        assertNull("Another pair must not inherit the remembered choice",
            AppleState().effectiveAllowOff(AppleSettings(), model))
        for (supported in listOf("A3063", "A3064", "A3065"))
            assertEquals(true, reconnected.effectiveAllowOff(restored, supported))
        for (unsupported in listOf("", "A2084", "A2698", "A2096")) {
            assertNull(reconnected.effectiveAllowOff(restored, unsupported))
            assertNull(AppleSettings().withAllowOffRequest(unsupported, before, byteArrayOf(1), previousReport).allowOffChoice)
        }
    }

    @Test fun failedOrRetiredTransportCannotSaveTheChoice() {
        val monitor = Any()
        val captured = Any()
        var current: Any? = captured
        var settings = AppleSettings()
        val state = AppleState()
        fun complete(sent: Boolean) = sent && publishForTransport(monitor, captured, { current }) {
            settings = settings.withAllowOffRequest(model, state, byteArrayOf(1), null)
        }
        assertFalse(complete(false))
        assertNull(settings.allowOffChoice)
        synchronized(monitor) { current = Any() }
        assertFalse(complete(true))
        assertNull(settings.allowOffChoice)
        synchronized(monitor) { current = captured }
        assertTrue(complete(true))
        assertEquals(true, settings.allowOffChoice)
    }

    @Test fun freshContradictoryReportRetiresTheRequestAndRemainsAuthoritativeAfterReconnect() {
        val original = AppleState().withControlReport(id, byteArrayOf(2))
        val beforeWrite = original.controlStates[id]
        val pending = original.withControlRequest(id, byteArrayOf(1), beforeWrite)
        val settings = AppleSettings().withAllowOffRequest(model, pending, byteArrayOf(1), beforeWrite)
        val report = pending.withControlReport(id, byteArrayOf(2, 0, 0, 0))
        val confirmedSettings = settings.withAllowOffReport(model, byteArrayOf(2, 0, 0, 0))
        assertFalse(id in report.requestedControls)
        assertEquals(false, report.effectiveAllowOff(settings, model))
        assertEquals(false, AppleState().effectiveAllowOff(confirmedSettings, model))
        assertSame("A write completion cannot replace a fresh report, even when its value stayed the same",
            confirmedSettings, confirmedSettings.withAllowOffRequest(model, report, byteArrayOf(1), beforeWrite))
        assertSame(report, report.withControlRequest(id, byteArrayOf(1), beforeWrite))
    }

    @Test fun malformedValuesNeitherRetirePendingIntentNorBecomeRememberedChoices() {
        val pending = AppleState().withControlRequest(id, byteArrayOf(1), null)
        val settings = AppleSettings(allowOffChoice = false)
        for (invalid in listOf(byteArrayOf(), byteArrayOf(0), byteArrayOf(3), byteArrayOf(1, 1),
            byteArrayOf(2, 0, 0, 0, 0), byteArrayOf(0xff.toByte()))) {
            assertArrayEquals(byteArrayOf(1), pending.withControlReport(id, invalid).requestedControls[id])
            assertSame(settings, settings.withAllowOffReport(model, invalid))
            assertSame(settings, settings.withAllowOffRequest(model, pending, invalid, null))
        }
        val unknownModelReport = AppleState().withControlReport(id, byteArrayOf(2))
        assertEquals(false, unknownModelReport.effectiveAllowOff(AppleSettings(allowOffChoice = true), ""))
    }
}
