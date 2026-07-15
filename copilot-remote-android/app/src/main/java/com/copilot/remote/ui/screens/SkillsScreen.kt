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
import androidx.compose.ui.unit.dp
import com.copilot.remote.data.SkillInfo
import com.copilot.remote.ui.components.*
import com.copilot.remote.viewmodel.CopilotViewModel
import top.yukonga.miuix.kmp.basic.*

@Composable
fun SkillsScreen(viewModel: CopilotViewModel) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) { viewModel.refreshSkills() }

    Box(Modifier.fillMaxSize()) {
        if (state.skills.isEmpty()) {
            LoadingIndicator(text = "正在加载技能…")
            return@Box
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                SectionHeader("可用技能", "${state.skills.size} 个 Copilot 工具")
            }
            items(state.skills) { skill ->
                SkillCard(
                    skill = skill,
                    onInvoke = { viewModel.invokeSkill(skill.id) },
                )
            }
        }
    }
}

@Composable
private fun SkillCard(
    skill: SkillInfo,
    onInvoke: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (skill.isCopilot == true) MiuixTheme.colorScheme.primaryContainer else MiuixTheme.colorScheme.surface,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = if (skill.isCopilot == true) MiuixTheme.colorScheme.onPrimaryContainer else MiuixTheme.colorScheme.onSurfaceSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        skill.name,
                        style = MiuixTheme.textStyles.title4,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (skill.isCopilot == true) {
                        Spacer(modifier = Modifier.width(6.dp))
                        InfoChip("Copilot", containerColor = MiuixTheme.colorScheme.primaryContainer, contentColor = MiuixTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Text(
                    skill.description,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                )
            }
            IconButton(
                onClick = onInvoke,
                modifier = Modifier.size(36.dp),
                cornerRadius = 10.dp,
                backgroundColor = MiuixTheme.colorScheme.primary,
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "调用", modifier = Modifier.size(18.dp))
            }
        }
    }
}
