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

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.ContextThemeWrapper
import android.view.View
import android.view.WindowManager
import android.view.WindowInsets
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import me.kavishdevar.librepods.database.app.AppSettingsEntity
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.activities.MainActivity
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.effectiveAllowOff
import me.kavishdevar.librepods.core.devices.Battery
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.presentation.design.NightTheme
import me.kavishdevar.librepods.services.LibrePodsAccessibilityService

enum class IslandType {
    CONNECTED,
    TAKING_OVER,
    MOVED_TO_REMOTE,
    MOVED_TO_OTHER_DEVICE,
}

enum class IslandState {
    COMPACT,
    EXPANDED,
    CLOSE
}

@Suppress("unused")
class IslandWindow(
    private var context: Context,
    private var appSettings: AppSettingsEntity
) {
    private var windowManager: WindowManager = context.getSystemService(WindowManager::class.java)
    private var composeView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var transitionGeneration by mutableLongStateOf(0L)
    private var retainedWindowWidth = 0

    private var autoCloseHandler: Handler? = null
    private var autoCloseRunnable: Runnable? = null

    private var currentAppSettings by mutableStateOf(appSettings)

    private var name by mutableStateOf("[Test] AirPods Pro")
    private var type by mutableStateOf(IslandType.CONNECTED)
    private var batteryLevel by mutableIntStateOf(70)
    private var reversed by mutableStateOf(false)
    private var otherDeviceName by mutableStateOf(null as String?)
    private var statusText by mutableStateOf(null as String?)
    private var ownerAddress: String? = null
    private val pairingOpenings = mutableMapOf<String, Long>()
    private var model by mutableStateOf(AirPodsModel.UNKNOWN)
    private var batteries by mutableStateOf(emptySet<Battery>())
    private var nearby by mutableStateOf(false)
    private var batteryNow by mutableStateOf(System.currentTimeMillis())
    private var effectiveSettings by mutableStateOf(appSettings.islandSettings)
    private var cameraPadding by mutableIntStateOf(0)
    private var cameraCapsuleWidth by mutableFloatStateOf(0f)
    private val ageHandler = Handler(Looper.getMainLooper())
    private val updateAge = object : Runnable {
        override fun run() {
            if (!isVisible || ownerAddress == null) return
            batteryNow = System.currentTimeMillis()
            ageHandler.postDelayed(this, 1000)
        }
    }

    private var showOffListeningMode by mutableStateOf(false)
    private var noiseControlModeValue by mutableIntStateOf(4)
    private var adaptiveStrength by mutableIntStateOf(30)
    private var requestedAdaptiveStrength by mutableStateOf<Int?>(null)

    private var onNoiseControlModeChanged: (Int) -> Unit = { noiseControlModeValue = it }
    private var onAdaptiveStrengthChanged: (Int) -> Unit = { requestedAdaptiveStrength = it }

    private var openApp: () -> Unit = {}

    var usingAccessibilityService by mutableStateOf(false)
        private set

    init {
        usingAccessibilityService = context is LibrePodsAccessibilityService
    }

    var islandState by mutableStateOf(IslandState.CLOSE)
        private set

    val isVisible: Boolean
        get() = islandState != IslandState.CLOSE && composeView?.parent != null && composeView?.visibility == View.VISIBLE

    fun updateContext(context: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { updateContext(context) }
            return
        }

        val restore = isVisible
        val state = islandState
        composeView?.let { if (it.parent != null) windowManager.removeView(it) }
        lifecycleOwner?.stop()
        composeView = null
        lifecycleOwner = null
        retainedWindowWidth = 0
        transitionGeneration++
        this.context = context
        this.usingAccessibilityService = context is LibrePodsAccessibilityService
        this.windowManager = context.getSystemService(WindowManager::class.java)

        if (!restore) return
        setupComposeView()

        if (composeView?.parent == null) {
            val params = createLayoutParams(state)
            try {
                lifecycleOwner?.start()
                windowManager.addView(composeView, params)
                composeView?.visibility = View.VISIBLE
            } catch (e: Exception) {
                Log.e("IslandWindow", "Error adding overlay view: $e")
            }
        }
    }

    fun updateSettings(appSettings: AppSettingsEntity) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { updateSettings(appSettings) }
            return
        }

        this.appSettings = appSettings
        this.currentAppSettings = appSettings

        beginTransition(islandState, force = true)
    }

    fun updateBattery(batteryPercentage: Int) {
        if (ownerAddress != null) return
        this.batteryLevel = batteryPercentage
    }

    fun updatePairedBattery(device: AppleDevice, model: AirPodsModel = device.metadata.value.model) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { updatePairedBattery(device, model) }
            return
        }
        if (!isVisible || ownerAddress != device.macAddress.value) return
        this.batteries = device.state.value.battery
        this.name = device.metadata.value.name
        if (model != AirPodsModel.UNKNOWN) this.model = model
        this.nearby = device.connectionState.value != ConnectionState.CONNECTED
        batteryNow = System.currentTimeMillis()
    }

    fun suppressPairingOpening(address: String, observedAtNanos: Long) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { suppressPairingOpening(address, observedAtNanos) }
            return
        }
        pairingOpenings[address] = maxOf(pairingOpenings[address] ?: Long.MIN_VALUE, observedAtNanos)
        if (ownerAddress == address && type == IslandType.CONNECTED && statusText == null) close()
    }

    fun observePairedCaseLid(address: String, open: Boolean, observedAtNanos: Long) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { observePairedCaseLid(address, open, observedAtNanos) }
            return
        }
        // Only a newer authenticated case close ends the opening covered by the pairing card.
        if (!open && observedAtNanos > (pairingOpenings[address] ?: return)) pairingOpenings.remove(address)
    }

    fun clearPairingOpening(address: String? = null) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { clearPairingOpening(address) }
            return
        }
        if (address == null) pairingOpenings.clear() else pairingOpenings.remove(address)
    }

    fun showPairedLid(device: AppleDevice, model: AirPodsModel = device.metadata.value.model,
                      observedAtNanos: Long = SystemClock.elapsedRealtimeNanos()) {
        val state = device.state.value
        show(name = device.metadata.value.name, owner = device, ownerModel = model, observedAtNanos = observedAtNanos,
            openApp = { context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
            showOffListeningMode = state.effectiveAllowOff(device.settings.value, device.metadata.value.modelNumber) == true,
            noiseControlModeValue = state.controlStates[ControlCommandIdentifier.LISTENING_MODE]?.firstOrNull()?.toInt() ?: 3,
            onNoiseControlModeChanged = { device.setControlCommand(ControlCommandIdentifier.LISTENING_MODE, it) },
            adaptiveStrength = state.controlStates[ControlCommandIdentifier.AUTO_ANC_STRENGTH]?.firstOrNull()?.toInt() ?: 50,
            requestedAdaptiveStrength = state.requestedControls[ControlCommandIdentifier.AUTO_ANC_STRENGTH]?.firstOrNull()?.toInt(),
            onAdaptiveStrengthChanged = { device.setControlCommand(ControlCommandIdentifier.AUTO_ANC_STRENGTH, it) })
    }

    fun updateAdaptiveStrength(device: AppleDevice) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { updateAdaptiveStrength(device) }
            return
        }
        if (!isVisible || ownerAddress != device.macAddress.value) return
        val state = device.state.value
        showOffListeningMode = state.effectiveAllowOff(device.settings.value, device.metadata.value.modelNumber) == true
        adaptiveStrength = state.controlStates[ControlCommandIdentifier.AUTO_ANC_STRENGTH]?.firstOrNull()?.toInt() ?: 50
        requestedAdaptiveStrength = state.requestedControls[ControlCommandIdentifier.AUTO_ANC_STRENGTH]?.firstOrNull()?.toInt()
    }

    fun updateNoiseControl(mode: Int, strength: Int, showOff: Boolean = showOffListeningMode) {
        this.noiseControlModeValue = mode
        this.adaptiveStrength = strength
        this.showOffListeningMode = showOff
    }

    fun changeState(state: IslandState) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { changeState(state) }
            return
        }

        beginTransition(state)
    }

    private fun beginTransition(state: IslandState, force: Boolean = false) {
        if (state == islandState && !force) return
        retainedWindowWidth = maxOf(retainedWindowWidth,
            (composeView?.layoutParams as? WindowManager.LayoutParams)?.width?.coerceAtLeast(0) ?: 0)
        transitionGeneration++
        islandState = state
        updateWindowParams(state)
    }

    private fun finishTransition(expected: ComposeView, generation: Long, state: IslandState) {
        if (composeView !== expected || transitionGeneration != generation || islandState != state) return
        if (state == IslandState.CLOSE) cleanupAndRemoveView(expected)
        else {
            retainedWindowWidth = 0
            updateWindowParams(state)
        }
    }

    fun show(
        name: String = this.name,
        batteryLevel: Int = this.batteryLevel,
        type: IslandType = IslandType.CONNECTED,
        reversed: Boolean = this.reversed,
        otherDeviceName: String? = this.otherDeviceName,
        onReverseAction: () -> Unit = {},

        openApp: () -> Unit = this.openApp,

        showOffListeningMode: Boolean = this.showOffListeningMode,
        noiseControlModeValue: Int = this.noiseControlModeValue,
        onNoiseControlModeChanged: (Int) -> Unit = this.onNoiseControlModeChanged,
        adaptiveStrength: Int = this.adaptiveStrength,
        onAdaptiveStrengthChanged: (Int) -> Unit = this.onAdaptiveStrengthChanged,

        forcedState: IslandState? = null,
        keepOpen: Boolean = false,
        owner: AppleDevice? = null,
        ownerModel: AirPodsModel = owner?.metadata?.value?.model ?: AirPodsModel.UNKNOWN,
        observedAtNanos: Long = SystemClock.elapsedRealtimeNanos(),
        statusText: String? = null,
        requestedAdaptiveStrength: Int? = null
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post {
                show(name, batteryLevel, type, reversed, otherDeviceName, onReverseAction,
                    openApp, showOffListeningMode, noiseControlModeValue, onNoiseControlModeChanged,
                    adaptiveStrength, onAdaptiveStrengthChanged, forcedState, keepOpen, owner, ownerModel, observedAtNanos, statusText, requestedAdaptiveStrength)
            }
            return
        }

        if (owner != null && type == IslandType.CONNECTED && statusText == null &&
            pairingOpenings.containsKey(owner.macAddress.value)) return

        Log.d("IslandWindow", "Showing island window: accessibility = $usingAccessibilityService")

        this.batteryLevel = batteryLevel
        this.ownerAddress = owner?.macAddress?.value
        this.model = ownerModel
        this.batteries = owner?.state?.value?.battery.orEmpty()
        this.nearby = owner != null && owner.connectionState.value != ConnectionState.CONNECTED
        this.batteryNow = System.currentTimeMillis()

        this.name = name
        this.type = type
        this.reversed = reversed
        this.otherDeviceName = otherDeviceName
        this.statusText = statusText

        this.openApp = openApp

        this.showOffListeningMode = showOffListeningMode
        this.noiseControlModeValue = noiseControlModeValue
        this.adaptiveStrength = adaptiveStrength
        this.requestedAdaptiveStrength = requestedAdaptiveStrength

        this.onNoiseControlModeChanged = {
            this.noiseControlModeValue = it
            onNoiseControlModeChanged(it)
        }

        this.onAdaptiveStrengthChanged = {
            onAdaptiveStrengthChanged(it)
        }

        beginTransition(forcedState ?: if (appSettings.islandSettings.enableExpanded) IslandState.EXPANDED else IslandState.COMPACT)
        createLayoutParams(islandState)
        if (composeView == null) {
            setupComposeView()
        }
        composeView?.logFirstOverlayFrame("island", observedAtNanos)

        if (composeView?.parent == null) {
            val params = createLayoutParams(islandState)
            try {
                lifecycleOwner?.start()
                windowManager.addView(composeView, params)
                composeView?.visibility = View.VISIBLE
            } catch (e: Exception) {
                Log.e("IslandWindow", "Error adding overlay view: $e")
            }
        }

        updateWindowParams(islandState)
        ageHandler.removeCallbacks(updateAge)
        if (owner != null) ageHandler.post(updateAge)
        if (!keepOpen) resetTimer()
    }

    private fun updateWindowParams(state: IslandState) {
        if (composeView?.parent != null) {
            val params = createLayoutParams(state)
            windowManager.updateViewLayout(composeView, params)
        }
    }

    private fun createLayoutParams(state: IslandState = islandState): WindowManager.LayoutParams {
        val metrics = windowManager.currentWindowMetrics
        val cutout = metrics.windowInsets.displayCutout
        val camera = cutout?.cutoutPath?.let { path -> RectF().also { path.computeBounds(it, true) } }
            ?.takeIf { !it.isEmpty } ?: cutout?.boundingRects?.firstOrNull()?.let(::RectF)
        val placement = islandPlacement(metrics.bounds,
            metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()),
            camera, context.resources.displayMetrics.density, appSettings.islandSettings)
        effectiveSettings = appSettings.islandSettings.copy(expandedWidth = placement.expandedWidth.toFloat(),
            expandedHeight = placement.expandedHeight.toFloat(), compactWidth = placement.compactWidth.toFloat(),
            compactHeight = placement.compactHeight.toFloat())
        cameraPadding = placement.contentTop
        cameraCapsuleWidth = camera?.let { it.width() + 12 * context.resources.displayMetrics.density }
            ?: placement.compactHeight.toFloat()
        val windowType = if (context is android.accessibilityservice.AccessibilityService) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        val targetWidth = when (state) {
            IslandState.EXPANDED -> placement.expandedWidth
            IslandState.COMPACT -> placement.compactWidth
            IslandState.CLOSE -> minOf(placement.compactWidth,
                maxOf(placement.compactHeight, cameraCapsuleWidth.toInt()))
        }
        return WindowManager.LayoutParams(
            maxOf(targetWidth, retainedWindowWidth),
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_SPLIT_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSPARENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = placement.x
            y = placement.y
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    private fun setupComposeView() {
        lifecycleOwner = OverlayLifecycleOwner()

        composeView = ComposeView(ContextThemeWrapper(context, R.style.Theme_LibrePods_Overlay)).apply {
            val overlayView = this
            isForceDarkAllowed = false
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)

            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)

            setContent {
                val darkTheme = when (currentAppSettings.nightMode) {
                    NightTheme.Dark -> true
                    NightTheme.Light -> false
                    NightTheme.System -> isSystemInDarkTheme()
                }

                LibrePodsTheme(
                    designSystem = currentAppSettings.designSystem,
                    overrideMaterialColor = currentAppSettings.overrideMaterialColor,
                    accessibilitySettings = currentAppSettings.accessibilitySettings,
                    fontSettings = currentAppSettings.fontSettings,
                    darkTheme = darkTheme
                ) {
                    IslandContent(
                        name = name,
                        type = type,
                        batteryLevel = batteryLevel,
                        reversed = reversed,
                        otherDeviceName = otherDeviceName,
                        statusText = statusText,

                        islandSettings = effectiveSettings,
                        islandState = islandState,

                        openApp = { openApp() },
                        changeState = { state, _ -> changeState(state) },
                        transitionGeneration = transitionGeneration,
                        onTransitionFinished = { generation, state -> finishTransition(overlayView, generation, state) },
                        resetTimer = { resetTimer() },

                        showOffListeningMode = showOffListeningMode,
                        noiseControlModeValue = noiseControlModeValue,
                        onNoiseControlModeChanged = this@IslandWindow.onNoiseControlModeChanged,
                        adaptiveStrength = this@IslandWindow.adaptiveStrength,
                        requestedAdaptiveStrength = this@IslandWindow.requestedAdaptiveStrength,
                        onAdaptiveStrengthChanged = this@IslandWindow.onAdaptiveStrengthChanged,
                        model = model, batteries = batteries, batteryNow = batteryNow,
                        paired = ownerAddress != null, nearby = nearby, cameraPaddingPx = cameraPadding,
                        cameraCapsuleWidthPx = cameraCapsuleWidth
                    )
                }
            }
        }
    }

    private fun resetTimer() {
        autoCloseRunnable?.let { autoCloseHandler?.removeCallbacks(it) }
        autoCloseHandler = Handler(Looper.getMainLooper())
        autoCloseRunnable = Runnable {
            if (appSettings.islandSettings.keepCompactVisible) {
                changeState(IslandState.COMPACT)
            } else {
                close()
            }
        }
        autoCloseHandler?.postDelayed(autoCloseRunnable!!, 4500)
    }

    fun close() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { close() }
            return
        }

        autoCloseRunnable?.let { autoCloseHandler?.removeCallbacks(it) }
        ageHandler.removeCallbacks(updateAge)
        beginTransition(IslandState.CLOSE)
    }

    private fun cleanupAndRemoveView(expected: ComposeView) {
        if (composeView !== expected || islandState != IslandState.CLOSE) return
        try {
            composeView?.let { view ->
                if (view.parent != null) {
                    windowManager.removeView(view)
                }
            }
            lifecycleOwner?.stop()
        } catch (e: Exception) {
            Log.e("IslandWindow", "Error removing view: $e")
        } finally {
            ageHandler.removeCallbacks(updateAge)
            ownerAddress = null
            composeView = null
            lifecycleOwner = null
            retainedWindowWidth = 0
            islandState = IslandState.CLOSE
        }
    }
}

private class OverlayLifecycleOwner: LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle
        field = LifecycleRegistry(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    fun start() {
        savedStateRegistryController.performRestore(null)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun stop() {
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}
