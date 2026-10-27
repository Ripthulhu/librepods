package me.kavishdevar.librepods.presentation.screens.apple

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.core.bluetooth.aacp.types.ControlCommandIdentifier
import me.kavishdevar.librepods.core.devices.*
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w320dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NoiseModeCycleRenderingTest {
    @get:Rule val compose = createComposeRule()
    private val id = ControlCommandIdentifier.LISTENING_MODE_CONFIGS
    private val metadata = AppleMetadata(model = AirPodsModel.AIRPODS_PRO_3, modelNumber = "A3063", version1 = "9A348")

    @Test fun materialUnknownMaskUpdatesOnlyCompleteSelectionsAndDoesNotWriteOnEcho() = stagedSelection(DesignSystem.Material)

    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    @Test fun appleUnknownMaskUpdatesOnlyCompleteSelectionsAndDoesNotWriteOnEcho() = stagedSelection(DesignSystem.Apple)

    private fun stagedSelection(theme: DesignSystem) {
        val current = mutableStateOf(AppleState(controlStates = mapOf(ControlCommandIdentifier.ALLOW_OFF_OPTION to byteArrayOf(1))))
        val writing = mutableStateOf(false)
        val writes = mutableListOf<Int>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                LibrePodsTheme(designSystem = theme) {
                    Column(Modifier.requiredSize(320.dp, 900.dp).background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(16.dp).testTag("noise-cycle").verticalScroll(rememberScrollState())) {
                        NoiseModeCycleSettings(current.value, AppleSettings(), metadata, true, writing.value) { writes += it }
                    }
                }
            }
        }
        assertTrue(writes.isEmpty())
        compose.onAllNodesWithText("Apply").assertCountEquals(0)
        choose("Off")
        compose.onAllNodesWithText("Apply").assertCountEquals(0)
        choose("Adaptive")
        choose("Noise Cancellation")
        compose.onNodeWithText("Transparency").assertIsNotSelected()
        assertTrue("Incomplete masks are never submitted", writes.all { it.countOneBits() >= 2 })
        compose.runOnIdle {
            assertEquals(listOf(9, 11), writes)
            current.value = current.value.copy(requestedControls = mapOf(id to byteArrayOf(11)))
            writing.value = true
        }
        compose.onAllNodesWithText("Applying…").assertCountEquals(0)
        compose.runOnIdle { writing.value = false }
        compose.onNodeWithText("Off").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Adaptive").assertIsSelected()
        compose.onNodeWithText("Noise Cancellation").assertIsSelected()
        for (commentary in listOf("Your choice", "not confirmed", "Current selection unavailable", "Selection not applied"))
            compose.onAllNodesWithText(commentary, substring = true).assertCountEquals(0)
        if (theme == DesignSystem.Material) {
            val capture = File("build/overlay-previews/noise-cycle-material-narrow-large.png")
            capture.parentFile.mkdirs()
            capture.outputStream().use {
                assertTrue(compose.onNodeWithTag("noise-cycle").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
        compose.runOnIdle {
            current.value = current.value.copy(controlStates = current.value.controlStates + (id to byteArrayOf(11)),
                requestedControls = emptyMap(), liveControlReports = setOf(id))
        }
        compose.onNodeWithText("Off").performScrollTo().assertIsSelected()
        compose.onAllNodesWithText("Apply").assertCountEquals(0)
        compose.runOnIdle { current.value = current.value.copy(controlStates = current.value.controlStates + (id to byteArrayOf(6))) }
        compose.onNodeWithText("Transparency").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Off").assertIsNotSelected()
        compose.runOnIdle { assertEquals("Opening, choosing and incoming reports never cause another write", listOf(9, 11), writes) }
    }

    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    @Test fun offPrerequisiteConnectionAndChangedModelInvalidateUnsafeDrafts() {
        val currentMetadata = mutableStateOf(metadata)
        val allowOff = mutableStateOf(false)
        val connected = mutableStateOf(true)
        val writes = mutableListOf<Int>()
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Apple) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    NoiseModeCycleSettings(AppleState(controlStates = mapOf(ControlCommandIdentifier.ALLOW_OFF_OPTION to
                        byteArrayOf(if (allowOff.value) 1 else 2))), AppleSettings(), currentMetadata.value, connected.value, false) { writes += it }
                }
            }
        }
        compose.onNodeWithText("Off").assertDoesNotExist()
        compose.onNodeWithText("Turn on Off Listening Mode in AirPods settings.").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { allowOff.value = true }
        choose("Off")
        assertTrue(writes.isEmpty())
        compose.runOnIdle { connected.value = false }
        compose.onNodeWithText("Noise Cancellation").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            connected.value = true
            currentMetadata.value = AppleMetadata(model = AirPodsModel.AIRPODS_PRO_1, modelNumber = "A2084", version1 = "6A326")
        }
        compose.onNodeWithText("Adaptive").assertDoesNotExist()
        compose.onAllNodesWithText("Apply").assertCountEquals(0)
        assertTrue(writes.isEmpty())
    }

    private fun choose(label: String) = compose.onNodeWithText(label).performScrollTo().performClick()
}
