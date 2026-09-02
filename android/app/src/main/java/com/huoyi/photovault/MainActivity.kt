package com.huoyi.photovault

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.huoyi.photovault.ui.navigation.NavGraph
import com.huoyi.photovault.ui.theme.PhotoVaultTheme
import com.huoyi.photovault.ui.theme.appBackgroundBrush
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PhotoVaultTheme {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(appBackgroundBrush())
                ) {
                    NavGraph()
                }
            }
        }
    }
}
