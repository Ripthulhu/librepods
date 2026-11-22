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
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.StaticLayout
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.descendants
import me.kavishdevar.librepods.LibrePodsApplication
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.bluetooth.ble.NearbyAirPods
import me.kavishdevar.librepods.core.bluetooth.ble.ProximityReport
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.Battery
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.core.devices.BatteryStatus
import me.kavishdevar.librepods.presentation.design.NightTheme
import kotlin.math.min

/** A single invitation; Bluetooth work belongs to NearbyPairingController. */
@SuppressLint("InflateParams", "ClickableViewAccessibility")
class PopupWindow(private val context: Context, private val onCloseCallback: () -> Unit = {}) {
    enum class Stage { NEARBY, SETUP, CONNECTING, COMPLETE, RETRY }

    private var themed = pairingContext(context)
    private val mView: View = LayoutInflater.from(themed).inflate(R.layout.popup_window, null)
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val button = mView.findViewById<Button>(R.id.pairing_primary)
    private val progress = mView.findViewById<ProgressBar>(R.id.pairing_progress)
    private val artwork = PairingArtwork(mView.findViewById(R.id.popup_artwork_container))
    private val batteryRow = mView.findViewById<View>(R.id.pairing_battery_row)
    private var batteries = emptySet<Battery>()
    private var attached = false
    private var closing = false
    private var notified = false
    private var interacted = false
    private var animation: ValueAnimator? = null
    private var contentAnimation: ValueAnimator? = null
    private val contentSnapshots = mutableListOf<BitmapDrawable>()
    private val contentSlots get() = listOf<View>(mView.findViewById(R.id.name),
        mView.findViewById(R.id.pairing_content), mView.findViewById(R.id.pairing_actions))
    private var presentation: List<Any?>? = null
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
            imageTintList = ColorStateList.valueOf(themed.getColor(R.color.pairing_close_icon))
            setOnClickListener {
                if (!closing && !notified) {
                    it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    close()
                }
            }
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
        retainSlotHeights()
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
            if (modelId !in NearbyAirPods.modelNames) handler.postDelayed(expire, 12_000)
        } catch (e: RuntimeException) {
            Log.w("PopupWindow", "Cannot show pairing invitation", e)
            // Post so the controller has stored this card before its dismissal callback.
            handler.post { forceClose() }
        }
    }

    fun showPairingActions(message: String, actions: List<Pair<String, () -> Unit>>, busy: Boolean = actions.isEmpty(), stage: Stage = Stage.NEARBY) {
        if (closing || notified) return
        // Discovery repeats the same instructions; it must not reset the presentation or layout.
        val nextPresentation = listOf(stage, message, actions.singleOrNull()?.first, busy)
        if (presentation == nextPresentation && actions.isEmpty()) return
        val snapshots = if (presentation != null && presentation != nextPresentation) captureContent() else emptyList()
        if (presentation != nextPresentation) {
            finishContentMotion()
        }
        presentation = nextPresentation
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
        applyActionColors()
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        progress.contentDescription = context.getString(if (stage == Stage.CONNECTING)
            R.string.pairing_connecting_title else R.string.pairing_searching)
        button.setOnClickListener {
            if (!closing && !notified && button.isEnabled && action != null) {
                it.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                interacted = true
                handler.removeCallbacks(expire)
                action.second()
            }
        }
        if (interacted || busy || stage == Stage.COMPLETE) handler.removeCallbacks(expire)
        renderBattery()
        resize()
        if (snapshots.isNotEmpty()) animateContent(snapshots)
    }

    /** Repaint the current invitation without replacing its session, action, battery data or deadlines. */
    fun refreshTheme() {
        if (closing || notified) return
        val next = pairingContext(context)
        val nightMask = Configuration.UI_MODE_NIGHT_MASK
        if ((next.resources.configuration.uiMode and nightMask) == (themed.resources.configuration.uiMode and nightMask)) return
        themed = next
        // A fading bitmap still contains the previous palette; the live content already has the current stage.
        finishContentMotion()
        reloadBackground(mView, R.drawable.popup_shape)
        val close = mView.findViewById<ImageButton>(R.id.close_button)
        reloadBackground(close, R.drawable.popup_button_shape)
        close.imageTintList = ColorStateList.valueOf(themed.getColor(R.color.pairing_close_icon))
        reloadBackground(button, R.drawable.pairing_primary_background)
        applyActionColors()
        val primary = themed.getColor(R.color.pairing_text_primary)
        val secondary = themed.getColor(R.color.pairing_text_secondary)
        mView.findViewById<TextView>(R.id.name).setTextColor(primary)
        mView.findViewById<TextView>(R.id.pairing_message).setTextColor(secondary)
        progress.indeterminateTintList = ColorStateList.valueOf(secondary)
        val batteryValues = setOf(R.id.pairing_buds_battery_text, R.id.pairing_right_battery_text, R.id.pairing_case_battery_text)
        for (label in (batteryRow as ViewGroup).descendants.filterIsInstance<TextView>()) {
            label.setTextColor(if (label.id in batteryValues) primary else secondary)
            // Recolour the existing icons in place, preserving their levels and expiry callbacks.
            (label.compoundDrawablesRelative[0] as? LayerDrawable)?.let { icon ->
                icon.getDrawable(0).setTint(themed.getColor(R.color.pairing_battery_track))
                if (icon.numberOfLayers == 3) icon.getDrawable(2).setTint(themed.getColor(R.color.pairing_battery_charging_icon))
            }
        }
        artwork.refreshTheme(themed.resources.configuration.uiMode and nightMask == Configuration.UI_MODE_NIGHT_YES)
    }

    private fun reloadBackground(view: View, resource: Int) {
        val padding = Rect(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
        view.background = ContextCompat.getDrawable(themed, resource)
        view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
    }

    private fun applyActionColors() {
        button.backgroundTintList = ColorStateList.valueOf(themed.getColor(
            if (stage == Stage.COMPLETE) R.color.pairing_action_complete else R.color.pairing_action))
        button.setTextColor(themed.getColor(
            if (stage == Stage.COMPLETE) R.color.pairing_text_primary else R.color.pairing_action_text))
    }

    fun updatePairingBattery(batteries: Set<Battery>) {
        if (closing || notified || stage != Stage.COMPLETE) return
        this.batteries = batteries.toSet()
        renderBattery()
        resize()
    }

    private fun renderBattery() {
        handler.removeCallbacks(expireBattery)
        batteryRow.visibility = if (stage == Stage.COMPLETE) View.VISIBLE else View.INVISIBLE
        batteryRow.importantForAccessibility = if (stage == Stage.COMPLETE) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        mView.findViewById<View>(R.id.pairing_message).apply {
            visibility = if (stage == Stage.COMPLETE) View.INVISIBLE else View.VISIBLE
            importantForAccessibility = if (stage == Stage.COMPLETE) View.IMPORTANT_FOR_ACCESSIBILITY_NO else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }
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
            setTint(themed.getColor(R.color.pairing_battery_track)); level = 10_000
        }
        val fill = context.getDrawable(R.drawable.island_battery_progress)!!.mutate().apply {
            setTint(if (battery != null && battery.level <= 25) Color.rgb(255, 69, 58) else Color.rgb(53, 199, 89))
            level = (battery?.level ?: 0) * 100
        }
        val layers = mutableListOf(track, fill)
        if (charging(battery)) layers += context.getDrawable(R.drawable.ic_power)!!.mutate().apply { setTint(themed.getColor(R.color.pairing_battery_charging_icon)) }
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
        retainSlotHeights()
        layout.height = min(mView.measuredHeight, maxHeight)
        runCatching { windowManager.updateViewLayout(mView, layout) }.onFailure { forceClose() }
    }

    private fun retainSlotHeights() {
        // Retain the initial action's scaled height even while the smaller busy indicator replaces it.
        for (slot in listOf(mView.findViewById<View>(R.id.pairing_content),
            mView.findViewById<View>(R.id.name).parent as View, mView.findViewById<View>(R.id.pairing_actions)))
            slot.minimumHeight = maxOf(slot.minimumHeight, slot.measuredHeight)
    }

    private fun captureContent(): List<BitmapDrawable> {
        if (!attached || expansion < 1f || !ValueAnimator.areAnimatorsEnabled()) return emptyList()
        val root = mView as ViewGroup
        val snapshots = mutableListOf<BitmapDrawable>()
        try {
            for (slot in contentSlots) {
                if (slot.width <= 0 || slot.height <= 0) continue
                val bounds = Rect().also { slot.getDrawingRect(it); root.offsetDescendantRectToMyCoords(slot, it) }
                val bitmap = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
                val drawable = BitmapDrawable(context.resources, bitmap).apply { this.bounds = bounds }
                snapshots += drawable
                // Capture the current composite so an interrupted fade starts from what is visible.
                root.draw(Canvas(bitmap).apply { translate(-bounds.left.toFloat(), -bounds.top.toFloat()) })
            }
        } catch (error: RuntimeException) {
            return emptyList()
        }
        return snapshots
    }

    private fun animateContent(snapshots: List<BitmapDrawable>) {
        if (closing || notified || !attached || !ValueAnimator.areAnimatorsEnabled()) {
            return
        }
        contentSnapshots += snapshots
        snapshots.forEach { mView.overlay.add(it) }
        contentSlots.forEach { it.alpha = 0f }
        contentAnimation = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 350
            addUpdateListener {
                val amount = it.animatedValue as Float
                contentSlots.forEach { slot -> slot.alpha = amount }
                snapshots.forEach { snapshot -> snapshot.alpha = ((1f - amount) * 255).toInt() }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { finishContentMotion() }
            })
            start()
        }
    }

    private fun finishContentMotion() {
        val previous = contentAnimation
        contentAnimation = null
        previous?.removeAllListeners()
        previous?.cancel()
        contentSlots.forEach { it.alpha = 1f }
        contentSnapshots.forEach { mView.overlay.remove(it) }
        contentSnapshots.clear()
    }

    fun close() {
        if (closing || notified) return
        closing = true
        finishContentMotion()
        artwork.pause()
        handler.removeCallbacks(expire)
        handler.removeCallbacks(expireBattery)
        notifyDismissed()
        if (attached) animate(opening = false) else remove()
    }

    fun forceClose() {
        closing = true
        finishContentMotion()
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
        artwork.setEntranceComplete(amount >= 1f && !closing)
    }

    private fun cancelAnimation() {
        generation++
        animation?.cancel()
        animation = null
    }

    private fun remove() {
        finishContentMotion()
        handler.removeCallbacks(expireBattery)
        artwork.stop()
        cancelAnimation()
        if (attached) runCatching { windowManager.removeViewImmediate(mView) }
        attached = false
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    companion object {
        /** Follows the app's Light/Dark/System choice, like the island, and resolves -night colors from it. */
        internal fun pairingContext(context: Context): Context {
            val base = context.resources.configuration.uiMode
            val night = when ((context.applicationContext as? LibrePodsApplication)?.appDataRepository?.settings?.value?.nightMode) {
                NightTheme.Dark -> Configuration.UI_MODE_NIGHT_YES
                NightTheme.Light -> Configuration.UI_MODE_NIGHT_NO
                else -> base and Configuration.UI_MODE_NIGHT_MASK
            }
            return ContextThemeWrapper(context, R.style.Theme_LibrePods_Pairing).apply {
                applyOverrideConfiguration(Configuration().apply {
                    uiMode = (base and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
                })
            }
        }
    }
}
