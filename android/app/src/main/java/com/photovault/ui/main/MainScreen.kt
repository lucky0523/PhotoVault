package com.photovault.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
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
import com.photovault.ui.main.tabs.TrashTab

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
 * Main screen with bottom navigation, connection status top bar, and tab content.
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
    // On Android 13+ this is READ_MEDIA_IMAGES + READ_MEDIA_VIDEO; on older versions READ_EXTERNAL_STORAGE.
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    ConnectionStatusIndicator(connectionState = connectionState)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by tabNavController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                tabs.forEach { tab ->
                    NavigationBarItem(
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                        selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true,
                        onClick = {
                            tabNavController.navigate(tab.route) {
                                // Pop up to the start destination to avoid building up a large stack
                                popUpTo(tabNavController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                // Avoid multiple copies of the same destination
                                launchSingleTop = true
                                // Restore state when re-selecting a previously selected item
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = tabNavController,
            startDestination = MainTab.Local.route,
            modifier = Modifier.padding(paddingValues)
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

/**
 * Connection status indicator showing a colored dot and connection type text.
 * - Green dot + "局域网" when connected via LAN
 * - Green dot + "公网" when connected via WAN
 * - Gray dot + "未连接" when disconnected
 * - Gray dot + "连接中..." when connecting
 */
@Composable
private fun ConnectionStatusIndicator(connectionState: ConnectionState) {
    val (dotColor, statusText) = when (connectionState) {
        is ConnectionState.Connected -> {
            when (connectionState.type) {
                ConnectionType.LAN -> Color(0xFF4CAF50) to "局域网"
                ConnectionType.WAN -> Color(0xFF4CAF50) to "公网"
            }
        }
        is ConnectionState.Connecting -> Color.Gray to "连接中..."
        is ConnectionState.Disconnected -> Color.Gray to "未连接"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}
