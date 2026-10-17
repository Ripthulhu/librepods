package me.kavishdevar.librepods.audio

/** Invalidates an asynchronous start before Stop/Close returns. */
internal class AudioSessionGeneration {
    private var generation = 0L
    private var closed = false

    @Synchronized fun next(): Long {
        check(!closed) { "Audio controller is closed" }
        return ++generation
    }
    @Synchronized fun invalidate() { generation++ }
    @Synchronized fun accepts(request: Long): Boolean = !closed && request == generation
    @Synchronized fun close() { closed = true; generation++ }
}
