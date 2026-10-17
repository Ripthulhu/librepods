package me.kavishdevar.librepods.presentation.components.apple

import android.app.Application
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
// Espresso's input bridge remains compatible with the supported API33 runtime.
@Config(sdk = [33], application = Application::class, qualifiers = "w448dp-h998dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NoiseControlSettingsTest {
    @get:Rule val compose = createComposeRule()

    // This gesture/state test does not need Robolectric's incomplete native AGSL compiler.
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    @Test fun adaptiveDragKeepsFinalRequestAcrossReopeningUntilAFreshReportReplacesIt() {
        val reported = mutableStateOf(25)
        val requested = mutableStateOf<Int?>(null)
        val visible = mutableStateOf(true)
        val writes = mutableListOf<Int>()
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Apple) {
                if (visible.value) NoiseControlSettings(
                    modifier = Modifier.width(400.dp).height(64.dp),
                    showOffListeningMode = false,
                    noiseControlModeValue = 4,
                    onNoiseControlModeChanged = {},
                    adaptiveStrength = reported.value,
                    requestedAdaptiveStrength = requested.value,
                    onAdaptiveStrengthChanged = { writes += it; requested.value = it },
                    showLabels = false
                )
            }
        }
        settle()
        val initialLeft = thumbLeft()
        val initialBounds = compose.onNodeWithTag("noise-control-thumb").fetchSemanticsNode().boundsInRoot
        assertEquals("The touch target must follow the translated thumb", 120f, initialLeft, 1.5f)
        assertEquals("Adaptive mode reserves one fifth of the width for the thumb", 80f, initialBounds.width, 1.5f)
        assertTrue("The touch target must be laid out", initialBounds.height > 0f)
        assertTrue("Composition must not write a strength", writes.isEmpty())
        compose.onNodeWithTag("noise-control-thumb").performTouchInput {
            swipe(center, center + Offset(80f, 0f), durationMillis = 320)
        }
        settle()
        val finalRequested = compose.runOnIdle { requireNotNull(requested.value) }
        // The pressed thumb scales during the drag; assert retained intent, not unscaled finger travel.
        assertTrue("The final gesture position must be committed: requested=$finalRequested, writes=$writes, left=${thumbLeft()}",
            finalRequested > 50)
        assertEquals("The last gesture command must remain the displayed request", writes.last(), finalRequested)
        assertEquals("A request must not replace the device's report", 25, reported.value)
        val finalLeft = thumbLeft()
        assertTrue("Release must not snap back to the old report", finalLeft > initialLeft + 30f)
        assertEquals(80f + finalRequested * 1.6f, finalLeft, 1.5f)

        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { visible.value = true }
        settle()
        assertEquals("Navigation must retain the separate pending request", finalLeft, thumbLeft(), 1.5f)
        val beforeReport = writes.size
        compose.runOnIdle { reported.value = 30; requested.value = null }
        settle()
        assertEquals("A fresh rounded report must become authoritative", 128f, thumbLeft(), 1.5f)
        assertEquals("Report-driven motion must not send another command", beforeReport, writes.size)
    }

    @Test fun materialStrengthOnlyWritesForUserInputAndFollowsRequestedAndReportedValues() {
        val reported = mutableStateOf(30)
        val requested = mutableStateOf<Int?>(null)
        val writes = mutableListOf<Int>()
        compose.setContent {
            LibrePodsTheme(designSystem = DesignSystem.Material) {
                AdaptiveAudioStrengthSlider(
                    adaptiveStrength = reported.value,
                    requestedAdaptiveStrength = requested.value,
                    onAdaptiveStrengthChanged = { writes += it; requested.value = it },
                    enabled = true
                )
            }
        }
        settle()
        val slider = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
        assertTrue(writes.isEmpty())
        assertEquals(70f, slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0.01f)
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(85f) }
        settle()
        assertEquals(listOf(15), writes)
        assertEquals(30, reported.value)
        assertEquals(85f, slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0.01f)
        compose.runOnIdle { reported.value = 27; requested.value = null }
        settle()
        assertEquals(73f, slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0.01f)
        assertEquals("Opening or receiving reports must never send a command", listOf(15), writes)
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
    }

    private fun thumbLeft() = compose.onNodeWithTag("noise-control-thumb").fetchSemanticsNode().boundsInRoot.left
}
