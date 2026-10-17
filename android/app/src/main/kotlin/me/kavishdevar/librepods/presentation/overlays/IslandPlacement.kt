/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import android.graphics.Insets
import android.graphics.Rect
import android.graphics.RectF
import me.kavishdevar.librepods.data.app.IslandSettings
import kotlin.math.roundToInt

internal data class IslandPlacement(val expandedWidth: Int, val expandedHeight: Int, val compactWidth: Int,
    val compactHeight: Int, val x: Int, val y: Int, val contentTop: Int)

internal fun islandPlacement(bounds: Rect, insets: Insets, camera: RectF?, density: Float, settings: IslandSettings): IslandPlacement {
    fun dp(value: Int) = (value * density).roundToInt()
    val defaults = IslandSettings()
    val safeWidth = (bounds.width() - insets.left - insets.right - dp(32)).coerceAtLeast(1)
    val maximumWidth = minOf(dp(420), safeWidth)
    val width = (if (settings.expandedWidth == defaults.expandedWidth) dp(420) else settings.expandedWidth.roundToInt())
        .coerceIn(minOf(dp(280), maximumWidth), maximumWidth)
    val y = if (settings.offsetY != defaults.offsetY) settings.offsetY.roundToInt().coerceAtLeast(0)
        else camera?.let { (it.top - dp(6)).roundToInt().coerceAtLeast(dp(6)) } ?: (insets.top + dp(12))
    val contentTop = camera?.takeIf { y < it.bottom }?.let { (it.bottom - y + dp(12)).roundToInt() } ?: dp(16)
    val minimumHeight = dp(160) + contentTop + if (settings.showListeningModeControl) dp(80) else 0
    val height = maxOf(settings.expandedHeight.roundToInt(), minimumHeight)
        .coerceAtMost((bounds.height() - y - insets.bottom - dp(16)).coerceAtLeast(1))
    val compactHeight = maxOf(settings.compactHeight.roundToInt(), dp(34),
        camera?.let { (it.height() + dp(12)).roundToInt() } ?: 0)
    val compactWidth = maxOf(settings.compactWidth.roundToInt(), dp(96),
        camera?.let { (it.width() + dp(12)).roundToInt() } ?: 0).coerceAtMost(safeWidth)
    val xLimit = ((safeWidth - maxOf(width, compactWidth)) / 2).coerceAtLeast(0)
    return IslandPlacement(width, height, compactWidth, compactHeight,
        settings.offsetX.roundToInt().coerceIn(-xLimit, xLimit), y, contentTop)
}
