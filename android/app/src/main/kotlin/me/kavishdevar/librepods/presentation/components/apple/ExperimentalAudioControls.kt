/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.components.apple

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.audio.ExperimentalAudioController
import me.kavishdevar.librepods.audio.MicrophonePhase
import me.kavishdevar.librepods.presentation.components.primitives.StyledList
import me.kavishdevar.librepods.presentation.components.primitives.styledListItem

/** Optional diagnostics use the selected device's controller and the rewrite's existing controls. */
@Composable
fun ExperimentalAudioControls(controller: ExperimentalAudioController) {
    val context = LocalContext.current
    val permissionMessage = stringResource(R.string.experimental_audio_permission)
    val shareTitle = stringResource(R.string.experimental_audio_share)
    val bridge by controller.bridge.state.collectAsState()
    val microphone by controller.microphone.state.collectAsState()
    val message by controller.message.collectAsState()
    val active = microphone.phase !in listOf(MicrophonePhase.IDLE, MicrophonePhase.ERROR)
    val busy = active || controller.isOpening || bridge.session !in listOf("stopped", "failed")
    var targetPackage by remember(controller) { mutableStateOf("") }
    var pendingAction by remember(controller) { mutableIntStateOf(0) }
    var localError by remember(controller) { mutableStateOf("") }
    var player by remember(controller) { mutableStateOf<MediaPlayer?>(null) }
    val currentController by rememberUpdatedState(controller)
    val currentTarget by rememberUpdatedState(targetPackage)
    fun run(action: Int) {
        player?.release()
        player = null
        localError = ""
        when (action) {
            1 -> currentController.startRecording()
            2 -> currentController.startInjection(currentTarget.trim(), forCall = false)
            3 -> currentController.startInjection("", forCall = true)
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingAction
        pendingAction = 0
        if (granted && action != 0 && currentController.isConnected) run(action)
        else localError = permissionMessage
    }
    fun start(action: Int) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) run(action)
        else { pendingAction = action; permission.launch(Manifest.permission.RECORD_AUDIO) }
    }
    DisposableEffect(controller) {
        onDispose { pendingAction = 0; player?.release(); player = null }
    }

    StyledList(title = stringResource(R.string.experimental_audio_title), description = stringResource(R.string.experimental_audio_description)) {
        styledListItem(contentText = stringResource(if (bridge.connected) R.string.experimental_shizuku_connected else R.string.experimental_shizuku_connect),
            enabled = !bridge.connected && !busy, onClick = { controller.bridge.requestPermission() })
        styledListItem(contentText = stringResource(if (bridge.probing) R.string.experimental_audio_checking else R.string.experimental_audio_check),
            enabled = bridge.connected && !bridge.probing && !busy, onClick = { controller.bridge.probe() })
        if (bridge.report.isNotBlank()) styledListItem(contentText = stringResource(R.string.experimental_audio_copy_report), onClick = {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("LibrePods audio diagnostics", bridge.report))
        })
    }
    if (controller.supportsMicrophone) {
        Spacer(Modifier.height(16.dp))
        StyledList(title = stringResource(R.string.experimental_audio_microphone), description = stringResource(R.string.experimental_audio_clock)) {
            styledListItem(contentText = stringResource(R.string.experimental_audio_record),
                enabled = controller.isConnected && bridge.connected && !bridge.probing && !busy && pendingAction == 0,
                onClick = { start(1) })
            if (busy) styledListItem(contentText = stringResource(R.string.experimental_audio_stop), onClick = { controller.stop() })
            val file = microphone.file?.takeIf { !busy && it.isFile && it.length() > 44 }
            if (file != null) {
                styledListItem(contentText = stringResource(R.string.experimental_audio_play), onClick = {
                    try {
                        player?.release()
                        val next = MediaPlayer()
                        player = next
                        next.apply {
                            setDataSource(file.absolutePath)
                            setOnCompletionListener { it.release(); if (player === it) player = null }
                            setOnErrorListener { failed, _, _ -> failed.release(); if (player === failed) player = null; true }
                            prepare()
                            start()
                        }
                    } catch (error: Exception) { player?.release(); player = null; localError = error.message.orEmpty() }
                })
                styledListItem(contentText = stringResource(R.string.experimental_audio_share), onClick = {
                    try {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                        val intent = Intent(Intent.ACTION_SEND).setType("audio/wav").putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(intent, shareTitle))
                    } catch (error: Exception) { localError = error.message.orEmpty() }
                })
            }
        }
        if (microphone.format != null) Text(stringResource(R.string.experimental_audio_duration,
            microphone.pcmDurationMs / 1000.0, microphone.wallDurationMs / 1000.0), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(value = targetPackage, onValueChange = { targetPackage = it }, singleLine = true,
            label = { Text(stringResource(R.string.experimental_audio_target)) }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        Spacer(Modifier.height(8.dp))
        StyledList(description = stringResource(R.string.experimental_audio_calls_hint)) {
            styledListItem(contentText = stringResource(R.string.experimental_audio_inject),
                enabled = controller.isConnected && bridge.connected && !bridge.probing && !busy && targetPackage.isNotBlank(), onClick = { start(2) })
            styledListItem(contentText = stringResource(R.string.experimental_audio_call),
                enabled = controller.isConnected && bridge.connected && !bridge.probing && !busy, onClick = { start(3) })
        }
    }
    val status = localError.ifBlank { message.ifBlank { microphone.message.ifBlank { bridge.detail.ifBlank { bridge.availability } } } }
    if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.experimental_audio_eld_gate), style = MaterialTheme.typography.bodySmall)
}
