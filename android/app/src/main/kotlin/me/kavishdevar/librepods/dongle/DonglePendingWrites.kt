package me.kavishdevar.librepods.dongle

import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier.ALLOW_OFF_OPTION
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier.CONVERSATION_DETECT_CONFIG

/**
 * Setting writes the AirPods have not answered with a newer report yet.
 *
 * Not thread-safe: the controller only uses it inside [DongleLifecycle] publication, which is the
 * guard the pending map always had.
 */
internal class DonglePendingWrites(private val clock: () -> Long) {
    private data class Pending(val value: Long, val revision: Long, val expiresAt: Long?)

    private val pending = mutableMapOf<Int, Pending>()

    /**
     * Remembers a write that was sent. [baseline] is the report the write was made against;
     * [stored] says whether the choice was saved locally (or did not need saving).
     */
    fun recordSent(id: Int, value: Long, baseline: DongleProtocol.Setting?, stored: Boolean) {
        val neverExpires = id in settingsWithoutExpiry || !stored
        if (baseline == null && !neverExpires) {
            pending.remove(id)
            return
        }
        val expiresAt = if (neverExpires) null else clock() + EXPIRY_MILLISECONDS
        pending[id] = Pending(value, baseline?.revision ?: 0L, expiresAt)
    }

    /** Forgets every write whose setting now has a different report revision. */
    fun dropAnswered(settings: Map<Int, DongleProtocol.Setting>) {
        pending.entries.removeAll { (id, request) ->
            // A newer receipt replaces intent; it does not prove that this write caused it.
            settings[id]?.revision?.let { it != request.revision } == true
        }
    }

    /** The value each pending write asked for. */
    fun requestedValues(): Map<Int, Long> = pending.mapValues { entry -> entry.value.value }

    /** Settings whose write expired without a newer report. */
    fun unconfirmed(): Set<Int> = pending.filterValues { entry ->
        entry.expiresAt?.let { clock() >= it } == true
    }.keys

    /** True while some write may still be answered, so the controller keeps polling quickly. */
    fun awaitingConfirmation(): Boolean = pending.values.any { entry ->
        entry.expiresAt?.let { end -> clock() < end } == true
    }

    fun clear() {
        pending.clear()
    }

    companion object {
        const val EXPIRY_MILLISECONDS = 10_000L

        /** Writes to these settings stay requested until a newer report arrives; they never time out. */
        private val settingsWithoutExpiry = setOf(CONVERSATION_DETECT_CONFIG.settingId, ALLOW_OFF_OPTION.settingId)
    }
}
