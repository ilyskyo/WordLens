package com.ilyskyo.wordlens

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.ilyskyo.wordlens.ui.nav.WordLensApp
import com.ilyskyo.wordlens.ui.theme.WordLensTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            WordLensTheme {
                WordLensApp()
            }
        }
    }
}
