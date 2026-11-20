package me.kavishdevar.librepods.dongle

import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DonglePendingWritesTest {
    private var now = 1_000L
    private val writes = DonglePendingWrites { now }
    private val listeningMode = ControlCommandIdentifier.LISTENING_MODE.settingId
    private val conversationAwareness = ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG.settingId

    @Test fun aReportedSettingWaitsTenSecondsBeforeItIsUnconfirmed() {
        writes.recordSent(listeningMode, 2, DongleProtocol.Setting(3, 0, 7), stored = true)
        assertEquals(mapOf(listeningMode to 2L), writes.requestedValues())
        assertTrue(writes.awaitingConfirmation())
        now += 9_999
        assertTrue(writes.unconfirmed().isEmpty())
        now += 1
        assertEquals(setOf(listeningMode), writes.unconfirmed())
        assertFalse(writes.awaitingConfirmation())
        assertEquals(mapOf(listeningMode to 2L), writes.requestedValues())
    }

    @Test fun onlyANewerReportRevisionAnswersAWrite() {
        writes.recordSent(listeningMode, 2, DongleProtocol.Setting(3, 0, 7), stored = true)
        writes.dropAnswered(mapOf(listeningMode to DongleProtocol.Setting(3, 0, 7)))
        assertEquals(mapOf(listeningMode to 2L), writes.requestedValues())
        writes.dropAnswered(mapOf(listeningMode to DongleProtocol.Setting(1, 0, 8)))
        assertTrue(writes.requestedValues().isEmpty())
    }

    @Test fun conversationAwarenessAndUnsavedChoicesNeverExpire() {
        writes.recordSent(conversationAwareness, 1, DongleProtocol.Setting(2, 0, 4), stored = true)
        writes.recordSent(ControlCommandIdentifier.MIC_MODE.settingId, 1, baseline = null, stored = false)
        now += 60_000
        assertTrue(writes.unconfirmed().isEmpty())
        assertFalse(writes.awaitingConfirmation())
        assertEquals(setOf(conversationAwareness, ControlCommandIdentifier.MIC_MODE.settingId), writes.requestedValues().keys)
    }

    @Test fun aSavedChoiceWithoutAReportReplacesNoIntent() {
        val micMode = ControlCommandIdentifier.MIC_MODE.settingId
        writes.recordSent(micMode, 1, baseline = null, stored = false)
        writes.recordSent(micMode, 2, baseline = null, stored = true)
        assertTrue(writes.requestedValues().isEmpty())
        writes.recordSent(listeningMode, 2, DongleProtocol.Setting(3, 0, 7), stored = true)
        writes.clear()
        assertTrue(writes.requestedValues().isEmpty())
        assertFalse(writes.awaitingConfirmation())
    }
}
