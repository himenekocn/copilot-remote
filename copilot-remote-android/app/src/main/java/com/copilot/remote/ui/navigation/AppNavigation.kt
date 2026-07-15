package com.copilot.remote.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.*
import com.copilot.remote.data.ConnectionState
import com.copilot.remote.ui.screens.*
import com.copilot.remote.viewmodel.CopilotUiState
import com.copilot.remote.viewmodel.CopilotViewModel
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    data object Chat : Screen("chat", "Copilot", Icons.Default.Chat)
    data object Explorer : Screen("explorer", "文件与搜索", Icons.Default.FolderOpen)
    data object Workspace : Screen("workspace", "工作区", Icons.Default.Folder)
    data object Terminal : Screen("terminal", "终端", Icons.Default.Terminal)
    data object Git : Screen("git", "Git", Icons.Default.Source)
    data object Models : Screen("models", "模型", Icons.Default.Psychology)
    data object Agents : Screen("agents", "智能体", Icons.Default.SmartToy)
    data object Skills : Screen("skills", "技能", Icons.Default.AutoAwesome)
    data object Tools : Screen("tools", "工具", Icons.Default.Handyman)
    data object Commands : Screen("commands", "命令", Icons.Default.Code)
    data object Extensions : Screen("extensions", "扩展", Icons.Default.Extension)
    data object Mcp : Screen("mcp", "MCP", Icons.Default.Hub)
    data object Settings : Screen("settings", "设置与账户", Icons.Default.Settings)
}

private val screens = listOf(Screen.Chat, Screen.Explorer, Screen.Workspace, Screen.Terminal, Screen.Git, Screen.Models, Screen.Agents, Screen.Skills, Screen.Tools, Screen.Commands, Screen.Extensions, Screen.Mcp, Screen.Settings)

@Composable
fun AppNavigation(viewModel: CopilotViewModel) {
    val nav = rememberNavController()
    val state by viewModel.uiState.collectAsState()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val tablet = LocalConfiguration.current.screenWidthDp >= 700
    var drawerOpen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val navigate: (Screen) -> Unit = { screen ->
        drawerOpen = false
        nav.navigate(screen.route) { launchSingleTop = true; restoreState = true }
    }
    BackHandler(drawerOpen) { drawerOpen = false }
    LaunchedEffect(state.errorMessage) { state.errorMessage?.let { snackbar.showSnackbar(it); viewModel.clearError() } }
    LaunchedEffect(state.notice?.id) {
        state.notice?.let { notice ->
            snackbar.showSnackbar(notice.message)
            viewModel.consumeNotice(notice.id)
        }
    }
    LaunchedEffect(state.pendingSharedText, state.pendingSharedImageUri) {
        if (state.pendingSharedText.isNotBlank() || state.pendingSharedImageUri.isNotBlank()) navigate(Screen.Chat)
    }

    val content: @Composable () -> Unit = {
        Scaffold(
            topBar = {
                if (route != Screen.Chat.route) SmallTopAppBar(
                    title = screens.firstOrNull { it.route == route }?.title ?: "Copilot",
                    navigationIcon = { if (!tablet) IconButton(onClick = { drawerOpen = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Menu, "打开菜单", modifier = Modifier.size(22.dp)) } },
                    actions = { if (route == Screen.Chat.route) IconButton(onClick = viewModel::newChatSession, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.AddComment, "新建对话", modifier = Modifier.size(22.dp)) } },
                )
            },
            containerColor = MiuixTheme.colorScheme.background,
        ) { padding ->
            NavHost(nav, if (state.connectionState == ConnectionState.AUTHENTICATED) Screen.Chat.route else Screen.Settings.route, Modifier.padding(padding).consumeWindowInsets(padding)) {
                composable(Screen.Chat.route) { ChatScreen(viewModel, onOpenNavigation = { drawerOpen = true }) }
                composable(Screen.Explorer.route) { ExplorerScreen(viewModel) }
                composable(Screen.Workspace.route) { WorkspaceScreen(viewModel) }
                composable(Screen.Terminal.route) { TerminalScreen(viewModel) }
                composable(Screen.Git.route) { GitScreen(viewModel) { navigate(Screen.Explorer) } }
                composable(Screen.Models.route) { ModelsScreen(viewModel) }
                composable(Screen.Agents.route) { AgentsScreen(viewModel) }
                composable(Screen.Skills.route) { SkillsScreen(viewModel) }
                composable(Screen.Tools.route) { ToolsScreen(viewModel) }
                composable(Screen.Commands.route) { CommandsScreen(viewModel) }
                composable(Screen.Extensions.route) { ExtensionsScreen(viewModel) }
                composable(Screen.Mcp.route) { McpScreen(viewModel) }
                composable(Screen.Settings.route) { SettingsScreen(viewModel) }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (tablet) Row(Modifier.fillMaxSize()) {
            ChatSidebar(viewModel, state, route, navigate, Modifier.width(320.dp).fillMaxHeight())
            VerticalDivider()
            Box(Modifier.weight(1f).fillMaxHeight()) { content() }
        } else Box(Modifier.fillMaxSize()) {
            content()
            AnimatedVisibility(drawerOpen, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .35f)).clickable { drawerOpen = false })
            }
            AnimatedVisibility(drawerOpen, enter = slideInHorizontally { -it }, exit = slideOutHorizontally { -it }) {
                Surface(modifier = Modifier.widthIn(max = 340.dp).fillMaxHeight(), shape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp), color = MiuixTheme.colorScheme.surface) {
                    ChatSidebar(viewModel, state, route, navigate, Modifier.fillMaxSize())
                }
            }
        }
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
        )
    }
}

@Composable
private fun ChatSidebar(viewModel: CopilotViewModel, state: CopilotUiState, route: String?, navigate: (Screen) -> Unit, modifier: Modifier) {
    var showMore by remember { mutableStateOf(false) }
    val remoteSessions = state.nativeChatSessions.sortedByDescending { it.updatedAt }
    Column(modifier.background(MiuixTheme.colorScheme.surface).statusBarsPadding().padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Copilot", style = MiuixTheme.textStyles.title1, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { navigate(Screen.Chat); viewModel.newChatSession() }, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.AddComment, "新建对话", modifier = Modifier.size(20.dp)) }
        }
        SidebarItem("文件与搜索", Screen.Explorer.icon, route == Screen.Explorer.route) { navigate(Screen.Explorer) }
        SidebarItem("项目", Screen.Workspace.icon, route == Screen.Workspace.route) { navigate(Screen.Workspace) }
        SidebarItem("扩展", Screen.Extensions.icon, route == Screen.Extensions.route) { navigate(Screen.Extensions) }
        SidebarItem("更多", Icons.Default.MoreHoriz, showMore) { showMore = !showMore }
        AnimatedVisibility(showMore) {
            Column(Modifier.padding(start = 12.dp)) {
                listOf(Screen.Terminal, Screen.Git, Screen.Models, Screen.Agents, Screen.Skills, Screen.Tools, Screen.Commands, Screen.Mcp).forEach { screen ->
                    SidebarItem(screen.title, screen.icon, route == screen.route) { navigate(screen) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 20.dp, end = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("全部对话", style = MiuixTheme.textStyles.title4, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (state.nativeSessionTotal > 0) Text("${state.nativeChatSessions.size}/${state.nativeSessionTotal}", style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary)
            IconButton(onClick = viewModel::refreshNativeChatSessions, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.Refresh, "刷新全部对话", modifier = Modifier.size(20.dp)) }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
            if (remoteSessions.isNotEmpty()) {
                items(remoteSessions, key = { it.id }) { session ->
                    ConversationItem(conversationTitle(session.title, session.id), session.id == state.activeNativeChatSessionId) {
                        viewModel.selectNativeChatSession(session.id)
                        navigate(Screen.Chat)
                    }
                }
                if (state.nativeSessionsHasMore) item {
                    TextButton(if (state.nativeSessionsLoading) "正在加载…" else "加载更多对话", onClick = viewModel::loadMoreNativeChatSessions, enabled = !state.nativeSessionsLoading, modifier = Modifier.fillMaxWidth())
                }
            } else {
                items(state.chatSessions.sortedByDescending { it.updatedAt }, key = { it.id }) { session ->
                    ConversationItem(session.title.ifBlank { "新对话" }, session.id == state.activeChatSessionId) {
                        viewModel.selectChatSession(session.id)
                        navigate(Screen.Chat)
                    }
                }
                if (state.nativeSessionsLoading) item { InfiniteProgressIndicator(Modifier.fillMaxWidth().padding(12.dp)) }
            }
        }
        SidebarItem(state.connectedWorkspaceName.ifBlank { "设置与账户" }, Icons.Default.AccountCircle, route == Screen.Settings.route, if (state.connectionState == ConnectionState.AUTHENTICATED) "已连接" else "未连接") { navigate(Screen.Settings) }
        Spacer(Modifier.navigationBarsPadding().height(8.dp))
    }
}

@Composable
private fun SidebarItem(title: String, icon: ImageVector, selected: Boolean, summary: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp).background(if (selected) MiuixTheme.colorScheme.surfaceContainer else Color.Transparent, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, modifier = Modifier.size(22.dp), tint = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) { Text(title, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal); summary?.let { Text(it, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceSecondary) } }
    }
}

@Composable
private fun ConversationItem(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (selected) MiuixTheme.colorScheme.surfaceContainer else Color.Transparent, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp), tint = MiuixTheme.colorScheme.primary)
    }
}

private fun conversationTitle(title: String, id: String) = title.takeUnless { it.isBlank() || it == id || it.matches(Regex("[0-9a-fA-F-]{36}")) } ?: "未命名对话"
