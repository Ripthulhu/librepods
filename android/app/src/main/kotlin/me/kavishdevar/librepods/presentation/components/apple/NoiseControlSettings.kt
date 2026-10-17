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

package me.kavishdevar.librepods.presentation.components.apple

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.app.FontFamilyOption
import me.kavishdevar.librepods.data.app.FontSettings
import me.kavishdevar.librepods.core.devices.NoiseControlMode
import me.kavishdevar.librepods.presentation.components.primitives.StyledButton
import me.kavishdevar.librepods.presentation.components.primitives.StyledSlider
import me.kavishdevar.librepods.presentation.design.DesignSystem
import me.kavishdevar.librepods.presentation.design.LibrePodsTheme
import me.kavishdevar.librepods.presentation.design.LocalDesignSystem
import me.kavishdevar.librepods.presentation.icons.LocalIcons
import me.kavishdevar.librepods.presentation.utils.LocalDebugMode
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun NoiseControlSettings(
    modifier: Modifier = Modifier,
    showOffListeningMode: Boolean,
    noiseControlModeValue: Int,
    onNoiseControlModeChanged: (Int) -> Unit,
    adaptiveStrength: Int = 0,
    onAdaptiveStrengthChanged: (Int) -> Unit,
    showLabels: Boolean = true,
    requestedAdaptiveStrength: Int? = null
) {
    val currentModeChanged by rememberUpdatedState(onNoiseControlModeChanged)
    val currentStrengthChanged by rememberUpdatedState(onAdaptiveStrengthChanged)
    when (LocalDesignSystem.current) {
        DesignSystem.Material -> {
            val options = buildList {
                if (showOffListeningMode) {
                    add(
                        Triple(
                            NoiseControlMode.OFF,
                            R.string.off,
                            R.drawable.ic_noise_cancellation
                        )
                    )
                }

                add(
                    Triple(
                        NoiseControlMode.TRANSPARENCY,
                        R.string.transparency,
                        R.drawable.ic_transparency
                    )
                )
                add(
                    Triple(
                        NoiseControlMode.ADAPTIVE,
                        R.string.adaptive,
                        R.drawable.ic_adaptive
                    )
                )
                add(
                    Triple(
                        NoiseControlMode.NOISE_CANCELLATION,
                        R.string.noise_cancellation,
                        R.drawable.ic_noise_cancellation
                    )
                )
            }

            val selectedMode = NoiseControlMode.entries[(noiseControlModeValue - 1).coerceIn(0, NoiseControlMode.entries.lastIndex)]

            Column(modifier = modifier) {
                if (showLabels) {
                    Text(
                        text = stringResource(R.string.noise_control),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmallEmphasized,
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .padding(top = 4.dp, bottom = 12.dp)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                ) {
                    options.forEachIndexed { index, (mode, labelRes, iconRes) ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            ToggleButton(
                                checked = selectedMode == mode,
                                onCheckedChange = {
                                    if (it) {
                                        onNoiseControlModeChanged(mode.ordinal + 1)
                                    }
                                },
                                shapes = when (index) {
                                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                                },
                                colors = ToggleButtonDefaults.colors(containerColor = if (showLabels) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.secondaryContainer),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    bitmap = ImageBitmap.imageResource(iconRes),
                                    contentDescription = null,
                                    modifier = Modifier.size(42.dp),
                                    tint = if (selectedMode == mode) MaterialTheme.colorScheme.onPrimary else if (showLabels) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }

                            if (showLabels) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = stringResource(labelRes),
                                    style = MaterialTheme.typography.labelSmall,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }
        }

        DesignSystem.Apple -> {
            val isDarkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
            val backgroundColor = if (isDarkTheme) (if (showLabels) Color(0xFF1C1C1E) else MaterialTheme.colorScheme.surfaceContainerLow) else Color(0xFFE3E3E8)

            val density = LocalDensity.current

            val backdrop = rememberLayerBackdrop()

            val iconRowHeight = remember { mutableStateOf(0.dp) }
            val width = remember { mutableStateOf(0.dp) }

            val currentMode by rememberUpdatedState(noiseControlModeValue)
            val currentStrength by rememberUpdatedState((requestedAdaptiveStrength ?: adaptiveStrength).coerceIn(0, 100))
            val currentShowOff by rememberUpdatedState(showOffListeningMode)
            val currentWidth by rememberUpdatedState(width.value.value)

            val offset = remember { Animatable(-1f) }
            var isInitialized by remember { mutableStateOf(false) }

            val offOffset = if (currentShowOff  && currentMode != 4) 1f else 0f

            val targetModeWidth = currentWidth / if (currentMode == 4) (5f + offOffset) else (3f + offOffset)
            val targetAdaptiveWidth = targetModeWidth * if (currentMode == 4) 3f else 1f
            val targetOffWidth = if (currentShowOff && currentMode != 4) targetModeWidth else 0f

            val modeWidth by animateFloatAsState(
                targetValue = targetModeWidth,
                animationSpec = if (!isInitialized) snap() else spring(),
            )
            val adaptiveWidth by animateFloatAsState(
                targetValue = targetAdaptiveWidth,
                animationSpec = if (!isInitialized) snap() else spring(),
            )
            val offWidth by animateFloatAsState(
                targetValue = targetOffWidth,
                animationSpec = if (!isInitialized) snap() else spring(),
            )

            val currentModeWidth by rememberUpdatedState(modeWidth)
            val currentTransparencyStart by rememberUpdatedState(offWidth)
            val currentAdaptiveStart by rememberUpdatedState(offWidth + modeWidth)
            val currentAncStart by rememberUpdatedState(offWidth + modeWidth * if (currentMode == 4) 4f else 2f)

            val targetTransparencyStart by rememberUpdatedState(targetOffWidth)
            val targetAdaptiveStart by rememberUpdatedState(targetOffWidth + targetModeWidth)
            val targetAncStart by rememberUpdatedState(targetOffWidth + targetModeWidth * if (currentMode == 4) 4f else 2f)
            val currentTargetModeWidth by rememberUpdatedState(targetModeWidth)

            val velocityTracker = VelocityTracker()
            val tapProgress = remember { Animatable(0f) }
            val stretch = remember { Animatable(0f) }

            val animationCoroutineScope = rememberCoroutineScope()

            var isDragging by remember { mutableStateOf(false) }
            var dragOffset by remember { mutableFloatStateOf(0f) }

            LaunchedEffect(width.value, noiseControlModeValue, adaptiveStrength, requestedAdaptiveStrength, showOffListeningMode) {
                if (!isDragging && width.value > 0.dp) {
                    val targetOffset = when (noiseControlModeValue) {
                        1 -> 0f
                        3 -> targetTransparencyStart
                        4 -> targetAdaptiveStart + currentStrength * (targetAncStart - targetAdaptiveStart - currentTargetModeWidth) / 100f
                        2 -> targetAncStart
                        else -> 0f
                    }
                    if (!isInitialized) {
                        offset.snapTo(targetOffset)
                    } else {
                        offset.animateTo(targetOffset)
                    }
                }
            }

            LaunchedEffect(width.value) {
                if (width.value > 0.dp && !isInitialized) {
                    delay(50.milliseconds)
                    isInitialized = true
                }
            }

            val targetMode = remember { mutableIntStateOf(0) }
            val targetStrength = remember { mutableIntStateOf(0) }

            LaunchedEffect(Unit) {
                launch {
                    snapshotFlow { offset.value }
                        .map { currentOffset ->
                            if (currentWidth == 0f) return@map null
                            val thumbCenter = currentOffset + (currentModeWidth / 2f)

                            if (currentShowOff) {
                                when {
                                    thumbCenter < currentTransparencyStart -> 1
                                    thumbCenter < currentAdaptiveStart -> 3
                                    thumbCenter < currentAncStart -> 4
                                    else -> 2
                                }
                            } else {
                                when {
                                    thumbCenter < currentAdaptiveStart -> 3
                                    thumbCenter < currentAncStart -> 4
                                    else -> 2
                                }
                            }
                        }
                        .distinctUntilChanged()
                        .collect { mode ->
                            mode?.let {
                                targetMode.intValue = it
                                if (isDragging) {
                                    currentModeChanged(it)
                                }
                            }
                        }
                }

                launch {
                    snapshotFlow { offset.value }
                        .map { currentOffset ->
                            if (currentMode == 4) {
                                val maxTravel = currentAncStart - currentAdaptiveStart - currentModeWidth
                                if (maxTravel <= 0f) 0 else {
                                    ((currentOffset - currentAdaptiveStart) / maxTravel * 100f)
                                        .roundToInt()
                                        .coerceIn(0, 100)
                                }
                            } else null
                        }
                        .distinctUntilChanged()
                        .collect { currentAdaptiveStrength ->
                            targetStrength.intValue = currentAdaptiveStrength ?: -1
                            if (isDragging && currentAdaptiveStrength != null) {
                                currentStrengthChanged(currentAdaptiveStrength)
                            }
                        }
                }
            }

            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .alpha(if (width.value > 0.dp) 1f else 0f)
            ) {
                if (LocalDebugMode.current) {
                    Text(
                        text = "[$noiseControlModeValue][${targetMode.intValue}][strength = ${targetStrength.intValue}]\nmodeWidth = ${modeWidth}\noffset = ${offset.value}\ntransparencyStart = $currentTransparencyStart adaptiveStart = $currentAdaptiveStart ancStart = $currentAncStart",
                        color = Color.Red.copy(0.85f),
                        modifier = Modifier.zIndex(999f),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .layerBackdrop(backdrop)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .onGloballyPositioned {
                                with(density) {
                                    iconRowHeight.value = it.size.height.toDp()
                                    width.value = it.size.width.toDp()
                                }
                            }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .pointerInput(Unit) {
                                    detectTapGestures {
                                        currentModeChanged(1)
                                    }
                                }
                                .width(offWidth.dp)
                                .background(
                                    backgroundColor,
                                    RoundedCornerShape(
                                        topStartPercent = 100,
                                        bottomStartPercent = 100
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            this@Row.AnimatedVisibility(
                                visible = noiseControlModeValue != 1,
                                enter = fadeIn(),
                                exit = fadeOut()
                            ){
                                Icon(
                                    bitmap = ImageBitmap.imageResource(R.drawable.ic_noise_cancellation),
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(modeWidth.dp)
                                .background(
                                    backgroundColor,
                                    RoundedCornerShape(
                                        topStartPercent = if (showOffListeningMode && currentMode != 4) 0 else 100,
                                        bottomStartPercent = if (showOffListeningMode && currentMode != 4) 0 else 100
                                    )
                                )
                                .pointerInput(Unit) {
                                    detectTapGestures {
                                        currentModeChanged(3)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            this@Row.AnimatedVisibility(
                                visible = noiseControlModeValue != 3,
                                enter = fadeIn(),
                                exit = fadeOut()
                            ) {
                                Icon(
                                    bitmap = ImageBitmap.imageResource(R.drawable.ic_transparency),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(36.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(adaptiveWidth.dp)
                                .pointerInput(Unit) {
                                    detectTapGestures { tapOffset ->
                                        if (currentMode != 4) {
                                            currentModeChanged(4)
                                        } else {
                                            val tapXInDp =
                                                with(density) { tapOffset.x.toDp().value }

                                            val maxTravel =
                                                targetAncStart - targetAdaptiveStart - currentTargetModeWidth

                                            if (maxTravel > 0f) {
                                                val thumbLeftEdge =
                                                    tapXInDp - (currentTargetModeWidth / 2f)

                                                val newStrength =
                                                    ((thumbLeftEdge / maxTravel) * 100f)
                                                        .roundToInt()
                                                        .coerceIn(0, 100)

                                                currentStrengthChanged(newStrength)
                                            }
                                        }
                                    }
                                }
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(backgroundColor)
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .background(
                                        if (noiseControlModeValue == 4) MaterialTheme.colorScheme.surfaceDim else Color.Transparent,
                                        RoundedCornerShape(percent = 100)
                                    )

                                    .zIndex(999f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AnimatedVisibility(
                                    visible = noiseControlModeValue == 4,
                                    enter = fadeIn(),
                                    exit = fadeOut(),
                                    modifier = Modifier.padding(start = 16.dp)
                                ) {
                                    Icon(
                                        imageVector = LocalIcons.current.CircleDotted,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurface,
                                    )
                                }

                                Spacer(modifier = Modifier.weight(1f))

                                AnimatedVisibility(
                                    visible = noiseControlModeValue != 4,
                                    enter = fadeIn(),
                                    exit = fadeOut(),
                                ) {
                                    Icon(
                                        bitmap = ImageBitmap.imageResource(R.drawable.ic_adaptive),
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(36.dp),
                                        tint = MaterialTheme.colorScheme.onSurface,
                                    )
                                }

                                Spacer(modifier = Modifier.weight(1f))

                                AnimatedVisibility(
                                    visible = noiseControlModeValue == 4,
                                    enter = fadeIn(),
                                    exit = fadeOut(),
                                    modifier = Modifier.padding(end = 16.dp)
                                ) {
                                    Icon(
                                        imageVector = LocalIcons.current.Circle,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(modeWidth.dp)
                                .background(
                                    backgroundColor,
                                    RoundedCornerShape(topEndPercent = 100, bottomEndPercent = 100)
                                )
                                .pointerInput(Unit) {
                                    detectTapGestures {
                                        currentModeChanged(2)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            this@Row.AnimatedVisibility(
                                visible = noiseControlModeValue != 2,
                                enter = fadeIn(),
                                exit = fadeOut()
                            ) {
                                Icon(
                                    bitmap = ImageBitmap.imageResource(R.drawable.ic_noise_cancellation),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(36.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    if (showLabels) {
                        Row (
                            modifier = Modifier
                                .background(
                                    @Suppress("KotlinConstantConditions")
                                    if (showLabels) MaterialTheme.colorScheme.surfaceContainer
                                    else MaterialTheme.colorScheme.surfaceContainerHigh
                                ), // else backdrop doesn't work
                        ) {
                            if (showOffListeningMode) {
                                Text(
                                    text = stringResource(R.string.off),
                                    style = MaterialTheme.typography.labelSmall,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.width(offWidth.dp),
                                    maxLines = 1,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                text = stringResource(R.string.transparency),
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.width(modeWidth.dp),
                                maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.adaptive),
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.width(adaptiveWidth.dp),
                                maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.noise_cancellation),
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.width(modeWidth.dp),
                                minLines = 2,
                                maxLines = 2,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .graphicsLayer {
                            translationX = with(density) { offset.value.coerceAtLeast(0f).dp.toPx() }
                            scaleX = 1f + tapProgress.value * 0.2f + (stretch.value * 0.6f)
                            scaleY = 1f + tapProgress.value * 0.2f - (stretch.value * 0.3f)
                        }
                        .width(modeWidth.dp)
                        .height(iconRowHeight.value)
                        .testTag("noise-control-thumb")
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    dragOffset = offset.value
                                    isDragging = true
                                    velocityTracker.resetTracking()
                                    animationCoroutineScope.launch {
                                        tapProgress.animateTo(1f)
                                    }
                                },
                                onDragEnd = {
                                    val thumbCenter = dragOffset + currentModeWidth / 2f
                                    val finalMode = when {
                                        currentShowOff && thumbCenter < currentTransparencyStart -> 1
                                        thumbCenter < currentAdaptiveStart -> 3
                                        thumbCenter < currentAncStart -> 4
                                        else -> 2
                                    }
                                    val finalStrength = if (currentMode == 4 && finalMode == 4) {
                                        val travel = currentAncStart - currentAdaptiveStart - currentModeWidth
                                        if (travel <= 0f) currentStrength else
                                            ((dragOffset - currentAdaptiveStart) / travel * 100f).roundToInt().coerceIn(0, 100)
                                    } else currentStrength
                                    // The final offset may not reach snapshotFlow before the gesture ends.
                                    if (finalMode != currentMode) currentModeChanged(finalMode)
                                    if (currentMode == 4 && finalMode == 4) currentStrengthChanged(finalStrength)
                                    isDragging = false

                                    val finalTarget = when (finalMode) {
                                        1 -> 0f
                                        3 -> targetTransparencyStart
                                        4 -> targetAdaptiveStart + finalStrength * (targetAncStart - targetAdaptiveStart - currentTargetModeWidth) / 100f
                                        2 -> targetAncStart
                                        else -> 0f
                                    }

                                    animationCoroutineScope.launch {
                                        stretch.animateTo(
                                            targetValue = 0f,
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioHighBouncy,
                                                stiffness = Spring.StiffnessMedium
                                            )
                                        )
                                    }
                                    animationCoroutineScope.launch { tapProgress.animateTo(0f) }
                                    animationCoroutineScope.launch { offset.animateTo(finalTarget) }
                                    velocityTracker.resetTracking()
                                },
                                onDragCancel = {
                                    isDragging = false
                                    animationCoroutineScope.launch {
                                        stretch.animateTo(
                                            targetValue = 0f,
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioHighBouncy,
                                                stiffness = Spring.StiffnessMedium
                                            )
                                        )
                                    }
                                    val restoredOffset = when (currentMode) {
                                        1 -> 0f
                                        3 -> targetTransparencyStart
                                        4 -> targetAdaptiveStart + currentStrength * (targetAncStart - targetAdaptiveStart - currentTargetModeWidth) / 100f
                                        2 -> targetAncStart
                                        else -> 0f
                                    }
                                    animationCoroutineScope.launch { offset.animateTo(restoredOffset) }
                                    animationCoroutineScope.launch { tapProgress.animateTo(0f) }
                                    velocityTracker.resetTracking()
                                }
                            ) { change, dragAmount ->
                                change.consume()
                                velocityTracker.addPosition(change.uptimeMillis, change.position)

                                val velocityPx = velocityTracker.calculateVelocity().x
                                val rawSensitivity = sqrt(abs(velocityPx) / 50f)

                                val targetStretch = (rawSensitivity / 10f).coerceIn(0f, 1f)

                                animationCoroutineScope.launch {
                                    stretch.animateTo(
                                        targetValue = targetStretch,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMedium
                                        )
                                    )
                                }

                                val newOffset = dragOffset + dragAmount.toDp().value
                                val maxTravel = (currentWidth - currentModeWidth).coerceAtLeast(0f)
                                val safeOffset = newOffset.coerceIn(0f, maxTravel)
                                dragOffset = safeOffset

                                animationCoroutineScope.launch {
                                    offset.snapTo(safeOffset)
                                }
                            }
                        }
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { RoundedCornerShape(percent = 100) },
                            effects = {
                                lens(
                                    refractionHeight = 12.dp.toPx(),
                                    refractionAmount = 36.dp.toPx(),
                                    depthEffect = true,
                                    chromaticAberration = true
                                )
                            },
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    val imageBitmap = ImageBitmap.imageResource(
                        when (noiseControlModeValue) {
                            1,2 -> R.drawable.ic_noise_cancellation
                            3 -> R.drawable.ic_transparency
                            4 -> R.drawable.ic_adaptive
                            else -> R.drawable.ic_adaptive
                        }
                    )
                    AnimatedContent(
                        imageBitmap
                    ) {
                        Icon(
                            bitmap = it,
                            contentDescription = null,
                            modifier = Modifier
                                .size(36.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun AdaptiveAudioStrengthSlider(
    adaptiveStrength: Int,
    requestedAdaptiveStrength: Int?,
    onAdaptiveStrengthChanged: (Int) -> Unit,
    enabled: Boolean
) {
    StyledSlider(
        value = 100f - (requestedAdaptiveStrength ?: adaptiveStrength).coerceIn(0, 100),
        onValueChange = { onAdaptiveStrengthChanged((100f - it).roundToInt().coerceIn(0, 100)) },
        valueRange = 0f..100f,
        snapPoints = listOf(0f, 50f, 100f),
        startImageVector = LocalIcons.current.SpeakerMin,
        endImageVector = LocalIcons.current.SpeakerMax,
        independent = true,
        description = stringResource(R.string.adaptive_audio_description),
        enabled = enabled
    )
}

@Preview
@Composable
fun NoiseControlSettingsPreview() {
    val showOffListeningMode = remember { mutableStateOf(false) }
    val mode = remember { mutableIntStateOf(2) }
    val strength = remember { mutableIntStateOf(30) }
    val debug = remember { mutableStateOf(false) }

    CompositionLocalProvider(
        LocalDebugMode provides debug.value
    ) {
        LibrePodsTheme(
            darkTheme = true,
            designSystem = DesignSystem.Apple,
            fontSettings = FontSettings(fontFamilyOption = FontFamilyOption.Inter)
        ) {
            Box(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .fillMaxSize()
                    .padding(vertical = 32.dp, horizontal = 16.dp)
            ) {
                Box(
                    modifier = Modifier.align(Alignment.Center)
                ){
                    NoiseControlSettings(
                        showOffListeningMode = showOffListeningMode.value,
                        noiseControlModeValue = mode.intValue,
                        onNoiseControlModeChanged = { mode.intValue = it },
                        adaptiveStrength = strength.intValue,
                        onAdaptiveStrengthChanged = { strength.intValue = it }
                    )
                }

                Column(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    verticalArrangement = Arrangement.Center
                ) {
                    Row {
                        StyledButton(onClick = { debug.value = !debug.value }) {
                            Text(
                                text = "debug (${debug.value})",
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        StyledButton(onClick = {
                            showOffListeningMode.value = !showOffListeningMode.value
                        }) {
                            Text(
                                text = "allow off (${showOffListeningMode.value})",
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(text = "trigger external changes (other device or stem press):", color = MaterialTheme.colorScheme.onSurface)

                    Spacer(modifier = Modifier.height(4.dp))

                    Row {
                        AnimatedVisibility(
                            visible = showOffListeningMode.value,
                            enter = expandHorizontally { 0 } + fadeIn(),
                            exit = fadeOut() + shrinkHorizontally { 0 }
                        ) {
                            StyledButton(onClick = { mode.intValue = 1 }) {
                                Text(text = "off", color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                        StyledButton(onClick = { mode.intValue = 3 }) {
                            Text(text = "transparency", color = MaterialTheme.colorScheme.onSurface)
                        }
                        StyledButton(onClick = { mode.intValue = 4 }) {
                            Text(text = "adaptive", color = MaterialTheme.colorScheme.onSurface)
                        }
                        StyledButton(onClick = { mode.intValue = 2 }) {
                            Text(text = "anc", color = MaterialTheme.colorScheme.onSurface)
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    val sliderValue = remember { mutableFloatStateOf(strength.intValue.toFloat()) }

                    LaunchedEffect(strength.intValue) {
                        sliderValue.floatValue = strength.intValue.toFloat()
                    }

                    LaunchedEffect(Unit) {
                        snapshotFlow { sliderValue.floatValue.toInt() }
                            .debounce(50.milliseconds)
                            .collect {
                                sliderValue.floatValue = it.toFloat()
                                strength.intValue = it
                            }
                    }

                    StyledSlider(
                        label = "adaptive strength",
                        value = sliderValue.floatValue,
                        onValueChange = { sliderValue.floatValue = it },
                        valueRange = 0f..100f,
                        independent = true
                    )
                }
            }
        }
    }
}
