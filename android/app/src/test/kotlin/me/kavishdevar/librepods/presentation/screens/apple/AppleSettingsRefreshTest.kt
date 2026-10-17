package me.kavishdevar.librepods.presentation.screens.apple

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.AppleMetadata
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.presentation.viewmodel.AppleUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
// Keep Espresso's input bridge and Compose density on the same supported API 33 runtime.
@Config(sdk = [33], application = Application::class, qualifiers = "w448dp-h998dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AppleSettingsRefreshTest {
    @get:Rule val compose = createComposeRule()

    // Apple backdrop AGSL is incomplete in Robolectric; test its actual input and semantics separately.
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    @Test fun refreshIsReachableAndCannotBeRepeatedWhileBusyOrDisconnected() {
        val busy = mutableStateOf(false)
        val connected = mutableStateOf(true)
        var requests = 0
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Apple) {
                SettingsScreen(busy.value, connected.value) { requests++ }
            }
        }
        scrollToBottom()
        assertEquals("Opening settings must not refresh the device", 0, requests)
        val label = text(R.string.refresh_device_settings)
        compose.onNodeWithText(label).assertIsDisplayed().assertIsEnabled()
            .performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, requests); busy.value = true }
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.refresh_device_settings_busy))
            .assertIsDisplayed().assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle {
            assertEquals("Busy refresh must not send another request", 1, requests)
            busy.value = false
            connected.value = false
        }
        compose.waitForIdle()
        compose.onNodeWithText(label).assertIsDisplayed().assertIsNotEnabled()
            .performTouchInput { click() }
        compose.runOnIdle {
            assertEquals("Disconnected refresh must not send a request", 1, requests)
            connected.value = true
        }
        compose.waitForIdle()
        compose.onNodeWithText(label).assertIsEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals("A later connected refresh remains usable", 2, requests) }
    }

    @Config(qualifiers = "w320dp-h900dp-mdpi")
    @Test fun materialRefreshFitsNarrowLightScreenAtLargeText() {
        renderMaterial(dark = false, fontScale = 1.3f, name = "light-narrow-large")
    }

    @Test fun materialRefreshRemainsReadableInDarkAppearance() {
        renderMaterial(dark = true, fontScale = 1f, name = "dark-material")
    }

    private fun renderMaterial(dark: Boolean, fontScale: Float, name: String) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                LibrePodsTheme(darkTheme = dark, designSystem = DesignSystem.Material) {
                    SettingsScreen(busy = false, connected = true, refresh = {})
                }
            }
        }
        scrollToBottom()
        val button = compose.onNodeWithText(text(R.string.refresh_device_settings))
            .assertIsDisplayed().assertIsEnabled().fetchSemanticsNode().boundsInRoot
        val description = compose.onNodeWithText(text(R.string.refresh_device_settings_description))
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val disconnect = compose.onNodeWithText(text(R.string.disconnect))
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Refresh must retain a full touch target", button.height >= 48f)
        assertTrue("Refresh and its explanation must not overlap", button.bottom <= description.top)
        assertTrue("Refresh must remain above Disconnect", description.bottom <= disconnect.top)
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val output = File("build/overlay-previews/refresh-settings-$name.png")
        output.parentFile.mkdirs()
        output.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun scrollToBottom() {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text(R.string.disconnect)))
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun text(id: Int) = ApplicationProvider.getApplicationContext<Application>().getString(id)

    @Composable
    private fun SettingsScreen(busy: Boolean, connected: Boolean, refresh: () -> Unit) {
        AppleSettingsScreen(
            uiState = AppleUiState(metadata = AppleMetadata(name = "AirPods Pro 3",
                model = AirPodsModel.AIRPODS_PRO_3, modelName = "AirPods Pro 3", modelNumber = "A3063",
                version1 = "9A348"), isPremium = true),
            setControlCommandInt = { _, _ -> },
            setControlCommandBoolean = { _, _ -> },
            writeATTCharacteristic = { _, _ -> },
            updateSettings = {},
            disconnect = {},
            navigateBack = null,
            navigateToRename = {},
            navigateToHearingProtection = {},
            navigateToHearingAid = {},
            navigateToLeftLongPress = {},
            navigateToRightLongPress = {},
            navigateToPurchase = {},
            navigateToEqualizer = {},
            navigateToHeadTracking = {},
            navigateToAccessibility = {},
            navigateToVersion = {},
            navigateToCallControlScreen = {},
            navigateToMicrophoneSettings = {},
            navigateToRecordingScreen = {},
            navigateToHeartRateScreen = {},
            navigateToDebugScreen = {},
            refreshDeviceSettings = refresh,
            refreshingSettings = busy,
            canRefreshSettings = connected
        )
    }
}
