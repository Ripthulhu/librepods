/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/


package me.kavishdevar.librepods.presentation.overlays

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.StaticLayout
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.ble.NearbyAirPods
import me.kavishdevar.librepods.core.bluetooth.ble.ProximityReport
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.Battery
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.core.devices.BatteryStatus
import kotlin.math.min

/** A single invitation; Bluetooth work belongs to NearbyPairingController. */
@SuppressLint("InflateParams", "ClickableViewAccessibility")
class PopupWindow(private val context: Context, private val onCloseCallback: () -> Unit = {}) {
    enum class Stage { NEARBY, SETUP, CONNECTING, COMPLETE, RETRY }

    private val mView: View = LayoutInflater.from(ContextThemeWrapper(context, R.style.Theme_LibrePods_Pairing))
        .inflate(R.layout.popup_window, null)
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val button = mView.findViewById<Button>(R.id.pairing_primary)
    private val progress = mView.findViewById<View>(R.id.pairing_progress)
    private val artwork = PairingArtwork(mView.findViewById(R.id.popup_artwork_container))
    private val batteryRow = mView.findViewById<View>(R.id.pairing_battery_row)
    private var batteries = emptySet<Battery>()
    private var attached = false
    private var closing = false
    private var notified = false
    private var interacted = false
    private var animation: ValueAnimator? = null
    private var generation = 0L
    private var downY = 0f
    private var downScroll = 0
    private var expansion = 0f
    private var modelId = 0
    private var modelName = ""
    private var stage = Stage.NEARBY
    private var message = ""
    private var params: WindowManager.LayoutParams? = null
    private val expire = Runnable { close() }
    private val expireBattery = Runnable { if (!closing && stage == Stage.COMPLETE) { renderBattery(); resize() } }

    init {
        mView.clipToOutline = true
        mView.findViewById<ImageButton>(R.id.close_button).apply {
            imageTintList = ColorStateList.valueOf(Color.rgb(99, 99, 102))
            setOnClickListener { close() }
        }
        mView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_OUTSIDE -> { close(); true }
                MotionEvent.ACTION_DOWN -> { downY = event.rawY; downScroll = mView.scrollY; false }
                MotionEvent.ACTION_UP -> {
                    if (downScroll == 0 && event.rawY - downY > dp(72)) { close(); true } else false
                }
                else -> false
            }
        }
    }

    fun openPairing(name: String, modelId: Int, observedAtNanos: Long = SystemClock.elapsedRealtimeNanos()) {
        if (attached || closing || notified) return
        if (!Settings.canDrawOverlays(context)) {
            handler.post { forceClose() }
            return
        }
        this.modelId = modelId
        modelName = name
        mView.findViewById<TextView>(R.id.name).text = name
        val metrics = windowManager.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val bounds = metrics.bounds
        val width = min(dp(400), bounds.width() - insets.left - insets.right - dp(32)).coerceAtLeast(1)
        val maxHeight = (bounds.height() - insets.top - insets.bottom - dp(32)).coerceAtLeast(1)
        mView.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
        val params = WindowManager.LayoutParams(width, min(mView.measuredHeight, maxHeight),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = dp(16)
                dimAmount = .28f
            }
        this.params = params
        try {
            mView.logFirstOverlayFrame("pairing", observedAtNanos)
            windowManager.addView(mView, params)
            attached = true
            artwork.start(modelId)
            animate(opening = true)
            handler.postDelayed(expire, 12_000)
        } catch (e: RuntimeException) {
            Log.w("PopupWindow", "Cannot show pairing invitation", e)
            // Post so the controller has stored this card before its dismissal callback.
            handler.post { forceClose() }
        }
    }

    fun showPairingActions(message: String, actions: List<Pair<String, () -> Unit>>, busy: Boolean = actions.isEmpty(), stage: Stage = Stage.NEARBY) {
        if (closing || notified) return
        // Discovery repeats the same instructions; it must not reset the presentation or layout.
        if (this.stage == stage && this.message == message && actions.isEmpty()) return
        if (this.stage != stage) batteries = emptySet()
        this.stage = stage
        this.message = message
        val heading = mView.findViewById<TextView>(R.id.name)
        heading.accessibilityLiveRegion = if (stage == Stage.COMPLETE) View.ACCESSIBILITY_LIVE_REGION_POLITE else View.ACCESSIBILITY_LIVE_REGION_NONE
        heading.contentDescription = if (stage == Stage.COMPLETE) "$modelName. $message" else null
        heading.text = when (stage) {
            Stage.SETUP -> context.getString(if (NearbyAirPods.usesDoubleTap(modelId))
                R.string.pairing_double_tap_title else R.string.pairing_hold_button_title)
            Stage.CONNECTING -> context.getString(R.string.pairing_connecting_title)
            else -> modelName
        }
        mView.findViewById<TextView>(R.id.pairing_message).text = message
        val action = actions.singleOrNull()
        button.text = action?.first ?: context.getString(R.string.pairing_connect)
        button.isEnabled = action != null && !busy
        button.importantForAccessibility = if (busy) View.IMPORTANT_FOR_ACCESSIBILITY_NO else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        button.visibility = if (busy || action == null) View.GONE else View.VISIBLE
        button.backgroundTintList = ColorStateList.valueOf(if (stage == Stage.COMPLETE) Color.rgb(242, 242, 247) else Color.WHITE)
        button.setTextColor(if (stage == Stage.COMPLETE) Color.rgb(28, 28, 30) else Color.rgb(0, 102, 214))
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        progress.contentDescription = context.getString(if (stage == Stage.CONNECTING)
            R.string.pairing_connecting_title else R.string.pairing_searching)
        button.setOnClickListener {
            if (!closing && action != null) {
                interacted = true
                handler.removeCallbacks(expire)
                action.second()
            }
        }
        if (interacted || busy || stage == Stage.COMPLETE) handler.removeCallbacks(expire)
        renderBattery()
        resize()
    }

    fun updatePairingBattery(batteries: Set<Battery>) {
        if (closing || notified || stage != Stage.COMPLETE) return
        this.batteries = batteries.toSet()
        renderBattery()
        resize()
    }

    private fun renderBattery() {
        handler.removeCallbacks(expireBattery)
        batteryRow.visibility = if (stage == Stage.COMPLETE) View.VISIBLE else View.GONE
        mView.findViewById<View>(R.id.pairing_message).visibility = if (stage == Stage.COMPLETE) View.GONE else View.VISIBLE
        if (stage != Stage.COMPLETE) return
        val now = System.currentTimeMillis()
        fun live(component: BatteryComponent) = batteries.singleOrNull { it.component == component }
            ?.takeIf { it.isLive(now) && it.level in 0..100 && it.status != BatteryStatus.UNKNOWN && it.status != BatteryStatus.DISCONNECTED }
        val headset = ProximityReport.model(modelId) in setOf(AirPodsModel.AIRPODS_MAX_LIGHTNING,
            AirPodsModel.AIRPODS_MAX_USBC, AirPodsModel.AIRPODS_MAX_2)
        val left = live(if (headset) BatteryComponent.HEADSET else BatteryComponent.LEFT)
        val right = live(BatteryComponent.RIGHT)
        val case = live(BatteryComponent.CASE)
        val split = !headset && (left != null || right != null) && (left?.level != right?.level || charging(left) != charging(right))
        val budsText = mView.findViewById<TextView>(R.id.pairing_buds_battery_text)
        val rightText = mView.findViewById<TextView>(R.id.pairing_right_battery_text)
        val caseText = mView.findViewById<TextView>(R.id.pairing_case_battery_text)
        mView.findViewById<TextView>(R.id.pairing_buds_battery_label).setText(
            if (headset) R.string.island_battery_headset else R.string.pairing_battery_buds)
        mView.findViewById<View>(R.id.pairing_case_battery_column).visibility = if (headset) View.GONE else View.VISIBLE
        budsText.textSize = if (split) 14f else 18f
        val content = mView.findViewById<View>(R.id.linear_layout)
        val columnWidth = (((params?.width ?: mView.measuredWidth) - content.paddingLeft - content.paddingRight) *
            if (headset) 1f else .45f).toInt()
        val textWidth = (columnWidth - dp(8 + 24 + 6) - rightText.paddingLeft - rightText.paddingRight).coerceAtLeast(1)
        val lineHeight = maxOf(dp(24), listOf(R.string.island_battery_left, R.string.island_battery_right).maxOf { label ->
            val widest = context.getString(R.string.pairing_battery_component_value, context.getString(label), "100%")
            StaticLayout.Builder.obtain(widest, 0, widest.length, rightText.paint, textWidth).setIncludePad(false)
                .setBreakStrategy(rightText.breakStrategy).setHyphenationFrequency(rightText.hyphenationFrequency)
                .setLineSpacing(rightText.lineSpacingExtra, rightText.lineSpacingMultiplier).build().height
        })
        budsText.minHeight = if (split) lineHeight else 2 * lineHeight + dp(4)
        rightText.minHeight = lineHeight
        caseText.minHeight = 2 * lineHeight + dp(4)
        batteryText(budsText, left, if (split) R.string.island_battery_left
            else if (headset) R.string.island_battery_headset else R.string.pairing_battery_buds, split)
        rightText.visibility = if (split) View.VISIBLE else View.GONE
        if (split) batteryText(rightText, right, R.string.island_battery_right, true)
        batteryText(caseText, case, R.string.island_battery_case, false)
        if (!split && !headset) budsText.contentDescription = listOf(BatteryComponent.LEFT to left, BatteryComponent.RIGHT to right)
            .joinToString("; ") { (component, battery) -> batteryDescription(battery,
                context.getString(if (component == BatteryComponent.LEFT) R.string.island_battery_left else R.string.island_battery_right)) }
        listOfNotNull(left, if (headset) null else right, if (headset) null else case)
            .minOfOrNull { requireNotNull(it.observedAt) + 90_000 - now }
            ?.let { handler.postDelayed(expireBattery, it.coerceAtLeast(1)) }
    }

    private fun charging(battery: Battery?) = battery?.status in setOf(BatteryStatus.CHARGING, BatteryStatus.OPTIMIZED_CHARGING)

    private fun batteryDescription(battery: Battery?, label: String): String = "$label, " +
        (battery?.let { "${it.level}%" } ?: context.getString(R.string.island_battery_unavailable)) +
        if (charging(battery)) ", ${context.getString(R.string.island_battery_charging)}" else ""

    private fun batteryText(view: TextView, battery: Battery?, labelRes: Int, showLabel: Boolean) {
        val label = context.getString(labelRes)
        val percentage = battery?.let { "${it.level}%" } ?: context.getString(R.string.pairing_battery_unknown)
        view.text = if (showLabel) context.getString(R.string.pairing_battery_component_value, label, percentage) else percentage
        view.contentDescription = batteryDescription(battery, label)
        val track = context.getDrawable(R.drawable.island_battery_progress)!!.mutate().apply {
            setTint(Color.rgb(229, 229, 234)); level = 10_000
        }
        val fill = context.getDrawable(R.drawable.island_battery_progress)!!.mutate().apply {
            setTint(if (battery != null && battery.level <= 25) Color.rgb(255, 69, 58) else Color.rgb(53, 199, 89))
            level = (battery?.level ?: 0) * 100
        }
        val layers = mutableListOf(track, fill)
        if (charging(battery)) layers += context.getDrawable(R.drawable.ic_power)!!.mutate().apply { setTint(Color.rgb(28, 28, 30)) }
        val icon = LayerDrawable(layers.toTypedArray()).apply {
            if (numberOfLayers == 3) {
                setLayerSize(2, dp(8), dp(12))
                setLayerGravity(2, Gravity.CENTER)
            }
            setBounds(0, 0, dp(24), dp(24))
        }
        view.compoundDrawablePadding = dp(6)
        view.setCompoundDrawablesRelative(icon, null, null, null)
    }

    private fun resize() {
        val layout = params ?: return
        if (!attached) return
        val metrics = windowManager.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val maxHeight = (metrics.bounds.height() - insets.top - insets.bottom - dp(32)).coerceAtLeast(1)
        mView.measure(View.MeasureSpec.makeMeasureSpec(layout.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
        layout.height = min(mView.measuredHeight, maxHeight)
        runCatching { windowManager.updateViewLayout(mView, layout) }.onFailure { forceClose() }
    }

    fun close() {
        if (closing || notified) return
        closing = true
        handler.removeCallbacks(expire)
        handler.removeCallbacks(expireBattery)
        notifyDismissed()
        if (attached) animate(opening = false) else remove()
    }

    fun forceClose() {
        closing = true
        artwork.stop()
        handler.removeCallbacks(expire)
        cancelAnimation()
        notifyDismissed()
        remove()
    }

    private fun notifyDismissed() {
        if (!notified) { notified = true; onCloseCallback() }
    }

    private fun animate(opening: Boolean) {
        cancelAnimation()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            render(if (opening) 1f else 0f)
            if (!opening) remove()
            return
        }
        val token = generation
        val from = expansion
        val to = if (opening) 1f else 0f
        render(from)
        animation = ValueAnimator.ofFloat(from, to).apply {
            duration = ((if (opening) 420 else 220) * kotlin.math.abs(to - from)).toLong().coerceAtLeast(1)
            interpolator = if (opening) PathInterpolator(.16f, 1f, .3f, 1f) else PathInterpolator(.32f, 0f, .67f, 0f)
            addUpdateListener { render(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (token == generation && !opening) remove()
                }
            })
            start()
        }
    }

    private fun render(amount: Float) {
        expansion = amount
        mView.translationY = (mView.measuredHeight + dp(16)) * (1 - amount)
    }

    private fun cancelAnimation() {
        generation++
        animation?.cancel()
        animation = null
    }

    private fun remove() {
        handler.removeCallbacks(expireBattery)
        artwork.stop()
        cancelAnimation()
        if (attached) runCatching { windowManager.removeViewImmediate(mView) }
        attached = false
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
