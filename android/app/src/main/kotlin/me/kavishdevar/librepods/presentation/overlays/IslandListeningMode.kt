/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

/** Only confirmed reports, after this transport's first valid baseline, announce a mode change. */
internal class IslandListeningMode {
    private var previous: Int? = null
    private var owner: Any? = null

    fun observe(mode: Int): Boolean {
        if (mode !in 1..4) return false
        val changed = previous != null && previous != mode
        previous = mode
        return changed
    }

    fun observeReport(transport: Any?, previousReport: ByteArray?, report: ByteArray?,
                      currentReport: ByteArray? = report): Int? {
        if (owner !== transport) { reset(); owner = transport }
        // Cached values and requested-only state changes do not constitute received reports.
        if (transport == null || report !== currentReport || report === previousReport) return null
        val mode = report?.singleOrNull()?.toInt()?.and(255)?.takeIf { it in 1..4 } ?: return null
        return mode.takeIf { observe(it) }
    }

    fun reset() { previous = null; owner = null }
}
