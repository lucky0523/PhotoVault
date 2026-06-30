package com.photovault.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.photovault.ui.login.LoginScreen
import com.photovault.ui.main.FolderDetailScreen
import com.photovault.ui.main.MainScreen
import java.net.URLDecoder
import java.net.URLEncoder

object Routes {
    const val LOGIN = "login"
    const val MAIN = "main"
    const val FOLDER_DETAIL = "folder_detail/{folderId}/{folderName}/{folderUri}/{backedUpImages}"

    fun folderDetail(folderId: Long, folderName: String, folderUri: String, backedUpImages: Int): String {
        val encodedUri = URLEncoder.encode(folderUri, "UTF-8")
        val encodedName = URLEncoder.encode(folderName, "UTF-8")
        return "folder_detail/$folderId/$encodedName/$encodedUri/$backedUpImages"
    }
}

@Composable
fun NavGraph() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.LOGIN
    ) {
        composable(Routes.LOGIN) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Routes.MAIN) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.MAIN) {
            MainScreen(
                onLogout = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.MAIN) { inclusive = true }
                    }
                },
                onNavigateToFolderDetail = { folderId, folderName, folderUri, backedUpImages ->
                    navController.navigate(
                        Routes.folderDetail(folderId, folderName, folderUri, backedUpImages)
                    )
                }
            )
        }

        composable(
            route = Routes.FOLDER_DETAIL,
            arguments = listOf(
                navArgument("folderId") { type = NavType.LongType },
                navArgument("folderName") { type = NavType.StringType },
                navArgument("folderUri") { type = NavType.StringType },
                navArgument("backedUpImages") { type = NavType.IntType }
            )
        ) { backStackEntry ->
            val folderName = URLDecoder.decode(
                backStackEntry.arguments?.getString("folderName") ?: "", "UTF-8"
            )
            val folderUri = URLDecoder.decode(
                backStackEntry.arguments?.getString("folderUri") ?: "", "UTF-8"
            )
            val backedUpImages = backStackEntry.arguments?.getInt("backedUpImages") ?: 0

            FolderDetailScreen(
                folderName = folderName,
                folderUri = folderUri,
                backedUpImages = backedUpImages,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
