package com.copilot.remote.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.copilot.remote.data.SkillInfo
import com.copilot.remote.viewmodel.CopilotViewModel
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SkillsScreen(viewModel: CopilotViewModel) {
    val state by viewModel.uiState.collectAsState()
    var query by remember { mutableStateOf("") }
    var userExpanded by remember { mutableStateOf(true) }
    var extensionExpanded by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) { viewModel.refreshSkills() }

    val visible = remember(state.skills, query) {
        val needle = query.trim()
        if (needle.isEmpty()) state.skills else state.skills.filter {
            it.name.contains(needle, ignoreCase = true) ||
                it.description.contains(needle, ignoreCase = true) ||
                it.sourceLabel.contains(needle, ignoreCase = true)
        }
    }
    val userSkills = visible.filter { it.source == "user" }
    val extensionSkills = visible.filter { it.source == "extension" }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Text("技能", style = MiuixTheme.textStyles.title2, fontWeight = FontWeight.Bold)
            Text(
                "当相关时，Copilot 会加载包含指令、脚本和资源的文件夹，以执行专用任务。",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            TextField(
                value = query,
                onValueChange = { query = it },
                label = "输入以搜索…",
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
            )
        }

        item { SkillGroupHeader("用户", userSkills.size, userExpanded) { userExpanded = !userExpanded } }
        if (userExpanded) items(userSkills, key = { it.id }) { SkillRow(it) }

        item {
            Spacer(Modifier.height(10.dp))
            SkillGroupHeader("扩展", extensionSkills.size, extensionExpanded) { extensionExpanded = !extensionExpanded }
        }
        if (extensionExpanded) items(extensionSkills, key = { it.id }) { SkillRow(it) }

        if (visible.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (query.isBlank()) "未发现技能" else "没有匹配的技能",
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SkillGroupHeader(title: String, count: Int, expanded: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text(count.toString(), color = MiuixTheme.colorScheme.onSurfaceSecondary)
        Spacer(Modifier.weight(1f))
        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = if (expanded) "收起" else "展开")
    }
}

@Composable
private fun SkillRow(skill: SkillInfo) {
    var expanded by rememberSaveable(skill.id) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                skill.name,
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (skill.sourceLabel.isNotBlank()) {
                Text(skill.sourceLabel, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.primary)
            }
        }
        if (skill.description.isNotBlank()) {
            Text(
                skill.description,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        AnimatedVisibility(expanded && skill.description.isNotBlank()) {
            Text("点击技能会在相关任务中由 Copilot 自动加载", style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
