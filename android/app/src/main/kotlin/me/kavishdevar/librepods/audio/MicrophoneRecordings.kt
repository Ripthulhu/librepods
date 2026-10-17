/* Copyright (C) 2026 LibrePods contributors; GPL-3.0-or-later. */
package me.kavishdevar.librepods.audio

import java.io.File
import java.io.IOException

internal fun pruneMicrophoneRecordings(directory: File, current: File) {
    val recordings = directory.listFiles { file ->
        file.isFile && file.name.startsWith("airpods-mic-") && file.extension == "wav"
    } ?: throw IOException("Cannot list microphone recordings")
    recordings.filter { it != current }
        .sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name })
        .drop(2)
        .forEach { file ->
            if (!file.delete() && file.exists()) throw IOException("Cannot remove an old microphone recording")
        }
}
