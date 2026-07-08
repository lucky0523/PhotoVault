package com.photovault.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import android.net.Uri
import com.photovault.ui.login.LoginScreen
import com.photovault.ui.main.FolderDetailScreen
import com.photovault.ui.main.MainScreen

object Routes {
    const val LOGIN = "login"
    const val MAIN = "main"
    const val FOLDER_DETAIL = "folder_detail/{folderId}/{folderName}/{folderUri}/{backedUpImages}"

    fun folderDetail(folderId: Long, folderName: String, folderUri: String, backedUpImages: Int): String {
        // Encode with Uri.encode (percent-encoding, space -> %20) so it round-trips
        // cleanly with Navigation's single automatic Uri.decode of path arguments.
        // (URLEncoder used application/x-www-form-urlencoded — '+' for space — and
        // the previous extra manual URLDecoder.decode double-decoded the value,
        // corrupting SAF tree URIs, e.g. dropping the "Camera" path segment.)
        val encodedUri = Uri.encode(folderUri)
        val encodedName = Uri.encode(folderName)
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
            ),
            enterTransition = {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(300)
                )
            },
            exitTransition = {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(300)
                )
            },
            popEnterTransition = {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(300)
                )
            },
            popExitTransition = {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(300)
                )
            }
        ) { backStackEntry ->
            // Navigation already Uri.decode()s path arguments once, so read them
            // directly — no manual decode (which previously double-decoded).
            val folderName = backStackEntry.arguments?.getString("folderName") ?: ""
            val folderUri = backStackEntry.arguments?.getString("folderUri") ?: ""
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
