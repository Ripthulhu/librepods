package me.kavishdevar.librepods.presentation.overlays

import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.core.view.doOnPreDraw

internal fun View.logFirstOverlayFrame(kind: String, observedAtNanos: Long) {
    doOnPreDraw {
        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - observedAtNanos) / 1_000_000
        Log.d("OverlayTiming", "Scan to $kind first frame: ${elapsedMs}ms")
    }
}
