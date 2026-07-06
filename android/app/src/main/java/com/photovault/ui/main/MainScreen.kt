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
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.photovault.data.network.ConnectionState
import com.photovault.data.network.ConnectionType
import com.photovault.ui.main.tabs.CloudTab
import com.photovault.ui.main.tabs.LocalTab
import com.photovault.ui.main.tabs.SettingsTab
import com.photovault.ui.main.tabs.TasksTab
import com.photovault.ui.main.tabs.TrashTab
import com.photovault.ui.theme.GlassBar
import com.photovault.ui.theme.LocalBottomBarPadding
import com.photovault.ui.theme.LocalGlassBackdrop
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
    data object Trash : MainTab("tab_trash", "回收站", Icons.Filled.Delete)
    data object Settings : MainTab("tab_settings", "设置", Icons.Filled.Settings)
}

private val tabs = listOf(
    MainTab.Local,
    MainTab.Cloud,
    MainTab.Tasks,
    MainTab.Trash,
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
    val tabNavController = rememberNavController()

    // Request media-read runtime permission (required for MediaStore image/video scanning).
    val mediaPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { /* result handled implicitly; scanning checks at runtime */ }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        val permissions = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
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
                    GlassHeader(connectionState = connectionState)
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
                    composable(MainTab.Trash.route) {
                        TrashTab()
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
                popUpTo(tabNavController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
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
private fun GlassHeader(connectionState: ConnectionState) {
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
        ConnectionPill(connectionState = connectionState)
    }
}

/**
 * Compact glass pill: colored dot + short status text.
 */
@Composable
private fun ConnectionPill(connectionState: ConnectionState) {
    val (dotColor, statusText) = when (connectionState) {
        is ConnectionState.Connected -> when (connectionState.type) {
            ConnectionType.LAN -> Color(0xFF34C759) to "局域网"
            ConnectionType.WAN -> Color(0xFF34C759) to "公网"
        }
        is ConnectionState.Connecting -> Color(0xFFFF9F0A) to "连接中"
        is ConnectionState.Disconnected -> Color(0xFF8E8E93) to "未连接"
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

