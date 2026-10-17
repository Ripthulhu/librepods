/* Copyright (C) 2026 LibrePods contributors; GPL-3.0-or-later. */
package me.kavishdevar.librepods.utils

/** The app may restore volume only while its own last write still owns it. */
class ConversationVolume {
    var owner: Any? = null
        private set
    var speaking = false
        private set
    private var original: Int? = null
    private var expected: Int? = null
    private var target: Int? = null
    private var overridden = false

    fun owns(owner: Any): Boolean = this.owner === owner

    fun begin(current: Int, maximum: Int, percent: Int, relative: Boolean, stage: Int, owner: Any,
        captureActive: Boolean = false, reducedPercent: Int? = null): Boolean {
        if (captureActive || stage !in 1..3 || (stage != 1 && (!owns(owner) || !speaking))) return false
        if (!owns(owner)) { clear(); this.owner = owner }
        if (!speaking) {
            if (original == null || expected != current) original = current
            expected = current
            overridden = false
        }
        speaking = true
        if (overridden) return true
        val cap = (if (relative) checkNotNull(original) else maximum) * percent.coerceIn(0, 100) / 100
        // Stage 3 partially recovers stage 2, without exceeding the conversation's original level.
        val reduced = reducedPercent?.let {
            (if (relative) checkNotNull(original) else maximum) * it.coerceIn(0, percent.coerceIn(0, 100)) / 100
        } ?: (cap * 60 / 100)
        target = minOf(if (stage == 3) checkNotNull(original) else current,
            if (stage == 2) reduced else cap).coerceIn(0, maximum)
        return true
    }

    fun end(current: Int, owner: Any): Boolean {
        if (!owns(owner)) return false
        if (!speaking) return false
        speaking = false
        if (overridden || original == null || expected != current) {
            original = null
            target = null
            return false
        }
        target = original
        return true
    }

    fun step(current: Int, owner: Any): Int? {
        if (!owns(owner)) return null
        if (target == null || original == null) return null
        if (expected != current) {
            overridden = true
            original = null
            target = null
            return null
        }
        if (current == target) {
            if (!speaking) { original = null; target = null }
            return null
        }
        return (current + if (current < checkNotNull(target)) 1 else -1).also { expected = it }
    }

    /** Returns a restore only while this connection still owns its last volume write. */
    fun cancel(current: Int, owner: Any, restoreAllowed: Boolean = true): Int? {
        if (!owns(owner)) return null
        val restore = original?.takeIf { restoreAllowed && !overridden && expected == current && it != current }
        clear()
        return restore
    }

    private fun clear() {
        owner = null
        speaking = false
        original = null
        expected = null
        target = null
        overridden = false
    }
}
