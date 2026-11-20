package me.kavishdevar.librepods.presentation.screens.onboarding

import android.Manifest
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.bluetooth.nearbyDevicesPermissions
import me.kavishdevar.librepods.presentation.screens.settings.PrivilegedAccessSetting
import me.kavishdevar.librepods.presentation.screens.settings.rememberPrivilegedAccessClient
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItem
import me.kavishdevar.librepods.presentation.components.primitives.styledListItem
import me.kavishdevar.librepods.presentation.components.primitives.StyledListItemOrientation
import me.kavishdevar.librepods.presentation.icons.MaterialIcons

private enum class PermissionStep { BLUETOOTH, NOTIFICATIONS, PHONE, MICROPHONE, HEALTH, OVERLAY, PRIVILEGED }

@Composable
fun PermissionsPage(
    onBackward: () -> Unit,
    onForward: () -> Unit
) {

    val context = LocalContext.current
    var grantAllStep by rememberSaveable { mutableStateOf<PermissionStep?>(null) }
    var pendingStep by rememberSaveable { mutableStateOf<PermissionStep?>(null) }
    fun advance(completed: PermissionStep) {
        if (pendingStep == completed) pendingStep = null
        if (grantAllStep == completed) grantAllStep = PermissionStep.entries.getOrNull(completed.ordinal + 1)
    }
    fun request(step: PermissionStep, launch: () -> Unit) {
        if (pendingStep != null) return
        pendingStep = step
        try {
            launch()
        } catch (expectedLaunchFailure: Exception) {
            // Any launcher or system failure only cancels this request; the user is told and can retry.
            pendingStep = null
            grantAllStep = null
            Toast.makeText(context, R.string.permission_request_failed, Toast.LENGTH_LONG).show()
        }
    }

    val access = rememberPrivilegedAccessClient()
    val accessState by access.state.collectAsState()
    var privilegedRequested by rememberSaveable { mutableStateOf(false) }
    val canDrawOverlays = remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var healthAvailable by remember { mutableStateOf(HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) }
    var healthGranted by remember { mutableStateOf(false) }
    var healthLoaded by remember { mutableStateOf(false) }
    var resumeCount by remember { mutableIntStateOf(0) }
    val healthPermission = remember { HealthPermission.getWritePermission(HeartRateRecord::class) }
    val healthClient = remember(healthAvailable) {
        if (healthAvailable) runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull() else null
    }
    LaunchedEffect(healthClient, resumeCount) {
        healthLoaded = false
        healthGranted = try {
            withTimeoutOrNull(3_000) {
                withContext(Dispatchers.IO) {
                    healthClient?.permissionController?.getGrantedPermissions()?.contains(healthPermission) == true
                }
            } == true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (expectedReadFailure: Exception) {
            // Health Connect may be missing, unbound or refuse the query; that reads as not granted.
            false
        }
        healthLoaded = true
    }
    val healthPermissions = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        healthGranted = healthPermission in granted
        advance(PermissionStep.HEALTH)
    }
    val overlayPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        canDrawOverlays.value = Settings.canDrawOverlays(context)
        advance(PermissionStep.OVERLAY)
    }
    fun requestOverlay() {
        overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri()))
    }
    val phonePermissionState = rememberMultiplePermissionsState(
        listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS)
    ) { advance(PermissionStep.PHONE) }
    val notificationPermissionState = rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS) {
        advance(PermissionStep.NOTIFICATIONS)
    }
    val microphonePermissionState = rememberPermissionState(Manifest.permission.RECORD_AUDIO) {
        advance(PermissionStep.MICROPHONE)
    }
    val bluetoothPermissionsState = rememberMultiplePermissionsState(
        nearbyDevicesPermissions + Manifest.permission.BLUETOOTH_ADVERTISE
    ) { advance(PermissionStep.BLUETOOTH) }

    LaunchedEffect(grantAllStep, grantAllStep == PermissionStep.HEALTH && healthLoaded) {
        if (grantAllStep == pendingStep) return@LaunchedEffect
        when (grantAllStep) {
            PermissionStep.BLUETOOTH -> if (bluetoothPermissionsState.allPermissionsGranted) advance(PermissionStep.BLUETOOTH)
                else request(PermissionStep.BLUETOOTH) { bluetoothPermissionsState.launchMultiplePermissionRequest() }
            PermissionStep.NOTIFICATIONS -> if (notificationPermissionState.status.isGranted) advance(PermissionStep.NOTIFICATIONS)
                else request(PermissionStep.NOTIFICATIONS) { notificationPermissionState.launchPermissionRequest() }
            PermissionStep.PHONE -> if (phonePermissionState.allPermissionsGranted) advance(PermissionStep.PHONE)
                else request(PermissionStep.PHONE) { phonePermissionState.launchMultiplePermissionRequest() }
            PermissionStep.MICROPHONE -> if (microphonePermissionState.status.isGranted) advance(PermissionStep.MICROPHONE)
                else request(PermissionStep.MICROPHONE) { microphonePermissionState.launchPermissionRequest() }
            PermissionStep.HEALTH -> if (healthLoaded) {
                if (healthClient == null || healthGranted) advance(PermissionStep.HEALTH)
                else request(PermissionStep.HEALTH) { healthPermissions.launch(setOf(healthPermission)) }
            }
            PermissionStep.OVERLAY -> if (canDrawOverlays.value) advance(PermissionStep.OVERLAY)
                else request(PermissionStep.OVERLAY) { requestOverlay() }
            PermissionStep.PRIVILEGED -> {
                privilegedRequested = true
                access.requestPermission()
                advance(PermissionStep.PRIVILEGED)
            }
            null -> Unit
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canDrawOverlays.value = Settings.canDrawOverlays(context)
                healthAvailable = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
                resumeCount++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val scrollState = rememberScrollState()
    val canRequest = grantAllStep == null && pendingStep == null

    Box(
        modifier = Modifier.background(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(42.dp)
        )
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StyledList(title = stringResource(R.string.required_permissions)) {
                val animatedBluetoothIconColor by animateColorAsState(if (bluetoothPermissionsState.allPermissionsGranted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                val animatedBluetoothContainerColor by animateColorAsState(
                    if (bluetoothPermissionsState.allPermissionsGranted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                )

                styledListItem(
                    contentText = stringResource(R.string.bluetooth),
                    onClick = if (canRequest && !bluetoothPermissionsState.allPermissionsGranted) {
                        {
                            grantAllStep = null
                            request(PermissionStep.BLUETOOTH) { bluetoothPermissionsState.launchMultiplePermissionRequest() }
                        }
                    } else null,
                    supportingText = stringResource(R.string.permission_description_bluetooth),
                    orientation = StyledListItemOrientation.Vertical,
                    leadingContent = {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    animatedBluetoothContainerColor,
                                    MaterialShapes.SoftBurst.normalized()
                                        .toShape()
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = MaterialIcons.Bluetooth,
                                contentDescription = "bluetooth",
                                modifier = Modifier.size(24.dp),
                                tint = animatedBluetoothIconColor
                            )
                        }
                    },
                )
            }
            StyledList(title = stringResource(R.string.optional_permissions)) {
                val animatedNotificationsIconColor by animateColorAsState(
                    if (notificationPermissionState.status.isGranted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )
                val animatedNotificationsContainerColor by animateColorAsState(
                    if (notificationPermissionState.status.isGranted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                )
                val animatedPhoneIconColor by animateColorAsState(if (phonePermissionState.allPermissionsGranted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                val animatedPhoneContainerColor by animateColorAsState(
                    if (phonePermissionState.allPermissionsGranted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                )

                styledListItem(
                    contentText = stringResource(R.string.notifications),
                    onClick = if (canRequest && !notificationPermissionState.status.isGranted) {
                        {
                            grantAllStep = null
                            request(PermissionStep.NOTIFICATIONS) { notificationPermissionState.launchPermissionRequest() }
                        }
                    } else null,
                    supportingText = stringResource(R.string.permission_description_notification),
                    orientation = StyledListItemOrientation.Vertical,
                    leadingContent = {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    animatedNotificationsContainerColor,
                                    MaterialShapes.SoftBurst.normalized()
                                        .toShape()
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = MaterialIcons.Notifications,
                                contentDescription = "notifications",
                                modifier = Modifier.size(24.dp),
                                tint = animatedNotificationsIconColor
                            )
                        }
                    },
                )
                styledListItem(
                    contentText = stringResource(R.string.phone),
                    onClick = if (canRequest && !phonePermissionState.allPermissionsGranted) {
                        {
                            grantAllStep = null
                            request(PermissionStep.PHONE) { phonePermissionState.launchMultiplePermissionRequest() }
                        }
                    } else null,
                    supportingText = stringResource(R.string.permission_description_phone),
                    orientation = StyledListItemOrientation.Vertical,
                    leadingContent = {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    animatedPhoneContainerColor,
                                    MaterialShapes.SoftBurst.normalized()
                                        .toShape()
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = MaterialIcons.Call,
                                contentDescription = "call",
                                modifier = Modifier.size(24.dp),
                                tint = animatedPhoneIconColor
                            )
                        }
                    },
                )
            }

            StyledListItem(
                contentText = stringResource(R.string.microphone),
                supportingText = stringResource(R.string.permission_description_microphone),
                orientation = StyledListItemOrientation.Vertical,
                onClick = if (canRequest && !microphonePermissionState.status.isGranted) ({
                    grantAllStep = null
                    request(PermissionStep.MICROPHONE) { microphonePermissionState.launchPermissionRequest() }
                }) else null,
            )
            PrivilegedAccessSetting(access, requestStarted = privilegedRequested, enabled = canRequest)

            val animatedHealthConnectIconColor by animateColorAsState(if (healthGranted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            val animatedHealthConnectContainerColor by animateColorAsState(if (healthGranted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)

            if (healthClient != null) StyledListItem(
                contentText = stringResource(R.string.permission_healthconnect),
                onClick = if (canRequest && healthLoaded && !healthGranted) {
                    {
                        grantAllStep = null
                        request(PermissionStep.HEALTH) { healthPermissions.launch(setOf(healthPermission)) }
                    }
                } else null,
                supportingText = stringResource(R.string.permission_description_healthconnect),
                leadingContent = {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                animatedHealthConnectContainerColor,
                                MaterialShapes.SoftBurst.normalized()
                                    .toShape()
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = MaterialIcons.VitalSigns,
                            contentDescription = "vital signs",
                            modifier = Modifier.size(24.dp),
                            tint = animatedHealthConnectIconColor
                        )
                    }
                }
            )

            val animatedOverlayIconColor by animateColorAsState(if (canDrawOverlays.value) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            val animatedOverlayContainerColor by animateColorAsState(if (canDrawOverlays.value) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)

            StyledListItem(
                contentText = stringResource(R.string.permission_overlay),
                onClick = if (canRequest && !canDrawOverlays.value) {
                    {
                        grantAllStep = null
                        request(PermissionStep.OVERLAY) { requestOverlay() }
                    }
                } else null,
                supportingText = stringResource(R.string.permission_description_overlay),
                orientation = StyledListItemOrientation.Vertical,
                leadingContent = {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                animatedOverlayContainerColor,
                                MaterialShapes.SoftBurst.normalized()
                                    .toShape()
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = MaterialIcons.Overlay,
                            contentDescription = "overlay",
                            modifier = Modifier.size(24.dp),
                            tint = animatedOverlayIconColor
                        )
                    }
                },
            )

            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledIconButton(
                    onClick = { grantAllStep = null; onBackward() },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .size(IconButtonDefaults.mediumContainerSize(IconButtonDefaults.IconButtonWidthOption.Narrow)),
                    shape = IconButtonDefaults.mediumRoundShape
                ) {
                    Icon(
                        Icons.AutoMirrored.Default.ArrowBack,
                        contentDescription = "backward",
                        modifier = Modifier.size(IconButtonDefaults.mediumIconSize),
                    )
                }
                Button(
                    onClick = { grantAllStep = PermissionStep.BLUETOOTH },
                    modifier = Modifier
                        .height(IconButtonDefaults.mediumContainerSize(IconButtonDefaults.IconButtonWidthOption.Narrow).height)
                        .weight(1f),
                    enabled = grantAllStep == null && pendingStep == null && (!bluetoothPermissionsState.allPermissionsGranted ||
                        !notificationPermissionState.status.isGranted || !phonePermissionState.allPermissionsGranted ||
                        !microphonePermissionState.status.isGranted || (healthClient != null && !healthGranted) ||
                        !canDrawOverlays.value || !accessState.connected)
                ) {
                    Text(
                        text = stringResource(R.string.grant_all_permissions),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                FilledIconButton(
                    onClick = onForward,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .size(
                            IconButtonDefaults.mediumContainerSize(IconButtonDefaults.IconButtonWidthOption.Narrow)
                        ),
                    shape = IconButtonDefaults.mediumRoundShape,
                    enabled = bluetoothPermissionsState.allPermissionsGranted && grantAllStep == null && pendingStep == null
                ) {
                    Icon(
                        Icons.AutoMirrored.Default.ArrowForward,
                        contentDescription = "forward",
                        modifier = Modifier.size(IconButtonDefaults.mediumIconSize),
                    )
                }
            }
        }
    }
}
