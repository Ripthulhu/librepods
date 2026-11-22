package me.kavishdevar.librepods.dongle

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The session, connection and control session an edit was started against. */
internal data class DongleEditorOwner(val client: DongleSession, val generation: Long,
    val address: List<Int>, val controlSession: Long)

/** Multi-step record edits that may keep the controller busy while they run. */
internal enum class DongleRecordKind { HEARING, TRANSPARENCY, ACCOMMODATIONS }

/**
 * Who may edit through the dongle right now, and the fenced execution of an owned edit.
 *
 * An owner is the current session on a ready, connected pair. Every state change an edit makes is
 * published through [publish], which only runs while that session is still the controller's.
 * The guards are the controller's: [DongleLifecycle] for publication and [io] for transport use.
 */
internal class DongleEditingLease(
    private val lifecycle: DongleLifecycle,
    private val io: Mutex,
    private val state: MutableStateFlow<DongleState>,
    private val session: () -> DongleSession?,
    private val callActive: () -> Boolean,
    private val volumeCheckpoint: (DongleSession) -> (suspend () -> Long?),
    private val requestRefresh: () -> Unit,
) {
    /** The record edit currently holding the controller busy, if any. */
    @Volatile var liveOperation: Pair<DongleEditorOwner, DongleRecordKind>? = null
        private set

    /** Work that must not overlap USB volume authority: record edits and automatic health reads. */
    private val protectedWork = AtomicInteger()

    fun protectedWorkIdle(): Boolean = protectedWork.get() == 0

    fun forgetLiveOperation() {
        liveOperation = null
    }

    /** Runs [block] under the lifecycle lock only while [client] is still the controller's session. */
    fun <T> publish(client: DongleSession, block: () -> T): T = lifecycle.publish(client.controlEpoch) {
        if (session() !== client) throw CancellationException("Dongle session changed")
        block()
    }

    fun currentOwner(): DongleEditorOwner? = runCatching {
        lifecycle.publish(lifecycle.current()) {
            val current = state.value
            val client = session() ?: return@publish null
            val status = current.status ?: return@publish null
            if (!current.connected || !status.controlsReady || client.controlEpoch != lifecycle.current()) null
            else DongleEditorOwner(client, status.generation, status.address.toList(), current.controlSession)
        }
    }.getOrNull()

    /** The current owner while neither the microphone nor a call is active. */
    fun ownerAvailableForEdits(): DongleEditorOwner? =
        currentOwner()?.takeIf { state.value.status?.microphoneActive == false && !callActive() }

    fun owns(owner: DongleEditorOwner): Boolean = currentOwner() == owner

    fun writable(owner: DongleEditorOwner?, kind: DongleRecordKind? = null): Boolean {
        val value = state.value
        // A record edit may continue while it is the operation keeping the controller busy.
        return owner != null && owns(owner) && value.status?.microphoneActive == false && !callActive() &&
            (kind == null || !value.busy || liveOperation == (owner to kind))
    }

    suspend fun <T> withVolumeCheckpoints(client: DongleSession, block: suspend () -> T): T =
        client.withRpcCheckpoints(volumeCheckpoint(client), block)

    suspend fun <T> withProtectedWork(block: suspend () -> T): T {
        protectedWork.incrementAndGet()
        try {
            return block()
        } finally {
            protectedWork.decrementAndGet()
            requestRefresh()
        }
    }

    /** Runs [block] on [owner]'s session with the controller marked busy; fails if ownership changed. */
    suspend fun <T> ownedCommand(owner: DongleEditorOwner, kind: DongleRecordKind? = null, bindTarget: Boolean = true,
        block: suspend (DongleSession) -> T): T {
        if (kind == null) return runOwned(owner, null, bindTarget, block)
        return withProtectedWork { runOwned(owner, kind, bindTarget, block) }
    }

    private suspend fun <T> runOwned(owner: DongleEditorOwner, kind: DongleRecordKind?, bindTarget: Boolean,
        block: suspend (DongleSession) -> T): T = io.withLock {
        check(owns(owner)) { "AirPods connection changed" }
        val target = checkNotNull(state.value.status)
        val client = owner.client
        publish(client) {
            check(writable(owner))
            liveOperation = kind?.let { owner to it }
            state.update { it.copy(busy = true, error = null) }
        }
        try {
            if (bindTarget) client.withTarget(target) { withVolumeCheckpoints(client) { block(client) } }
            else withVolumeCheckpoints(client) { block(client) }
        } finally {
            runCatching {
                publish(client) {
                    liveOperation = null
                    state.update { it.copy(busy = false) }
                }
            }
            requestRefresh()
        }
    }
}
