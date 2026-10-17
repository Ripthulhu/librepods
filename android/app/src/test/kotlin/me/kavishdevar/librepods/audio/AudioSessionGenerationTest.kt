package me.kavishdevar.librepods.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioSessionGenerationTest {
    @Test fun lateStartCannotSurviveStopOrReplaceTheNextSession() {
        val sessions = AudioSessionGeneration()
        val old = sessions.next()
        sessions.invalidate()
        assertFalse(sessions.accepts(old))
        val current = sessions.next()
        assertTrue(sessions.accepts(current))
        assertFalse(sessions.accepts(old))
    }

    @Test fun closePermanentlyInvalidatesPendingStarts() {
        val sessions = AudioSessionGeneration()
        val opening = sessions.next()
        sessions.close()
        sessions.invalidate()
        assertFalse(sessions.accepts(opening))
        assertThrows(IllegalStateException::class.java) { sessions.next() }
    }
}
