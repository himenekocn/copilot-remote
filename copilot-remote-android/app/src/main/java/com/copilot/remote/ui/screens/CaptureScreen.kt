package com.copilot.remote.ui.screens

import top.yukonga.miuix.kmp.theme.MiuixTheme

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.copilot.remote.viewmodel.CopilotViewModel
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

@Composable
fun CaptureScreen(viewModel: CopilotViewModel, onOpenChat: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    var frameIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.captureFrames) {
        frameIndex = 0
        if (state.captureFrames.size > 1) while (true) {
            delay(state.captureIntervalMs.coerceAtLeast(250).toLong())
            frameIndex = (frameIndex + 1) % state.captureFrames.size
        }
    }
    val bitmap = remember(state.captureFrames, frameIndex) {
        state.captureFrames.getOrNull(frameIndex)?.let { Base64.decode(it, Base64.DEFAULT) }?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.asImageBitmap()
    }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("VS Code 窗口", style = MiuixTheme.textStyles.title1)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::captureWindow) { Icon(Icons.Default.PhotoCamera, null); Spacer(Modifier.width(6.dp)); Text("截图") }
            Button(onClick = viewModel::recordWindow) { Icon(Icons.Default.Videocam, null); Spacer(Modifier.width(6.dp)); Text("录制 5 秒") }
            Button(
                onClick = { viewModel.attachCaptureToChat(frameIndex); onOpenChat() },
                enabled = bitmap != null,
            ) {
                Icon(Icons.Default.AddPhotoAlternate, null)
                Spacer(Modifier.width(6.dp))
                Text("附加到对话")
            }
        }
        if (bitmap == null) Box(Modifier.fillMaxWidth().weight(1f)) { Text("截图将在这里显示") }
        else Image(bitmap, "VS Code capture", Modifier.fillMaxWidth().weight(1f), contentScale = ContentScale.Fit)
        if (state.captureFrames.size > 1) Text("帧 ${frameIndex + 1}/${state.captureFrames.size} · ${state.captureIntervalMs} 毫秒")
    }
}
