package me.kavishdevar.librepods.presentation.overlays

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.AirPodsSpecs
import me.kavishdevar.librepods.core.devices.Battery
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.core.devices.BatteryStatus
import me.kavishdevar.librepods.data.app.IslandBatteryStyle
import me.kavishdevar.librepods.data.app.IslandSettings
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
// Keep Espresso's legacy InputManager bridge on the app's supported API 33 runtime.
// API 37 theme/window behavior is covered separately by IslandStateTest.
@Config(sdk = [33], application = Application::class, qualifiers = "w448dp-h998dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class IslandMotionRenderingTest {
    @get:Rule val compose = createComposeRule()

    private data class Frame(val label: String, val width: Int, val height: Int, val reveal: Float)
    private val positions = mutableMapOf<String, LayoutCoordinates>()
    private val fullWidth = 1248
    private val fullHeight = 720
    private val cameraTop = 150f

    @Test fun expandedPayloadKeepsItsMeasuredTextAndArtworkLayoutThroughoutRevealAndExit() {
        val full = Frame("expanded", fullWidth, fullHeight, 1f)
        val frame = mutableStateOf(full)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(3f)) {
                MaterialTheme {
                    Box(Modifier.requiredSize((fullWidth / 3f).dp, (fullHeight / 3f).dp)
                        .background(Color(0xFF666666)).testTag("motion-host"), contentAlignment = Alignment.TopCenter) {
                        val current = frame.value
                        Box(Modifier.requiredSize((current.width / 3f).dp, (current.height / 3f).dp)
                            .record("shell").clip(RoundedCornerShape(30.dp)).background(Color.Black)) {
                            IslandPayload(fullWidth, fullHeight, current.reveal,
                                cameraSafeTopPx = cameraTop) {
                                ExpandedContent()
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val settledBounds = compose.runOnIdle { contentBounds() }
        for (current in listOf(full, Frame("opening-middle", 780, 460, 0.6f),
            Frame("opening-camera", 240, 132, 0.12f), Frame("exit-middle", 600, 330, 0.4f),
            Frame("exit-camera", 132, 132, 0f), full.copy(label = "reopened"))) {
            compose.runOnIdle { frame.value = current }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals("The endpoint payload must not inherit the moving shell size", IntSize(fullWidth, fullHeight),
                    requireNotNull(positions["payload"]).size)
                val bounds = contentBounds()
                for ((tag, settled) in settledBounds) assertRectClose("${current.label}: $tag reflowed", settled, bounds.getValue(tag))
                assertTrue("Artwork must not overlap the device name", bounds.getValue("art").right < bounds.getValue("title").left)
                assertTrue("Status must not overlap the device name", bounds.getValue("title").bottom <= bounds.getValue("status").top)
                assertTrue("Header must not overlap the battery row", bounds.getValue("header").bottom <= bounds.getValue("batteries").top)
                assertTrue("Final content must start below the camera clearance", bounds.getValue("header").top >= cameraTop)
                if (current.height > cameraTop) {
                    val shell = requireNotNull(positions["shell"])
                    for (tag in settledBounds.keys) {
                        val drawn = shell.localBoundingBoxOf(requireNotNull(positions[tag]), clipBounds = false)
                        assertTrue("${current.label}: $tag must fit the shell without clipping words or artwork",
                            drawn.left >= -0.5f && drawn.right <= shell.size.width + 0.5f &&
                                drawn.top >= cameraTop - 0.5f && drawn.bottom <= shell.size.height + 0.5f)
                    }
                }
            }
            val bitmap = compose.onNodeWithTag("motion-host").captureToImage().asAndroidBitmap()
            val output = File("build/overlay-previews/island-motion-${current.label}.png")
            output.parentFile.mkdirs()
            output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }

    @Test fun cameraBandClipsTheTransformedPayloadWithoutClippingTheRestOfTheShell() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(3f)) {
                Box(Modifier.requiredSize((fullWidth / 3f).dp, (fullHeight / 3f).dp)
                    .background(Color(0xFF666666)).testTag("motion-host"), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.requiredSize(260.dp, (460 / 3f).dp)
                        .clip(RoundedCornerShape(30.dp)).background(Color.Black)) {
                        IslandPayload(fullWidth, fullHeight, 0.6f, cameraSafeTopPx = cameraTop) {
                            // Deliberately attempts to draw through the camera; the production renderer must clip it.
                            Box(Modifier.fillMaxSize().background(Color.Magenta))
                        }
                    }
                }
            }
        }
        val bitmap = compose.onNodeWithTag("motion-host").captureToImage().asAndroidBitmap()
        assertEquals(android.graphics.Color.BLACK, bitmap.getPixel(fullWidth / 2, 75))
        assertEquals(android.graphics.Color.MAGENTA, bitmap.getPixel(fullWidth / 2, 210))
        assertEquals("Payload must remain inside the moving shell", 0xFF666666.toInt(), bitmap.getPixel(80, 210))
    }

    @Test fun productionCompactContentSurvivesTheCameraAndStatusOnlyUpdatesDoNotReplayExpansion() {
        val state = mutableStateOf(IslandState.EXPANDED)
        val status = mutableStateOf("Nearby")
        val title = "Studio AirPods Pro 3"
        val settings = IslandSettings(expandedWidth = fullWidth.toFloat(), expandedHeight = 630f,
            compactWidth = 300f, compactHeight = 132f, compactBatteryStyle = IslandBatteryStyle.TEXT,
            keepCompactVisible = true)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(3f)) {
                LibrePodsTheme(darkTheme = true) {
                    Box(Modifier.requiredSize((fullWidth / 3f).dp, (fullHeight / 3f).dp)
                        .background(Color(0xFF666666)).testTag("motion-host"), contentAlignment = Alignment.TopCenter) {
                        IslandContent(name = title, type = IslandType.CONNECTED, batteryLevel = 100,
                            reversed = false, otherDeviceName = null, islandSettings = settings, islandState = state.value,
                            showOffListeningMode = false, noiseControlModeValue = 3, onNoiseControlModeChanged = {},
                            onAdaptiveStrengthChanged = {}, model = AirPodsModel.AIRPODS_PRO_3,
                            batteries = setOf(Battery(BatteryComponent.LEFT, 100, BatteryStatus.CHARGING, 1_000),
                                Battery(BatteryComponent.RIGHT, 100, BatteryStatus.CHARGING, 1_000),
                                Battery(BatteryComponent.CASE, 100, BatteryStatus.NOT_CHARGING, 1_000)),
                            batteryNow = 1_000, paired = true, nearby = true, cameraPaddingPx = cameraTop.toInt(),
                            cameraCapsuleWidthPx = 132f, statusText = status.value)
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(title).assertIsDisplayed()
        savePreview("production-expanded")
        val before = compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { status.value = "Connected" }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(title).assertIsDisplayed()
        assertRectClose("A status-only update must not restart expansion", before,
            compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot)
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { state.value = IslandState.COMPACT }
        compose.waitForIdle()
        compose.onNodeWithText("100", useUnmergedTree = true).assertIsDisplayed()
        val host = compose.onNodeWithTag("motion-host").fetchSemanticsNode().boundsInRoot
        val artwork = compose.onNodeWithTag("island-compact-artwork", useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val battery = compose.onNodeWithTag("island-compact-battery", useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val capsuleLeft = host.left + fullWidth / 2f - 66f
        val capsuleRight = host.left + fullWidth / 2f + 66f
        assertTrue("The full artwork layout must fit left of the reserved camera capsule", artwork.right <= capsuleLeft + 0.5f)
        assertTrue("The full battery layout must fit right of the reserved camera capsule", battery.left >= capsuleRight - 0.5f)
        assertEquals("Artwork must use the complete left wing", 60f, artwork.width, 0.5f)
        assertEquals("Battery must use the complete right wing", 60f, battery.width, 0.5f)
        val number = compose.onNodeWithText("100", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("The complete percentage must fit inside its camera-safe wing",
            number.left >= battery.left - 0.5f && number.right <= battery.right + 0.5f)
        val bitmap = compose.onNodeWithTag("motion-host").captureToImage().asAndroidBitmap()
        val left = (fullWidth - 300) / 2
        var artworkPixels = 0
        for (y in 32 until 100) for (x in left + 30 until left + 95) {
            val pixel = bitmap.getPixel(x, y)
            if (android.graphics.Color.red(pixel) > 90 && android.graphics.Color.green(pixel) > 90 &&
                android.graphics.Color.blue(pixel) > 90) artworkPixels++
        }
        assertTrue("Compact artwork must not be hidden by the expanded 150px camera inset", artworkPixels > 20)
        val output = File("build/overlay-previews/island-motion-compact.png")
        output.parentFile.mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun savePreview(label: String) {
        val bitmap = compose.onNodeWithTag("motion-host").captureToImage().asAndroidBitmap()
        val output = File("build/overlay-previews/island-motion-$label.png")
        output.parentFile.mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Composable private fun ExpandedContent() {
        Column(Modifier.fillMaxSize().record("payload")
            .padding(start = 16.dp, end = 16.dp, top = (cameraTop / 3f).dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().weight(1f).record("header"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Image(painterResource(AirPodsSpecs.getSpec(AirPodsModel.AIRPODS_PRO_3).primaryImageRes), null,
                    Modifier.size(64.dp).record("art"))
                Column(Modifier.weight(1f)) {
                    Text("Studio AirPods Pro 3 with a longer device name", color = Color.White,
                        fontSize = 20.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().record("title"))
                    Text("Nearby", color = Color.LightGray, fontSize = 14.sp, maxLines = 1,
                        modifier = Modifier.fillMaxWidth().record("status"))
                }
            }
            Row(Modifier.fillMaxWidth().height(64.dp).record("batteries"),
                horizontalArrangement = Arrangement.SpaceEvenly) {
                for (label in listOf("Left", "Right", "Case")) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("100%", color = Color.White, fontSize = 20.sp)
                    Text(label, color = Color.LightGray, fontSize = 12.sp)
                }
            }
        }
    }

    private fun Modifier.record(tag: String) = onGloballyPositioned { positions[tag] = it }
    private fun contentBounds(): Map<String, Rect> {
        val payload = requireNotNull(positions["payload"])
        return listOf("art", "title", "status", "header", "batteries").associateWith {
            payload.localBoundingBoxOf(requireNotNull(positions[it]), clipBounds = false)
        }
    }
    private fun assertRectClose(message: String, expected: Rect, actual: Rect) {
        assertEquals(message, expected.left, actual.left, 0.5f)
        assertEquals(message, expected.top, actual.top, 0.5f)
        assertEquals(message, expected.right, actual.right, 0.5f)
        assertEquals(message, expected.bottom, actual.bottom, 0.5f)
    }
}
