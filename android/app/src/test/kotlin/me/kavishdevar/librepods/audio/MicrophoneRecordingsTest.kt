package me.kavishdevar.librepods.audio

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MicrophoneRecordingsTest {
    @Test fun retainsCurrentAndTwoNewestTestsEvenWhenCurrentHasOldTimestamp() {
        val directory = Files.createTempDirectory("microphone-retention-").toFile()
        try {
            val recordings = (0..7).map { index ->
                File(directory, "airpods-mic-$index.wav").apply {
                    writeText("recording $index")
                    assertTrue(setLastModified(1_000L * (index + 1)))
                }
            }
            val unrelated = File(directory, "other.wav").apply { writeText("keep") }
            val nested = File(directory, "airpods-mic-folder.wav").apply { mkdir() }
            pruneMicrophoneRecordings(directory, recordings.first())
            assertEquals(setOf(recordings[0], recordings[6], recordings[7]), recordings.filter { it.exists() }.toSet())
            assertEquals("recording 0", recordings.first().readText())
            assertTrue(unrelated.exists())
            assertTrue(nested.isDirectory)
            pruneMicrophoneRecordings(directory, recordings.first())
            assertEquals(3, recordings.count { it.exists() })
        } finally { directory.deleteRecursively() }
    }

    @Test fun repeatedSessionsKeepStorageBoundedAndTheCurrentFileWritable() {
        val directory = Files.createTempDirectory("microphone-sessions-").toFile()
        try {
            repeat(12) { index ->
                val current = File.createTempFile("airpods-mic-", ".wav", directory)
                pruneMicrophoneRecordings(directory, current)
                current.appendText("session $index")
                assertEquals("session $index", current.readText())
                assertTrue(directory.listFiles()!!.size <= 3)
            }
        } finally { directory.deleteRecursively() }
    }
}
