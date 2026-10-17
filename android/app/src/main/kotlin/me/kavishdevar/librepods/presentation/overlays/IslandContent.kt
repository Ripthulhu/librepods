package me.kavishdevar.librepods.presentation.overlays

import android.util.Log
import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.app.IslandBatteryStyle
import me.kavishdevar.librepods.data.app.IslandSettings
import me.kavishdevar.librepods.core.devices.AirPodsModel
import me.kavishdevar.librepods.core.devices.AirPodsSpecs
import me.kavishdevar.librepods.core.devices.Battery
import me.kavishdevar.librepods.core.devices.BatteryComponent
import me.kavishdevar.librepods.data.app.resolve
import me.kavishdevar.librepods.presentation.components.apple.NoiseControlSettings
import me.kavishdevar.librepods.presentation.icons.LocalIcons
import java.util.concurrent.TimeUnit

internal fun islandInitialSize(settings: IslandSettings, state: IslandState, motionEnabled: Boolean,
    cameraCapsuleWidth: Float = settings.compactHeight): Pair<Float, Float> = when {
    state == IslandState.CLOSE -> minOf(settings.compactWidth, maxOf(settings.compactHeight, cameraCapsuleWidth)) to settings.compactHeight
    motionEnabled -> minOf(settings.compactWidth, maxOf(settings.compactHeight, cameraCapsuleWidth)) to settings.compactHeight
    state == IslandState.EXPANDED -> settings.expandedWidth to settings.expandedHeight
    else -> settings.compactWidth to settings.compactHeight
}

/** The shell reveals one fixed endpoint layout; text and artwork never reflow during a fold. */
@Composable
internal fun IslandPayload(
    widthPx: Int,
    heightPx: Int,
    revealProgress: Float,
    cameraSafeTopPx: Float = 0f,
    cameraGapPx: Float = 0f,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val progress = revealProgress.coerceIn(0f, 1f)
    val lift = with(LocalDensity.current) { 8.dp.toPx() } * (1f - progress)
    Layout(content = content, modifier = modifier.fillMaxSize().drawWithContent {
        clipRect(top = cameraSafeTopPx.coerceIn(0f, size.height)) {
            if (cameraGapPx > 0f) {
                clipRect(left = (size.width - cameraGapPx) / 2f,
                    right = (size.width + cameraGapPx) / 2f, clipOp = ClipOp.Difference) { this@drawWithContent.drawContent() }
            } else this@drawWithContent.drawContent()
        }
    }) { measurables, constraints ->
        val child = measurables.single().measure(Constraints.fixed(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1)))
        val fittingBody = cameraSafeTopPx > 0f
        val bodyScale = if (fittingBody) minOf(constraints.maxWidth / child.width.toFloat(),
            (constraints.maxHeight - cameraSafeTopPx) / (child.height - cameraSafeTopPx).coerceAtLeast(1f))
            .coerceIn(0f, 1f) else 0.96f + 0.04f * progress
        layout(constraints.maxWidth, constraints.maxHeight) {
            child.placeRelativeWithLayer((constraints.maxWidth - child.width) / 2, 0) {
                transformOrigin = TransformOrigin(0.5f, if (fittingBody) cameraSafeTopPx / child.height else 0f)
                scaleX = bodyScale
                scaleY = bodyScale
                translationY = if (fittingBody) 0f else -lift
            }
        }
    }
}

@Composable
fun IslandContent(
    name: String,
    type: IslandType,
    batteryLevel: Int,
    reversed: Boolean,
    otherDeviceName: String?,

    islandSettings: IslandSettings,
    islandState: IslandState,

    openApp: () -> Unit = {},
    changeState: (IslandState, Boolean) -> Unit = { _, _ ->},
    onClosed: () -> Unit = {},
    resetTimer: () -> Unit = {},

    showOffListeningMode: Boolean,
    noiseControlModeValue: Int,
    onNoiseControlModeChanged: (Int) -> Unit,
    adaptiveStrength: Int = 0,
    onAdaptiveStrengthChanged: (Int) -> Unit,
    requestedAdaptiveStrength: Int? = null,
    model: AirPodsModel = AirPodsModel.UNKNOWN,
    batteries: Set<Battery> = emptySet(),
    batteryNow: Long = System.currentTimeMillis(),
    paired: Boolean = false,
    nearby: Boolean = false,
    cameraPaddingPx: Int = 0,
    cameraCapsuleWidthPx: Float = islandSettings.compactHeight,
    statusText: String? = null,
    transitionGeneration: Long = 0L,
    onTransitionFinished: (Long, IslandState) -> Unit = { _, _ -> },
) {
    val density = LocalDensity.current
    val cells = islandBatteryCells(model, batteries, batteryNow)
    val compactCell = cells.filter { it.component != BatteryComponent.CASE }.filter { it.live && it.level != null }
        .minByOrNull { it.level!! } ?: cells.firstOrNull { it.component != BatteryComponent.CASE && it.level != null }
    val displayBatteryLevel = if (paired) compactCell?.level else batteryLevel
    val batteryLive = !paired || compactCell?.live == true
    val spec = AirPodsSpecs.getSpec(model)

    val initialMotionEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
    val initialSize = remember { islandInitialSize(islandSettings, islandState, initialMotionEnabled, cameraCapsuleWidthPx) }
    val animWidth = remember { Animatable(initialSize.first) }
    val animHeight = remember { Animatable(initialSize.second) }
    val animRadius = remember { Animatable(if (!initialMotionEnabled && islandState == IslandState.EXPANDED) islandSettings.expandedBorderRadius else islandSettings.compactBorderRadius) }
    val animBorderWidth = remember { Animatable(if (!initialMotionEnabled && islandState == IslandState.EXPANDED) islandSettings.expandedBorderWidth else islandSettings.compactBorderWidth) }
    var contentState by remember { mutableStateOf(if (islandState == IslandState.COMPACT) IslandState.COMPACT else IslandState.EXPANDED) }
    val expandedContent = contentState == IslandState.EXPANDED
    val payloadWidth = if (expandedContent) islandSettings.expandedWidth else islandSettings.compactWidth
    val payloadHeight = if (expandedContent) islandSettings.expandedHeight else islandSettings.compactHeight
    val capsuleWidth = minOf(islandSettings.compactWidth, maxOf(islandSettings.compactHeight, cameraCapsuleWidthPx))
    val revealProgress = if (expandedContent)
        ((animHeight.value - islandSettings.compactHeight) / (payloadHeight - islandSettings.compactHeight).coerceAtLeast(1f)).coerceIn(0f, 1f)
    else ((animWidth.value - capsuleWidth) / (payloadWidth - capsuleWidth).coerceAtLeast(1f)).coerceIn(0f, 1f)
    val finished by rememberUpdatedState(onTransitionFinished)
    val closed by rememberUpdatedState(onClosed)

    val springSpec = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessLow
    )

    LaunchedEffect(
        islandState,
        transitionGeneration,
        cameraCapsuleWidthPx,
        islandSettings.expandedWidth,
        islandSettings.expandedHeight,
        islandSettings.compactWidth,
        islandSettings.compactHeight,
        islandSettings.expandedBorderRadius,
        islandSettings.compactBorderRadius,
        islandSettings.expandedBorderWidth,
        islandSettings.compactBorderWidth
    ) {
        val (targetW, targetH) = when (islandState) {
            IslandState.EXPANDED -> islandSettings.expandedWidth to islandSettings.expandedHeight
            IslandState.COMPACT -> islandSettings.compactWidth to islandSettings.compactHeight
            IslandState.CLOSE -> capsuleWidth to islandSettings.compactHeight
        }

        val targetRadius = when (islandState) {
            IslandState.EXPANDED -> islandSettings.expandedBorderRadius
            IslandState.COMPACT -> islandSettings.compactBorderRadius
            IslandState.CLOSE -> islandSettings.compactBorderRadius
        }

        val targetBorderWidth = when (islandState) {
            IslandState.EXPANDED -> islandSettings.expandedBorderWidth
            IslandState.COMPACT -> islandSettings.compactBorderWidth
            IslandState.CLOSE -> islandSettings.compactBorderWidth
        }

        if (islandState == IslandState.EXPANDED) contentState = IslandState.EXPANDED
        val opening = targetW > animWidth.value || targetH > animHeight.value
        val motion = tween<Float>(if (opening) 320 else 220, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
        val motionEnabled = ValueAnimator.areAnimatorsEnabled()
        val wJob = launch { if (motionEnabled) animWidth.animateTo(targetW, motion) else animWidth.snapTo(targetW) }
        val hJob = launch { if (motionEnabled) animHeight.animateTo(targetH, motion) else animHeight.snapTo(targetH) }
        val rJob = launch { if (motionEnabled) animRadius.animateTo(targetRadius, motion) else animRadius.snapTo(targetRadius) }
        val bJob = launch { if (motionEnabled) animBorderWidth.animateTo(targetBorderWidth, motion) else animBorderWidth.snapTo(targetBorderWidth) }

        wJob.join()
        hJob.join()
        rJob.join()
        bJob.join()

        if (islandState == IslandState.COMPACT) contentState = IslandState.COMPACT
        finished(transitionGeneration, islandState)
        if (islandState == IslandState.CLOSE) closed()
    }

    val scale = remember { Animatable(0f) }

    val widthDp = with(density) { animWidth.value.coerceAtLeast(1f).toDp() * (1f + scale.value * if (islandState == IslandState.EXPANDED) 0.01f else 0.1f) }
    val heightDp = with(density) { animHeight.value.coerceAtLeast(1f).toDp() * (1f + scale.value * if (islandState == IslandState.EXPANDED) 0.01f else 0.05f) }
    val borderWidthDp = with(density) { animBorderWidth.value.toDp() }

    val padding = if (expandedContent) 16.dp else 8.dp
    val compactWingWidth = (with(density) { ((islandSettings.compactWidth - capsuleWidth) / 2f).toDp() } - 8.dp)
        .coerceAtLeast(0.dp)
    val compactBatterySize = minOf(compactWingWidth, with(density) { islandSettings.compactHeight.toDp() } - 16.dp)
        .coerceAtLeast(0.dp)

    val backgroundColor = if (paired && islandSettings.expandedBackgroundColor == IslandSettings().expandedBackgroundColor)
        Color.Black else islandSettings.expandedBackgroundColor.resolve()

    val backgroundColorWithAlpha = animateColorAsState(
        targetValue = if (expandedContent) backgroundColor.copy(alpha = islandSettings.expandedBackgroundAlpha) else Color.Black,
        animationSpec = tween(100)
    )

    val textColor = if (!expandedContent || backgroundColor.luminance() < 0.5f) Color.White else contentColorFor(backgroundColor)
    val shape = RoundedCornerShape(with(density) { animRadius.value.toDp() } )

    val typeText = statusText ?: when (type) {
        IslandType.CONNECTED -> stringResource(if (nearby) R.string.island_nearby else R.string.island_connected_text)
        IslandType.TAKING_OVER -> stringResource(R.string.island_taking_over_text)
        IslandType.MOVED_TO_REMOTE -> stringResource(R.string.island_moved_to_remote_text)
        IslandType.MOVED_TO_OTHER_DEVICE -> {
            if (otherDeviceName.isNullOrEmpty()) {
                Log.w("IslandWindow", "Other device name is null or empty for MOVED_TO_OTHER_DEVICE type")
            }
            if (reversed) {
                stringResource(R.string.island_moved_to_other_device_reversed_text)
            } else {
                stringResource(R.string.island_moved_to_other_device_text, otherDeviceName ?: "")
            }
        }
    }

    val animationCoroutineScope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .requiredSize(width = widthDp, height = heightDp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(backgroundColorWithAlpha.value, shape)
                .border(
                    width = borderWidthDp,
                    color = textColor.copy(if (borderWidthDp > 0.dp) 0.2f else 0f),
                    shape = shape
                )
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent()
                            resetTimer()
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount ->
                        if (dragAmount > 0) changeState(IslandState.EXPANDED, true)
                        else changeState(
                            if (islandSettings.keepCompactVisible) IslandState.COMPACT else IslandState.CLOSE,
                            true
                        )
                    }
                }
        ) {
            key(contentState) {
                IslandPayload(payloadWidth.toInt(), payloadHeight.toInt(), revealProgress,
                    cameraSafeTopPx = if (expandedContent) cameraPaddingPx.toFloat() else 0f,
                    cameraGapPx = if (expandedContent) 0f else (cameraCapsuleWidthPx - with(density) { 12.dp.toPx() }).coerceAtLeast(0f),
                    modifier = if (islandState == contentState) Modifier else Modifier.clearAndSetSemantics { }
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        }) {
                    Column(
                        modifier = Modifier.fillMaxSize()
                            .padding(start = padding, end = padding, bottom = padding,
                                top = if (expandedContent) maxOf(padding, with(density) { cameraPaddingPx.toDp() }) else padding),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .pointerInput(islandState) {
                                    detectTapGestures(
                                        onPress = {
                                            scale.animateTo(1f, springSpec)
                                            tryAwaitRelease()
                                            scale.animateTo(0f)
                                        },
                                        onTap = {
                                            when (islandState) {
                                                IslandState.EXPANDED -> {
                                                    if (islandSettings.expandedOpenAppOnTap) openApp()
                                                    else if (islandSettings.keepCompactVisible) changeState(
                                                        IslandState.COMPACT,
                                                        false
                                                    )
                                                }

                                                IslandState.COMPACT -> {
                                                    if (islandSettings.compactOpenAppOnTap) openApp()
                                                    else changeState(IslandState.EXPANDED, true)
                                                }

                                                else -> {}
                                            }
                                        },
                                        onLongPress = {
                                            animationCoroutineScope.launch { scale.animateTo(0f) }
                                            when (islandState) {
                                                IslandState.EXPANDED -> {
                                                    if (!islandSettings.expandedOpenAppOnTap) openApp()
                                                    else if (islandSettings.keepCompactVisible) changeState(
                                                        IslandState.COMPACT,
                                                        false
                                                    )
                                                }

                                                IslandState.COMPACT -> {
                                                    if (!islandSettings.compactOpenAppOnTap) openApp()
                                                    else changeState(IslandState.EXPANDED, true)
                                                }

                                                else -> {}
                                            }
                                        }
                                    )
                                },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val useMonochrome = when (contentState) {
                                IslandState.EXPANDED -> islandSettings.expandedUseMonochrome
                                IslandState.COMPACT -> islandSettings.compactUseMonochrome
                                IslandState.CLOSE -> islandSettings.compactUseMonochrome
                            }
                            AnimatedContent(
                                targetState = useMonochrome,
                                modifier = if (expandedContent) Modifier else Modifier.width(compactWingWidth)
                                    .fillMaxHeight().testTag("island-compact-artwork"),
                                contentAlignment = if (expandedContent) Alignment.TopStart else Alignment.Center,
                            ) {
                                if (it) {
                                    Icon(
                                        imageVector = LocalIcons.current.fromName(spec.genericIconName) ?: LocalIcons.current.Headphones,
                                        contentDescription = null,
                                        tint = textColor,
                                        modifier = Modifier.fillMaxHeight(0.8f)
                                    )
                                } else {
                                    Image(
                                        bitmap = ImageBitmap.imageResource(spec.primaryImageRes),
                                        contentDescription = null,
                                        modifier = Modifier
                                            .heightIn(max = 64.dp)
                                            .fillMaxHeight(0.8f)
                                    )
                                }
                            }

                            if (expandedContent) Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp)
                            ) {
                                    AnimatedVisibility(visible = islandSettings.fontSize > 15) {
                                        Text(
                                            text = typeText,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontSize = islandSettings.fontSize.sp * 0.65f,
                                            fontWeight = FontWeight.SemiBold,
                                            color = textColor.copy(0.6f),
                                        )
                                    }

                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontSize = islandSettings.fontSize.sp,
                                        color = textColor,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                            }

                            val currentBatteryStyle = when (contentState) {
                                IslandState.EXPANDED -> islandSettings.expandedBatteryStyle
                                IslandState.COMPACT -> islandSettings.compactBatteryStyle
                                IslandState.CLOSE -> islandSettings.compactBatteryStyle
                            }

                            val showRing = currentBatteryStyle == IslandBatteryStyle.CIRCLE || currentBatteryStyle == IslandBatteryStyle.CIRCLE_TEXT
                            val showText = currentBatteryStyle == IslandBatteryStyle.TEXT || currentBatteryStyle == IslandBatteryStyle.CIRCLE_TEXT

                            if (!paired || !expandedContent) BoxWithConstraints(
                                modifier = if (!expandedContent) Modifier.size(compactBatterySize).testTag("island-compact-battery") else Modifier
                                    .heightIn(max = 64.dp)
                                    .fillMaxHeight(0.8f)
                                    .aspectRatio(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                val isDarkTheme = backgroundColor.luminance() < 0.5f
                                val batteryColor =
                                    if (!batteryLive || displayBatteryLevel == null) textColor.copy(alpha = 0.5f)
                                    else if (displayBatteryLevel > 25) if (isDarkTheme) Color(0xFF2ED158) else Color(0xFF35C759)
                                    else if (isDarkTheme) Color(0xFFFC4244) else Color(0xFFfe373C)

                                this@Row.AnimatedVisibility(
                                    visible = showRing,
                                    enter = fadeIn(animationSpec = tween(180, delayMillis = 80)),
                                    exit = fadeOut(animationSpec = tween(100)),
                                ) {
                                    val strokeWidthPx = with(LocalDensity.current) { (maxHeight / 10f).toPx() }

                                    val progress = (displayBatteryLevel ?: 0) / 100f

                                    Canvas(
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        val startAngle = -90f
                                        val stroke = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)

                                        drawArc(
                                            color = batteryColor.copy(0.2f),
                                            startAngle = 0f,
                                            sweepAngle = 360f,
                                            useCenter = false,
                                            style = stroke
                                        )

                                        drawArc(
                                            color = batteryColor,
                                            startAngle = startAngle,
                                            sweepAngle = 360f * progress,
                                            useCenter = false,
                                            style = stroke
                                        )
                                    }
                                }
                                this@Row.AnimatedVisibility(
                                    visible = showText,
                                    enter = fadeIn(animationSpec = tween(180, delayMillis = 80)),
                                    exit = fadeOut(animationSpec = tween(100)),
                                ) {
                                    val fontSize = with(LocalDensity.current) {
                                        (maxHeight / 2.5f).toSp()
                                    }
                                    Text(
                                        text = displayBatteryLevel?.toString() ?: "—",
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontSize = fontSize,
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        color = batteryColor,
                                        maxLines = 1,
                                        softWrap = false,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }

                        if (paired && expandedContent && islandSettings.expandedBatteryStyle != IslandBatteryStyle.NONE) {
                            val commonAge = islandCommonAge(cells)
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                cells.forEach { cell ->
                                    IslandBatteryIndicator(cell, islandSettings.expandedBatteryStyle, textColor, Modifier.weight(1f),
                                        showAge = commonAge == null)
                                }
                            }
                            if (commonAge != null) Text(stringResource(R.string.island_battery_last_seen, islandAgeText(commonAge)),
                                color = textColor.copy(alpha = 0.6f), fontSize = 11.sp)
                            }
                        }

                        AnimatedVisibility(
                            visible = islandSettings.showListeningModeControl && expandedContent,
                            enter = fadeIn(animationSpec = tween(180, delayMillis = 80)),
                            exit = fadeOut(animationSpec = tween(100)),
                            modifier = Modifier
                                .requiredHeightIn(min = 20.dp, max = 64.dp)
                                .requiredWidth(with(density) { islandSettings.expandedWidth.toDp() - padding - 12.dp })
                                .weight(1f)
                        ) {
                            NoiseControlSettings(
                                modifier = Modifier.fillMaxHeight(),
                                showOffListeningMode = showOffListeningMode,
                                noiseControlModeValue = noiseControlModeValue,
                                onNoiseControlModeChanged = onNoiseControlModeChanged,
                                adaptiveStrength = adaptiveStrength,
                                requestedAdaptiveStrength = requestedAdaptiveStrength,
                                onAdaptiveStrengthChanged = onAdaptiveStrengthChanged,
                                showLabels = false
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun islandAgeText(age: Pair<Long, TimeUnit>): String = when (age.second) {
    TimeUnit.MINUTES -> stringResource(R.string.island_battery_age_minutes, age.first)
    TimeUnit.HOURS -> stringResource(R.string.island_battery_age_hours, age.first)
    TimeUnit.DAYS -> stringResource(R.string.island_battery_age_days, age.first)
    else -> stringResource(R.string.island_battery_age_recent)
}

@Composable
private fun IslandBatteryIndicator(cell: IslandBatteryCell, style: IslandBatteryStyle, textColor: Color, modifier: Modifier,
    showAge: Boolean = true) {
    val label = stringResource(when (cell.component) {
        BatteryComponent.LEFT -> R.string.island_battery_left
        BatteryComponent.RIGHT -> R.string.island_battery_right
        BatteryComponent.CASE -> R.string.island_battery_case
        BatteryComponent.HEADSET -> R.string.island_battery_headset
    })
    val percentage = cell.level?.let { "$it%" } ?: "—"
    val age = if (!cell.live && cell.ageSeconds != null)
        islandAgeText(islandAgeBucket(cell.ageSeconds)) else null
    val freshness = stringResource(when {
        cell.level == null -> R.string.island_battery_unavailable
        cell.live -> R.string.island_battery_current
        else -> R.string.island_battery_cached
    })
    val description = "$label, $percentage, $freshness" + (age?.let { ", ${stringResource(R.string.island_battery_last_seen, it)}" } ?: "") +
        if (cell.charging) ", ${stringResource(R.string.island_battery_charging)}" else ""
    val color = if (!cell.live || cell.level == null) textColor.copy(alpha = 0.5f)
        else if (cell.level <= 25) Color(0xFFFF453A) else Color(0xFF00D352)
    Column(modifier.clearAndSetSemantics { contentDescription = description }, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (style == IslandBatteryStyle.CIRCLE || style == IslandBatteryStyle.CIRCLE_TEXT) {
                Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize().padding(2.dp)) {
                        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                        drawArc(color.copy(alpha = 0.2f), 0f, 360f, false, style = stroke)
                        if (cell.level != null) drawArc(color, -90f, 3.6f * cell.level, false, style = stroke)
                    }
                    if (cell.charging) Icon(LocalIcons.current.Bolt, null, tint = color, modifier = Modifier.size(16.dp))
                }
            }
            if (style == IslandBatteryStyle.TEXT || style == IslandBatteryStyle.CIRCLE_TEXT)
                Text(percentage, color = if (cell.live) textColor else color, fontSize = 17.sp, maxLines = 1)
        }
        Text(label, color = textColor.copy(alpha = 0.65f), fontSize = 12.sp, maxLines = 1)
        if (showAge && age != null) Text(age, color = color, fontSize = 11.sp, maxLines = 1)
    }
}

@Preview
@Composable
fun IslandContentPreview(){
    IslandContent(
        name = "Kavish's AirPods Pro 3",
        type = IslandType.CONNECTED,
        batteryLevel = 75,
        reversed = false,
        otherDeviceName = null,

        islandState = IslandState.EXPANDED,
        islandSettings = IslandSettings(),

        openApp = {},
        changeState = { _, _ -> },
        onClosed = {},
        resetTimer = {},

        showOffListeningMode = false,
        noiseControlModeValue = 0,
        onNoiseControlModeChanged = {},
        adaptiveStrength = 0,
        onAdaptiveStrengthChanged = {}
    )
}
