package me.kavishdevar.librepods.audio

import me.kavishdevar.librepods.shizuku.A2dpRecoveryGuard

/** A reconnect to the same MAC is a different transport and cannot inherit a pending call route. */
internal class SelectedCallTransport<T : Any>(
    private val request: Long,
    val socket: T,
    address: String,
) {
    val address = A2dpRecoveryGuard(address).address

    fun isCurrent(generation: AudioSessionGeneration, currentSocket: T?, connected: Boolean): Boolean =
        generation.accepts(request) && socket === currentSocket && connected
}
