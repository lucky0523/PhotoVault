package com.photovault.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.photovault.data.network.ConnectionState
import com.photovault.data.network.ConnectionType
import com.photovault.ui.main.tabs.CloudTab
import com.photovault.ui.main.tabs.LocalTab
import com.photovault.ui.main.tabs.SettingsTab
import com.photovault.ui.main.tabs.TasksTab
import com.photovault.ui.theme.GlassBar
import com.photovault.ui.theme.LiquidDialogButton
import com.photovault.ui.theme.LiquidDialogButtonStyle
import com.photovault.ui.theme.LiquidGlassDialog
import com.photovault.ui.theme.LocalBottomBarPadding
import com.photovault.ui.theme.LocalGlassBackdrop
import com.photovault.ui.theme.PhotoVaultColors
import com.photovault.ui.theme.appBackgroundBrush
import com.photovault.ui.theme.liquid.LiquidBottomTab
import com.photovault.ui.theme.liquid.LiquidBottomTabs
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/**
 * Bottom navigation tab definitions.
 */
sealed class MainTab(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    data object Local : MainTab("tab_local", "本地", Icons.Filled.PhoneAndroid)
    data object Cloud : MainTab("tab_cloud", "云端", Icons.Filled.Cloud)
    data object Tasks : MainTab("tab_tasks", "备份任务", Icons.Filled.Sync)
    data object Settings : MainTab("tab_settings", "设置", Icons.Filled.Settings)
}

private val tabs = listOf(
    MainTab.Local,
    MainTab.Cloud,
    MainTab.Tasks,
    MainTab.Settings
)

/**
 * Main screen with a slim glass header (title + connection pill), a frosted
 * glass bottom navigation bar, and tab content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onLogout: () -> Unit,
    onNavigateToFolderDetail: (folderId: Long, folderName: String, folderUri: String, backedUpImages: Int) -> Unit = { _, _, _, _ -> },
    viewModel: MainViewModel = hiltViewModel()
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val heartbeatCountdown by viewModel.heartbeatCountdown.collectAsState()
    val tabNavController = rememberNavController()

    // Trigger ③ (foreground): when a periodic scan finds a user-paused backup and
    // the app is in the foreground, it asks for confirmation instead of resuming
    // silently. Show that confirmation here.
    val showResumePrompt by com.photovault.service.BackupResumePrompt.pending.collectAsState()
    val promptContext = androidx.compose.ui.platform.LocalContext.current
    // Only surface the confirmation while the app UI is actually visible (RESUMED).
    // A background scan resumes silently and never requests a prompt, but this
    // guard also covers the transition race where the request is made just as the
    // app is being backgrounded — the dialog must never appear while off-screen.
    var appVisible by remember { mutableStateOf(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { appVisible = true }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { appVisible = false }
    if (showResumePrompt && appVisible) {
        LiquidGlassDialog(
            onDismissRequest = { com.photovault.service.BackupResumePrompt.consume() },
            title = "恢复备份？",
            text = "你之前手动暂停了自动备份。是否现在恢复未完成的备份任务？"
        ) {
            LiquidDialogButton(
                text = "保持暂停",
                onClick = { com.photovault.service.BackupResumePrompt.consume() },
                style = LiquidDialogButtonStyle.Neutral
            )
            LiquidDialogButton(
                text = "恢复",
                onClick = {
                    com.photovault.service.BackupForegroundService.resume(promptContext)
                    com.photovault.service.BackupResumePrompt.consume()
                },
                style = LiquidDialogButtonStyle.Accent
            )
        }
    }

    // Request media-read runtime permission (required for MediaStore image/video scanning).
    val mediaPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { /* result handled implicitly; scanning checks at runtime */ }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        val permissions = buildList {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.READ_MEDIA_IMAGES)
                add(android.Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            // ACCESS_MEDIA_LOCATION (API 29+) lets us read un-redacted original bytes
            // for byte-identical backups (R5.2). Not being granted is non-blocking —
            // MediaBytesReader falls back to a plain read (R5.4).
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                add(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
            }
        }.toTypedArray()
        mediaPermissionLauncher.launch(permissions)
    }

    val backgroundBrush = appBackgroundBrush()
    // Gradient-only backdrop: used by the header pill and the in-content
    // liquid-glass cards. Keeping it free of content avoids feedback artifacts.
    val bgBackdrop = rememberLayerBackdrop()
    // Content backdrop: captures the gradient + the scrolling tab content, so the
    // floating bottom bar genuinely refracts whatever scrolls beneath it. The bar
    // is a sibling (not part of this layer), so it never samples itself.
    val contentBackdrop = rememberLayerBackdrop(
        onDraw = {
            drawRect(backgroundBrush)
            drawContent()
        }
    )

    // Space the floating tab bar occupies at the bottom, so scrollable content can
    // clear it: navigation bar inset + the bar footprint (height + margins).
    val navBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomBarPadding = navBottomInset + 88.dp

    Box(modifier = Modifier.fillMaxSize()) {
        // Full-screen gradient layer recorded into bgBackdrop; also paints the
        // gradient on screen behind the (transparent) Scaffold.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(bgBackdrop)
                .background(backgroundBrush)
        )

        CompositionLocalProvider(
            LocalGlassBackdrop provides bgBackdrop,
            LocalBottomBarPadding provides bottomBarPadding
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    GlassHeader(
                        connectionState = connectionState,
                        heartbeatCountdown = heartbeatCountdown
                    )
                }
            ) { paddingValues ->
                // Full-screen content layer captured by contentBackdrop. Content
                // fills to the very bottom (behind the floating bar); the tab
                // screens add LocalBottomBarPadding to their bottom contentPadding.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .layerBackdrop(contentBackdrop)
                ) {
                    NavHost(
                        navController = tabNavController,
                        startDestination = MainTab.Local.route,
                        modifier = Modifier.padding(top = paddingValues.calculateTopPadding())
                    ) {
                    composable(MainTab.Local.route) {
                        LocalTab(
                            onNavigateToFolderDetail = onNavigateToFolderDetail
                        )
                    }
                    composable(MainTab.Cloud.route) {
                        CloudTab()
                    }
                    composable(MainTab.Tasks.route) {
                        TasksTab()
                    }
                        composable(MainTab.Settings.route) {
                            SettingsTab(onLogout = onLogout)
                        }
                    }
                }
            }
        }

        // Floating liquid-glass tab bar (draggable thumb + interactive highlight),
        // overlaid at the bottom of the page rather than occupying a layout slot.
        //
        // Selection is driven by a *synchronous* state (selectedIndex), NOT by the
        // navigation back-stack. The nav back-stack updates asynchronously, so using
        // it as the selection source created a feedback loop: a tap would navigate,
        // the (lagging) route would flow back into the bar, re-trigger animateToValue
        // and re-navigate, and rapid taps would spam overlapping press()/release()
        // squish animations until the thumb got stuck enlarged (an oval/egg blob).
        // Setting selectedIndex up-front (like the reference catalog) keeps the
        // source of truth immediate and the thumb animation balanced.
        //
        // The lambda must be a *stable* identity that reads selectedIndex *inside*
        // it (a real snapshot read): the bar keys internal state on the lambda's
        // identity and observes it via snapshotFlow.
        var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
        val selectedTabIndex: () -> Int = remember { { selectedIndex } }

        val onTabSelected: (Int) -> Unit = { index ->
            selectedIndex = index
            val tab = tabs[index]
            tabNavController.navigate(tab.route) {
                // Clear the whole tab back stack (saving each tab's state) so
                // switching tabs never leaves a poppable entry. This way the
                // system back on any tab has nothing to pop here and falls
                // through to exit the app, instead of returning to LocalTab.
                popUpTo(tabNavController.graph.id) {
                    inclusive = true
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }

        // Keep the highlight in sync with the actual NavHost destination so a
        // system back (which pops the route without going through onTabSelected)
        // also moves the highlight. This only updates the index — it never
        // navigates — so there is no feedback loop with onTabSelected.
        val navEntry by tabNavController.currentBackStackEntryAsState()
        LaunchedEffect(navEntry?.destination?.route) {
            val idx = tabs.indexOfFirst { it.route == navEntry?.destination?.route }
            if (idx >= 0 && idx != selectedIndex) {
                selectedIndex = idx
            }
        }

        LiquidBottomTabs(
            selectedTabIndex = selectedTabIndex,
            onTabSelected = onTabSelected,
            backdrop = contentBackdrop,
            tabsCount = tabs.size,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            tabs.forEach { tab ->
                val tabColor = MaterialTheme.colorScheme.onSurfaceVariant
                LiquidBottomTab {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                        tint = tabColor,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = tab.label,
                        fontSize = 10.sp,
                        color = tabColor,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * Slim, information-dense header: brand on the left, a compact connection pill
 * on the right. Sits under the (transparent) status bar edge-to-edge.
 */
@Composable
private fun GlassHeader(connectionState: ConnectionState, heartbeatCountdown: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "PhotoVault",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.weight(1f))
        ConnectionPill(
            connectionState = connectionState,
            heartbeatCountdown = heartbeatCountdown
        )
    }
}

/**
 * Compact glass pill: colored dot + short status text.
 */
@Composable
private fun ConnectionPill(connectionState: ConnectionState, heartbeatCountdown: Int) {
    val (dotColor, baseText) = when (connectionState) {
        is ConnectionState.Connected -> when (connectionState.type) {
            ConnectionType.LAN -> PhotoVaultColors.SyncGreen to "局域网"
            ConnectionType.WAN -> PhotoVaultColors.SyncGreen to "公网"
        }
        is ConnectionState.Connecting -> PhotoVaultColors.ArchiveAmber to "连接中"
        is ConnectionState.Disconnected -> MaterialTheme.colorScheme.onSurfaceVariant to "未连接"
    }
    // Debug: append the countdown whenever the heartbeat/reconnect loop is
    // active (countdown >= 0), regardless of connection state, so we can see
    // when the next probe/reconnect attempt fires.
    val statusText = if (heartbeatCountdown >= 0) {
        "$baseText · ♥${heartbeatCountdown}s"
    } else {
        baseText
    }
    val animatedDot by animateColorAsState(dotColor, label = "dot")

    GlassBar(
        shape = CircleShape
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(animatedDot)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
