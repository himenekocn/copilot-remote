package com.copilot.remote.ui.screens

import top.yukonga.miuix.kmp.theme.MiuixTheme

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.copilot.remote.data.ExtensionInfo
import com.copilot.remote.ui.components.*
import com.copilot.remote.viewmodel.CopilotViewModel
import top.yukonga.miuix.kmp.basic.*

@Composable
fun ExtensionsScreen(viewModel: CopilotViewModel) {
    val state by viewModel.uiState.collectAsState()
    var showOnlyAI by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refreshExtensions() }

    val filtered = remember(state.extensions, showOnlyAI) {
        if (showOnlyAI) state.extensions.filter { it.isAI } else state.extensions
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${filtered.size} 个扩展", color = MiuixTheme.colorScheme.onSurfaceSecondary, modifier = Modifier.weight(1f))
            IconButton(onClick = { showOnlyAI = !showOnlyAI }, modifier = Modifier.size(44.dp)) { Icon(if (showOnlyAI) Icons.Default.FilterAlt else Icons.Default.FilterAltOff, "仅显示 AI 扩展", modifier = Modifier.size(21.dp)) }
            IconButton(onClick = viewModel::refreshExtensions, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.Refresh, "刷新", modifier = Modifier.size(21.dp)) }
        }
        if (state.extensions.isEmpty()) {
            LoadingIndicator(text = "正在加载扩展…")
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(filtered) { ext ->
                ExtensionCard(
                    extension = ext,
                    onToggle = { enable -> viewModel.toggleExtension(ext.id, enable) },
                )
            }
        }
    }
}

@Composable
private fun ExtensionCard(
    extension: ExtensionInfo,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 12.dp,
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (extension.isCopilot) MiuixTheme.colorScheme.primaryContainer
                else if (extension.isAI) MiuixTheme.colorScheme.secondaryContainer
                else MiuixTheme.colorScheme.surface,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Extension,
                        contentDescription = null,
                        tint = if (extension.isCopilot) MiuixTheme.colorScheme.onPrimaryContainer
                        else if (extension.isAI) MiuixTheme.colorScheme.onTertiaryContainer
                        else MiuixTheme.colorScheme.onSurfaceSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    extension.displayName,
                    style = MiuixTheme.textStyles.title4,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    extension.id,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    InfoChip("v${extension.version}")
                    if (extension.isCopilot) InfoChip("Copilot", containerColor = MiuixTheme.colorScheme.primaryContainer, contentColor = MiuixTheme.colorScheme.onPrimaryContainer)
                    if (extension.isAI && !extension.isCopilot) InfoChip("AI", containerColor = MiuixTheme.colorScheme.secondaryContainer, contentColor = MiuixTheme.colorScheme.onTertiaryContainer)
                }
            }
            Switch(
                checked = extension.isActive,
                onCheckedChange = onToggle,
            )
        }
    }
}
