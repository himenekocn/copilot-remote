package com.copilot.remote.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.copilot.remote.data.ConnectionState
import com.copilot.remote.data.InstanceInfo
import com.copilot.remote.data.ServerProfile
import com.copilot.remote.ui.components.ConnectionStatusIndicator
import com.copilot.remote.viewmodel.CopilotViewModel
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.net.URI

@Composable
fun SettingsScreen(viewModel: CopilotViewModel) {
    val state by viewModel.uiState.collectAsState()
    val initial = remember(state.wsUrl) { parseServerUrl(state.wsUrl) }
    var secure by remember(state.wsUrl) { mutableStateOf(initial.first == "wss") }
    var host by remember(state.wsUrl) { mutableStateOf(initial.second) }
    var port by remember(state.wsUrl) { mutableStateOf(initial.third) }
    var key by remember(state.key) { mutableStateOf(state.key) }
    var showKey by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    val url = buildServerUrl(if (secure) "wss" else "ws", host, port)

    LaunchedEffect(state.activeConnectionId, state.connectionState) {
        if (state.connectionState == ConnectionState.AUTHENTICATED) {
            viewModel.refreshInstances(showFeedback = false)
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
      LazyColumn(Modifier.fillMaxHeight().widthIn(max = 760.dp).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer), cornerRadius = 20.dp, insideMargin = PaddingValues(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Dns, null, tint = MiuixTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("服务器状态", style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
                        Text(state.connectedWorkspaceName.ifBlank { "Copilot Remote" }, style = MiuixTheme.textStyles.footnote1)
                    }
                    ConnectionStatusIndicator(state.connectionState)
                }
            }
        }
        if (state.serverProfiles.isNotEmpty()) {
            item { SmallTitle("已保存的服务器") }
            items(state.serverProfiles, key = { it.id }) { profile ->
                ProfileCard(profile, profile.id == state.activeProfileId, state.connectionState, { viewModel.connectToProfile(profile) }, { viewModel.deleteProfile(profile.id) })
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("VS Code 实例", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceSecondary, modifier = Modifier.weight(1f))
                IconButton(onClick = { viewModel.refreshInstances() }, enabled = state.connectionState == ConnectionState.AUTHENTICATED, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Default.Refresh, "刷新 VS Code 实例", modifier = Modifier.size(20.dp))
                }
            }
        }
        if (state.instances.isNotEmpty()) {
            items(state.instances, key = { it.instanceId }) { instance -> InstanceCard(instance, instance.instanceId == state.activeInstanceId) { viewModel.selectInstance(instance.instanceId) } }
        } else {
            item { Text("尚未发现可用实例", color = MiuixTheme.colorScheme.onSurfaceSecondary, modifier = Modifier.padding(horizontal = 28.dp)) }
        }
        item { SmallTitle("手动连接") }
        item {
            Card(colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer), cornerRadius = 20.dp, insideMargin = PaddingValues(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    BasicComponent(title = "使用加密连接", summary = if (secure) "WSS" else "WS（仅限可信局域网）", endActions = { Switch(secure, { secure = it }) })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextField(host, { host = it.trim() }, label = "IP 或主机名", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.weight(1f), singleLine = true)
                        TextField(port, { port = it.filter(Char::isDigit).take(5) }, label = "端口", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(112.dp), singleLine = true)
                    }
                    TextField(key, { key = it }, label = "认证密钥", modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(), leadingIcon = { Icon(Icons.Default.Key, null) }, trailingIcon = { IconButton(onClick = { showKey = !showKey }) { Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (showKey) "隐藏密钥" else "显示密钥") } })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { if (state.connectionState == ConnectionState.AUTHENTICATED) viewModel.disconnect() else viewModel.connect(url, key) }, enabled = host.isNotBlank() && port.toIntOrNull() in 1..65535 && key.isNotBlank(), modifier = Modifier.weight(1f)) { Icon(if (state.connectionState == ConnectionState.AUTHENTICATED) Icons.Default.PowerOff else Icons.Default.Power, null); Spacer(Modifier.width(8.dp)); Text(if (state.connectionState == ConnectionState.AUTHENTICATED) "断开" else "连接") }
                        Button(onClick = { showSave = true }, enabled = host.isNotBlank() && key.isNotBlank()) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(6.dp)); Text("保存") }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer), cornerRadius = 20.dp, insideMargin = PaddingValues(16.dp)) {
                Text("快速设置", style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("1. 在 VS Code 安装 Copilot Remote 扩展\n2. 运行 Generate New Key\n3. 运行 Start Server\n4. 输入地址与密钥后连接", style = MiuixTheme.textStyles.body2)
            }
        }
      }
    }

    var label by remember { mutableStateOf("") }
    SuperDialog(show = showSave, title = "保存服务器", onDismissRequest = { showSave = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(label, { label = it }, label = "配置名称", modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton("取消", { showSave = false })
                Button(onClick = { viewModel.saveProfile(label.ifBlank { host }, url, key); showSave = false }, enabled = label.isNotBlank()) { Text("保存") }
            }
        }
    }
}

@Composable
private fun ProfileCard(profile: ServerProfile, active: Boolean, connectionState: ConnectionState, onConnect: () -> Unit, onDelete: () -> Unit) {
    Card(onClick = onConnect, colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer), cornerRadius = 18.dp, insideMargin = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (active) Icons.Default.CheckCircle else Icons.Default.Computer, null, tint = if (active) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceSecondary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) { Text(profile.label, fontWeight = FontWeight.Bold); Text(profile.wsUrl, style = MiuixTheme.textStyles.footnote1, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis); if (active) ConnectionStatusIndicator(connectionState) }
            IconButton(onClick = onDelete, enabled = !active) { Icon(Icons.Default.Delete, "删除", tint = MiuixTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun InstanceCard(instance: InstanceInfo, selected: Boolean, onSelect: () -> Unit) {
    Card(onClick = onSelect, colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer), cornerRadius = 18.dp, insideMargin = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (instance.isPrimary) Icons.Default.Star else Icons.Default.DesktopWindows, null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) { Text(instance.workspaceName.ifBlank { instance.windowTitle }, fontWeight = FontWeight.Bold); Text("${instance.host}:${instance.port}", style = MiuixTheme.textStyles.footnote1, fontFamily = FontFamily.Monospace) }
            if (selected) Icon(Icons.Default.Check, null, tint = MiuixTheme.colorScheme.primary)
        }
    }
}

private fun parseServerUrl(url: String): Triple<String, String, String> = runCatching {
    val uri = URI(url.ifBlank { "ws://192.168.1.100:9876" })
    Triple(uri.scheme?.takeIf { it == "ws" || it == "wss" } ?: "ws", uri.host.orEmpty(), (if (uri.port > 0) uri.port else 9876).toString())
}.getOrDefault(Triple("ws", "", "9876"))

private fun buildServerUrl(scheme: String, host: String, port: String): String {
    val normalized = host.trim().removePrefix("[").removeSuffix("]")
    return "$scheme://${if (':' in normalized) "[$normalized]" else normalized}:$port"
}
