/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import android.app.Application
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.graphics.Insets
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
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
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.AppleDevice
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.core.devices.AppleState
import me.kavishdevar.librepods.core.devices.Battery
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.core.devices.BatteryStatus
import me.kavishdevar.librepods.core.devices.ConnectionState
import me.kavishdevar.librepods.data.app.IslandSettings
import me.kavishdevar.librepods.database.app.AppSettingsEntity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class IslandStateTest {
    @Test fun islandKeepsItsFrameworkDarkOptOutEvenWhenTheCallingAppThemeIsLight() {
        val caller = ContextThemeWrapper(RuntimeEnvironment.getApplication(), android.R.style.Theme_Material_Light_NoActionBar)
        val lightTheme = caller.resources.getIdentifier("isLightTheme", "attr", "android")
        caller.obtainStyledAttributes(intArrayOf(lightTheme)).let {
            try { assertTrue(it.getBoolean(0, false)) } finally { it.recycle() }
        }
        val window = IslandWindow(caller, AppSettingsEntity())
        IslandWindow::class.java.getDeclaredMethod("setupComposeView").apply { isAccessible = true }.invoke(window)
        val view = IslandWindow::class.java.getDeclaredField("composeView").apply { isAccessible = true }.get(window) as ComposeView
        try {
            assertFalse(view.isForceDarkAllowed)
            view.context.obtainStyledAttributes(intArrayOf(lightTheme, android.R.attr.forceDarkAllowed)).let {
                try {
                    assertFalse(it.getBoolean(0, true))
                    assertFalse(it.getBoolean(1, true))
                } finally { it.recycle() }
            }
            caller.obtainStyledAttributes(intArrayOf(lightTheme)).let {
                try { assertTrue(it.getBoolean(0, false)) } finally { it.recycle() }
            }
        } finally { view.disposeComposition() }
    }

    @Test fun nativeWindowRetainsOutgoingWidthUntilTheMatchingTransitionCompletes() {
        val context = RuntimeEnvironment.getApplication()
        val window = IslandWindow(context, AppSettingsEntity())
        val view = ComposeView(context).apply {
            layoutParams = WindowManager.LayoutParams(1600, 600,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, 0, PixelFormat.TRANSPARENT)
        }
        assertEquals(1600, view.layoutParams.width)
        IslandWindow::class.java.getDeclaredField("composeView").apply { isAccessible = true }.set(window, view)
        val params = IslandWindow::class.java.getDeclaredMethod("createLayoutParams", IslandState::class.java)
            .apply { isAccessible = true }
        val generation = IslandWindow::class.java.getDeclaredMethod("getTransitionGeneration").apply { isAccessible = true }
        val finish = IslandWindow::class.java.getDeclaredMethod("finishTransition", ComposeView::class.java,
            Long::class.javaPrimitiveType, IslandState::class.java).apply { isAccessible = true }
        window.changeState(IslandState.EXPANDED)
        val outgoingGeneration = generation.invoke(window) as Long
        window.changeState(IslandState.COMPACT)
        val compactGeneration = generation.invoke(window) as Long
        assertEquals(1600, (params.invoke(window, IslandState.COMPACT) as WindowManager.LayoutParams).width)
        finish.invoke(window, view, outgoingGeneration, IslandState.EXPANDED)
        assertEquals(1600, (params.invoke(window, IslandState.COMPACT) as WindowManager.LayoutParams).width)
        finish.invoke(window, ComposeView(context), compactGeneration, IslandState.COMPACT)
        assertEquals(1600, (params.invoke(window, IslandState.COMPACT) as WindowManager.LayoutParams).width)
        finish.invoke(window, view, compactGeneration, IslandState.COMPACT)
        assertTrue((params.invoke(window, IslandState.COMPACT) as WindowManager.LayoutParams).width < 1600)
    }

    @Test fun sameSizeStatusAndBatteryUpdatesDoNotRestartTheOpeningTransition() {
        val window = IslandWindow(RuntimeEnvironment.getApplication(), AppSettingsEntity())
        val generation = IslandWindow::class.java.getDeclaredMethod("getTransitionGeneration").apply { isAccessible = true }
        window.changeState(IslandState.EXPANDED)
        val firstGeneration = generation.invoke(window) as Long
        assertTrue(firstGeneration > 0L)
        window.changeState(IslandState.EXPANDED)
        window.updateBattery(42)
        window.updateNoiseControl(3, 40)
        assertEquals(firstGeneration, generation.invoke(window))
    }

    @Test fun cachedAgesUseStableCoarseUnitsAndShareOnlyWhenEveryComponentMatches() {
        assertEquals(0L to TimeUnit.SECONDS, islandAgeBucket(59))
        assertEquals(1L to TimeUnit.MINUTES, islandAgeBucket(60))
        assertEquals(59L to TimeUnit.MINUTES, islandAgeBucket(3599))
        assertEquals(1L to TimeUnit.HOURS, islandAgeBucket(3600))
        assertEquals(1L to TimeUnit.DAYS, islandAgeBucket(86400))
        val cells = listOf(BatteryComponent.LEFT, BatteryComponent.RIGHT, BatteryComponent.CASE).map {
            IslandBatteryCell(it, 80, live = false, charging = false, ageSeconds = 120)
        }
        assertEquals(2L to TimeUnit.MINUTES, islandCommonAge(cells))
        assertEquals(2L to TimeUnit.MINUTES, islandCommonAge(cells.dropLast(1) + cells.last().copy(ageSeconds = 179)))
        assertNull(islandCommonAge(cells.dropLast(1) + cells.last().copy(ageSeconds = 180)))
        assertNull(islandCommonAge(cells.dropLast(1) + cells.last().copy(live = true)))
        assertNull(islandCommonAge(cells.dropLast(1) + cells.last().copy(level = null, ageSeconds = null)))
    }

    @Test fun openingStartsWithAnAlreadyVisibleCameraCapsuleForBothDisplayModes() {
        val settings = IslandSettings(compactWidth = 300f, compactHeight = 132f,
            expandedWidth = 1248f, expandedHeight = 630f)
        for (state in listOf(IslandState.EXPANDED, IslandState.COMPACT)) {
            val first = islandInitialSize(settings, state, motionEnabled = true)
            assertEquals(132f, first.first, 0f)
            assertEquals(132f, first.second, 0f)
            assertTrue(first.first < settings.compactWidth)
        }
        val wideCamera = islandInitialSize(settings, IslandState.EXPANDED, true, cameraCapsuleWidth = 210f)
        assertEquals(210f, wideCamera.first, 0f)
        assertEquals(132f, wideCamera.second, 0f)
    }

    @Test fun reducedMotionStartsAtTheRequestedSizeWithoutAnEntranceOrAttachmentDelay() {
        val settings = IslandSettings(compactWidth = 300f, compactHeight = 132f,
            expandedWidth = 1248f, expandedHeight = 630f)
        assertEquals(settings.expandedWidth to settings.expandedHeight,
            islandInitialSize(settings, IslandState.EXPANDED, motionEnabled = false))
        assertEquals(settings.compactWidth to settings.compactHeight,
            islandInitialSize(settings, IslandState.COMPACT, motionEnabled = false))
        assertEquals(settings.compactHeight to settings.compactHeight,
            islandInitialSize(settings, IslandState.CLOSE, motionEnabled = true))
    }

    @Test fun anInterruptedExitCannotRemoveAReopenedOrReplacementOverlay() {
        val context = RuntimeEnvironment.getApplication()
        val window = IslandWindow(context, AppSettingsEntity())
        val first = ComposeView(context)
        val replacement = ComposeView(context)
        val field = IslandWindow::class.java.getDeclaredField("composeView").apply { isAccessible = true }
        val completeExit = IslandWindow::class.java.getDeclaredMethod("cleanupAndRemoveView", ComposeView::class.java)
            .apply { isAccessible = true }
        field.set(window, first)
        window.changeState(IslandState.EXPANDED)
        completeExit.invoke(window, first)
        assertSame(first, field.get(window))
        window.changeState(IslandState.CLOSE)
        field.set(window, replacement)
        completeExit.invoke(window, first)
        assertSame(replacement, field.get(window))
        completeExit.invoke(window, replacement)
        assertNull(field.get(window))
    }

    @Test fun rapidPairedReopensKeepTheLatestBatteryAndRejectOldExitCompletion() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        val firstOwner = AppleDevice(context, adapter, adapter.getRemoteDevice("00:11:22:33:44:55"), ConnectionState.DISCONNECTED)
        val nextOwner = AppleDevice(context, adapter, adapter.getRemoteDevice("00:11:22:33:44:66"), ConnectionState.DISCONNECTED)
        val window = IslandWindow(context, AppSettingsEntity())
        val view = ComposeView(context)
        val field = IslandWindow::class.java.getDeclaredField("composeView").apply { isAccessible = true }
        val owner = IslandWindow::class.java.getDeclaredField("ownerAddress").apply { isAccessible = true }
        val generation = IslandWindow::class.java.getDeclaredMethod("getTransitionGeneration").apply { isAccessible = true }
        val batteries = IslandWindow::class.java.getDeclaredMethod("getBatteries").apply { isAccessible = true }
        val finish = IslandWindow::class.java.getDeclaredMethod("finishTransition", ComposeView::class.java,
            Long::class.javaPrimitiveType, IslandState::class.java).apply { isAccessible = true }
        context.getSystemService(WindowManager::class.java).addView(view, WindowManager.LayoutParams(800, 600,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSPARENT))
        field.set(window, view)
        @Suppress("UNCHECKED_CAST")
        fun cells() = islandBatteryCells(AirPodsModel.AIRPODS_PRO_3,
            batteries.invoke(window) as Set<Battery>, System.currentTimeMillis())
        try {
            window.show(owner = firstOwner, ownerModel = AirPodsModel.AIRPODS_PRO_3, keepOpen = true)
            assertTrue(window.isVisible)
            assertTrue(cells().all { it.level == null && !it.charging })
            for ((index, device) in listOf(firstOwner, firstOwner, nextOwner).withIndex()) {
                window.close()
                val oldExit = generation.invoke(window) as Long
                window.show(owner = device, ownerModel = AirPodsModel.AIRPODS_PRO_3, keepOpen = true)
                assertTrue((generation.invoke(window) as Long) > oldExit)
                if (device === nextOwner) assertTrue("A new owner cannot inherit another pair's readings", cells().all { it.level == null })
                val charging = index % 2 == 0
                val readings = setOf(Battery(BatteryComponent.LEFT, 60 - index,
                    if (charging) BatteryStatus.CHARGING else BatteryStatus.NOT_CHARGING),
                    Battery(BatteryComponent.RIGHT, 40 - index,
                        if (charging) BatteryStatus.NOT_CHARGING else BatteryStatus.CHARGING),
                    Battery(BatteryComponent.CASE, 90 - index,
                        if (charging) BatteryStatus.OPTIMIZED_CHARGING else BatteryStatus.NOT_CHARGING))
                device.loadInitialState(AppleState(battery = readings), device.settings.value,
                    AppleMetadata(name = "AirPods", model = AirPodsModel.AIRPODS_PRO_3))
                window.updatePairedBattery(device)
                if (device === nextOwner) window.updatePairedBattery(firstOwner)
                finish.invoke(window, view, oldExit, IslandState.CLOSE)
                assertSame("An old exit cannot detach the reopened window", view, field.get(window))
                assertEquals(device.macAddress.value, owner.get(window))
                assertEquals(IslandState.EXPANDED, window.islandState)
                assertEquals(listOf(60 - index, 40 - index, 90 - index), cells().map { it.level })
                assertTrue(cells().all { it.live })
                assertEquals(listOf(charging, !charging, charging), cells().map { it.charging })
            }
        } finally {
            window.close()
            finish.invoke(window, view, generation.invoke(window) as Long, IslandState.CLOSE)
        }
    }

    @Test fun pairingOpeningSuppressesQueuedAnnouncementsUntilANewerOwnedCaseClose() = withPairedWindow { window, paired, other ->
        val address = paired.macAddress.value
        val queued = Thread { window.show(owner = paired, keepOpen = true) }
        queued.start()
        queued.join()
        assertEquals(IslandState.CLOSE, window.islandState)
        window.suppressPairingOpening(address, 1_000L)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("An announcement queued before pairing completion must also be suppressed", window.isVisible)

        window.close() // Closing a card/island must not end the physical case opening.
        for ((owner, open, observed) in listOf(
            Triple(address, true, 1_001L),
            Triple(address, false, 999L),
            Triple(address, false, 1_000L),
            Triple(other.macAddress.value, false, 1_001L)
        )) {
            window.observePairedCaseLid(owner, open, observed)
            window.showPairedLid(paired)
            window.updatePairedBattery(paired)
            assertEquals(IslandState.CLOSE, window.islandState)
        }
        window.observePairedCaseLid(address, false, 1_001L)
        window.observePairedCaseLid(address, true, 1_002L)
        window.showPairedLid(paired)
        assertTrue("The next actual case opening is eligible again", window.isVisible)

        window.suppressPairingOpening(address, 2_000L)
        window.clearPairingOpening(other.macAddress.value)
        window.show(owner = paired, keepOpen = true)
        assertFalse(window.isVisible)
        window.clearPairingOpening(address)
        window.show(owner = paired, keepOpen = true)
        assertTrue("Unpairing retires only that pair's suppression", window.isVisible)
        window.suppressPairingOpening(address, 3_000L)
        window.clearPairingOpening()
        window.show(owner = paired, keepOpen = true)
        assertTrue("Adapter/service retirement clears the in-memory policy", window.isVisible)
    }

    @Test fun pairingOpeningClosesOnlyItsAutomaticIslandAndKeepsModeForeignAndPreviewNotices() = withPairedWindow { window, paired, other ->
        window.show(owner = paired, keepOpen = true)
        assertTrue(window.isVisible)
        window.suppressPairingOpening(paired.macAddress.value, 1_000L)
        assertEquals("The pairing card replaces an already-visible automatic island", IslandState.CLOSE, window.islandState)

        window.show(owner = paired, statusText = "Transparency", keepOpen = true)
        assertTrue(window.isVisible)
        window.suppressPairingOpening(paired.macAddress.value, 1_001L)
        assertTrue("An explicit listening-mode notice stays visible", window.isVisible)
        window.show(owner = other, keepOpen = true)
        window.suppressPairingOpening(paired.macAddress.value, 1_002L)
        assertTrue("Another pair remains eligible", window.isVisible)
        window.show(owner = paired, type = IslandType.TAKING_OVER, keepOpen = true)
        assertTrue("An intentional takeover notice is not a battery announcement", window.isVisible)
        window.show(keepOpen = true)
        assertTrue("Customization previews have no paired owner", window.isVisible)
    }

    private fun withPairedWindow(block: (IslandWindow, AppleDevice, AppleDevice) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        val paired = AppleDevice(context, adapter, adapter.getRemoteDevice("00:11:22:33:44:55"), ConnectionState.DISCONNECTED)
        val other = AppleDevice(context, adapter, adapter.getRemoteDevice("00:11:22:33:44:66"), ConnectionState.DISCONNECTED)
        val window = IslandWindow(context, AppSettingsEntity())
        val owner = object : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
            val registry = LifecycleRegistry(this)
            val savedState = SavedStateRegistryController.create(this)
            override val lifecycle: Lifecycle get() = registry
            override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
            override val viewModelStore = ViewModelStore()
        }
        owner.savedState.performRestore(null)
        owner.registry.currentState = Lifecycle.State.RESUMED
        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
        }
        context.getSystemService(WindowManager::class.java).addView(view, WindowManager.LayoutParams(800, 600,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSPARENT))
        IslandWindow::class.java.getDeclaredField("composeView").apply { isAccessible = true }.set(window, view)
        try { block(window, paired, other) } finally {
            window.close()
            IslandWindow::class.java.getDeclaredMethod("cleanupAndRemoveView", ComposeView::class.java)
                .apply { isAccessible = true }.invoke(window, view)
            view.disposeComposition()
            owner.registry.currentState = Lifecycle.State.DESTROYED
            owner.viewModelStore.clear()
        }
    }

    @Test fun physicalCameraUsesAcceptedPixelGeometryAndWiderSafeWidth() {
        val placement = islandPlacement(Rect(0, 0, 1344, 2992), Insets.of(0, 200, 0, 72),
            RectF(623.5f, 52f, 719.5f, 148f), 3f, IslandSettings())
        assertEquals(1248, placement.expandedWidth)
        assertEquals(34, placement.y)
        assertEquals(150, placement.contentTop)
        assertEquals(132, placement.compactHeight)
        assertTrue(placement.compactWidth >= 288)
        assertEquals(0, placement.x)
        assertTrue(placement.expandedHeight >= placement.contentTop + 480)
    }

    @Test fun noCutoutStaysBelowStatusBarAndCustomPositionIsConstrained() {
        val settings = IslandSettings(expandedWidth = 2000f, offsetX = 500f, offsetY = 250f)
        val placement = islandPlacement(Rect(0, 0, 1080, 2400), Insets.of(0, 72, 0, 72), null, 3f, settings)
        assertEquals(984, placement.expandedWidth)
        assertEquals(250, placement.y)
        assertEquals(0, placement.x)
        val defaults = islandPlacement(Rect(0, 0, 1080, 2400), Insets.of(0, 72, 0, 72), null, 3f, IslandSettings())
        assertEquals(108, defaults.y)
    }

    @Test fun everyBatteryKeepsItsOwnAgeAndUnknownIsNotZero() {
        val now = 200_000L
        val cells = islandBatteryCells(AirPodsModel.AIRPODS_PRO_3, setOf(
            Battery(BatteryComponent.LEFT, 100, BatteryStatus.CHARGING, now - 1_000),
            Battery(BatteryComponent.RIGHT, 47, BatteryStatus.CHARGING, now - 90_000),
            Battery(BatteryComponent.CASE, 0, BatteryStatus.DISCONNECTED)
        ), now)
        assertEquals(listOf(BatteryComponent.LEFT, BatteryComponent.RIGHT, BatteryComponent.CASE), cells.map { it.component })
        assertTrue(cells[0].live)
        assertTrue(cells[0].charging)
        assertEquals(1L, cells[0].ageSeconds)
        assertEquals(47, cells[1].level)
        assertFalse(cells[1].live)
        assertFalse(cells[1].charging)
        assertEquals(90L, cells[1].ageSeconds)
        assertNull(cells[2].level)
        assertNull(cells[2].ageSeconds)
        assertFalse(cells[2].charging)
    }

    @Test fun explicitMissingComponentKeepsPercentageButCannotClaimCharging() {
        val cells = islandBatteryCells(AirPodsModel.AIRPODS_PRO_3, setOf(
            Battery(BatteryComponent.LEFT, 0, BatteryStatus.NOT_CHARGING, 10_000),
            Battery(BatteryComponent.RIGHT, 98, BatteryStatus.CHARGING, 10_000, present = false)
        ), 11_000)
        assertEquals(0, cells[0].level)
        assertTrue(cells[0].live)
        assertEquals(98, cells[1].level)
        assertFalse(cells[1].live)
        assertFalse(cells[1].charging)
        assertNull(cells[2].level)
        assertEquals(listOf(BatteryComponent.HEADSET),
            islandBatteryCells(AirPodsModel.AIRPODS_MAX_USBC, emptySet(), 11_000).map { it.component })
    }
}
