package me.kavishdevar.librepods.utils

import org.junit.Assert.*
import org.junit.Test

class ConversationVolumeTest {
    private val owner = Any()

    @Test fun configuredReducedVolumeUsesTheSameOriginalBaselineAndCannotExceedTheFirstStage() {
        val volume = ConversationVolume()
        volume.begin(12, 20, 50, true, 1, owner)
        var current = 12
        while (true) { current = volume.step(current, owner) ?: break }
        volume.begin(current, 20, 50, true, 2, owner, reducedPercent = 25)
        while (true) { current = volume.step(current, owner) ?: break }
        assertEquals(3, current)
        assertTrue(volume.end(current, owner))
        while (true) { current = volume.step(current, owner) ?: break }
        assertEquals(12, current)
    }

    @Test fun microphoneCaptureRejectsSpeechDuckingUntilCaptureEnds() {
        val volume = ConversationVolume()
        for (stage in 1..3) {
            assertFalse(volume.begin(12, 20, 25, false, stage, owner, captureActive = true))
            assertNull(volume.owner)
            assertNull(volume.step(12, owner))
        }
        assertTrue(volume.begin(12, 20, 25, false, 1, owner))
        assertEquals(11, volume.step(12, owner))
        // Starting capture restores an existing duck only while it still owns the last write.
        assertEquals(12, volume.cancel(11, owner))
        assertFalse(volume.begin(12, 20, 25, false, 1, owner, captureActive = true))
        assertFalse(volume.end(12, owner))
        assertTrue(volume.begin(12, 20, 25, false, 1, owner, captureActive = false))
    }

    @Test fun quietAudioIsNeverRaisedWhileSpeaking() {
        val volume = ConversationVolume()
        volume.begin(1, 20, 25, false, 1, owner)
        assertNull(volume.step(1, owner))
    }

    @Test fun manualAdjustmentStopsDuckingAndRestoration() {
        val volume = ConversationVolume()
        volume.begin(10, 20, 25, false, 1, owner)
        assertEquals(9, volume.step(10, owner))
        assertNull(volume.step(12, owner))
        volume.begin(12, 20, 25, false, 2, owner)
        assertNull(volume.step(12, owner))
        assertFalse(volume.end(12, owner))
    }

    @Test fun secondStageAndNormalRestoration() {
        val volume = ConversationVolume()
        volume.begin(10, 20, 50, true, 1, owner)
        volume.begin(10, 20, 50, true, 2, owner)
        var current = 10
        while (true) { current = volume.step(current, owner) ?: break }
        assertEquals(3, current)
        assertTrue(volume.end(current, owner))
        while (true) { current = volume.step(current, owner) ?: break }
        assertEquals(10, current)
        assertFalse(volume.end(current, owner))
    }

    @Test fun manualChangeDuringRestorationWins() {
        val volume = ConversationVolume()
        volume.begin(10, 20, 50, true, 1, owner)
        var current = 10
        while (true) { current = volume.step(current, owner) ?: break }
        assertTrue(volume.end(5, owner))
        assertEquals(6, volume.step(5, owner))
        assertNull(volume.step(2, owner))
    }

    @Test fun disconnectRestoresOnlyTheOwnedLastWriteAndRetiresEveryPendingTick() {
        val volume = ConversationVolume()
        volume.begin(10, 20, 25, false, 1, owner)
        assertEquals(9, volume.step(10, owner))
        assertEquals(10, volume.cancel(9, owner))
        assertFalse(volume.speaking)
        assertNull(volume.owner)
        assertNull(volume.step(9, owner))
        assertFalse(volume.end(9, owner))
        assertNull(volume.cancel(9, owner))
    }

    @Test fun manualVolumeChangePreventsDisconnectRestoration() {
        val volume = ConversationVolume()
        volume.begin(10, 20, 25, false, 1, owner)
        assertEquals(9, volume.step(10, owner))
        assertNull(volume.cancel(12, owner))
        assertNull(volume.owner)
        volume.begin(12, 20, 100, true, 1, owner)
        assertNull(volume.step(12, owner))
        assertTrue(volume.end(12, owner))
        assertNull(volume.step(12, owner))
    }

    @Test fun retiringAnOldConnectionCannotStopOrRestoreItsReplacement() {
        val volume = ConversationVolume()
        val replacement = Any()
        volume.begin(10, 20, 25, false, 1, owner)
        assertEquals(9, volume.step(10, owner))
        volume.begin(12, 20, 25, false, 1, replacement)
        assertEquals(11, volume.step(12, replacement))
        assertNull(volume.cancel(11, owner))
        assertFalse(volume.end(11, owner))
        assertNull(volume.step(11, owner))
        assertTrue(volume.speaking)
        assertEquals(12, volume.cancel(11, replacement))
    }

    @Test fun losingTheSelectedAudioRouteCancelsWithoutRestoringAnotherOutput() {
        val volume = ConversationVolume()
        volume.begin(10, 20, 25, false, 1, owner)
        assertEquals(9, volume.step(10, owner))
        assertNull(volume.cancel(9, owner, restoreAllowed = false))
        assertFalse(volume.speaking)
        assertNull(volume.owner)
        assertNull(volume.step(9, owner))
        val replacement = Any()
        volume.begin(4, 20, 100, true, 1, replacement)
        assertNull(volume.step(4, replacement))
        assertNull(volume.cancel(4, replacement))
    }

    @Test fun manualMuteImmediatelyBeforeTheEndIsNeverUndone() {
        val volume = ConversationVolume()
        volume.begin(10, 20, 25, false, 1, owner)
        assertEquals(9, volume.step(10, owner))
        assertFalse(volume.end(0, owner))
        assertNull(volume.step(0, owner))
        assertNull(volume.cancel(0, owner))
    }

    @Test fun disconnectDuringRestorationCannotLeaveAnotherRestoreTick() {
        val volume = ConversationVolume()
        volume.begin(10, 20, 50, true, 1, owner)
        var current = 10
        while (true) { current = volume.step(current, owner) ?: break }
        assertTrue(volume.end(current, owner))
        assertEquals(6, volume.step(current, owner))
        assertEquals(10, volume.cancel(6, owner))
        assertNull(volume.step(6, owner))
    }

    @Test fun anotherConversationDuringOwnedRestorationKeepsTheOriginalBaseline() {
        val volume = ConversationVolume()
        volume.begin(12, 20, 50, true, 1, owner)
        var current = 12
        while (true) { current = volume.step(current, owner) ?: break }
        assertTrue(volume.end(current, owner))
        current = checkNotNull(volume.step(current, owner))
        volume.begin(current, 20, 50, true, 1, owner)
        while (true) { current = volume.step(current, owner) ?: break }
        assertEquals(6, current)
        assertTrue(volume.end(current, owner))
        while (true) { current = volume.step(current, owner) ?: break }
        assertEquals(12, current)
    }

    @Test fun repeatedStagesNeverRaiseTheDuckedVolumeOrLoseTheOriginalBaseline() {
        val volume = ConversationVolume()
        volume.begin(12, 20, 50, false, 1, owner)
        var current = 12
        while (true) { current = volume.step(current, owner) ?: break }
        assertEquals(10, current)
        volume.begin(current, 20, 50, false, 2, owner)
        while (true) { current = volume.step(current, owner) ?: break }
        assertEquals(6, current)
        volume.begin(current, 20, 50, false, 1, owner)
        assertNull(volume.step(current, owner))
        volume.begin(current, 20, 50, false, 2, owner)
        assertNull(volume.step(current, owner))
        assertTrue(volume.end(current, owner))
        while (true) { current = volume.step(current, owner) ?: break }
        assertEquals(12, current)
    }

    @Test fun partialRecoveryRestoresOnlyTheConfiguredCapBeforeTheConversationEnds() {
        val volume = ConversationVolume()
        volume.begin(12, 20, 50, false, 1, owner)
        var current = 12
        while (true) { current = volume.step(current, owner) ?: break }
        assertEquals(10, current)
        volume.begin(current, 20, 50, false, 2, owner)
        while (true) { current = volume.step(current, owner) ?: break }
        assertEquals(6, current)
        assertTrue(volume.begin(current, 20, 50, false, 3, owner))
        while (true) { current = volume.step(current, owner) ?: break }
        assertEquals(10, current)
        assertTrue(volume.end(current, owner))
        while (true) { current = volume.step(current, owner) ?: break }
        assertEquals(12, current)
    }

    @Test fun partialRecoveryNeverExceedsAQuietOriginalVolume() {
        val volume = ConversationVolume()
        volume.begin(1, 20, 50, false, 1, owner)
        volume.begin(1, 20, 50, false, 2, owner)
        assertTrue(volume.begin(1, 20, 50, false, 3, owner))
        assertNull(volume.step(1, owner))
    }

    @Test fun orphanFollowupStagesCannotAcquireVolumeOwnership() {
        for (stage in listOf(2, 3, 0, 5, 255)) {
            val volume = ConversationVolume()
            assertFalse(volume.begin(12, 20, 25, false, stage, owner))
            assertNull(volume.owner)
            assertFalse(volume.speaking)
            assertNull(volume.step(12, owner))
            assertNull(volume.cancel(12, owner))
        }
    }

    @Test fun anotherTransportFollowupCannotRetireTheCurrentConversation() {
        val volume = ConversationVolume()
        volume.begin(12, 20, 50, true, 1, owner)
        assertEquals(11, volume.step(12, owner))
        for (stage in listOf(2, 3, 5)) {
            assertFalse(volume.begin(11, 20, 50, true, stage, Any()))
            assertTrue(volume.owns(owner))
        }
        assertEquals(10, volume.step(11, owner))
        assertEquals(12, volume.cancel(10, owner))
    }

    @Test fun lateFollowupStagesCannotInterruptRestorationOrRestartAnEndedConversation() {
        val volume = ConversationVolume()
        volume.begin(10, 20, 50, true, 1, owner)
        var current = 10
        while (true) { current = volume.step(current, owner) ?: break }
        assertTrue(volume.end(current, owner))
        assertFalse(volume.begin(current, 20, 50, true, 2, owner))
        current = checkNotNull(volume.step(current, owner))
        assertEquals(6, current)
        assertFalse(volume.begin(current, 20, 50, true, 3, owner))
        while (true) { current = volume.step(current, owner) ?: break }
        assertEquals(10, current)
        assertFalse(volume.begin(current, 20, 50, true, 3, owner))
        assertNull(volume.step(current, owner))
        assertFalse(volume.speaking)
    }

    @Test fun anObservedManualMuteRetiresRestorationEvenIfTheUserReturnsToTheDuckedLevel() {
        val volume = ConversationVolume()
        volume.begin(10, 20, 50, true, 1, owner)
        var current = 10
        while (true) { current = volume.step(current, owner) ?: break }
        assertEquals(5, current)
        assertNull(volume.step(0, owner))
        assertTrue(volume.begin(5, 20, 50, true, 3, owner))
        assertNull(volume.step(5, owner))
        assertFalse(volume.end(5, owner))
        assertNull(volume.cancel(5, owner))
    }
}
