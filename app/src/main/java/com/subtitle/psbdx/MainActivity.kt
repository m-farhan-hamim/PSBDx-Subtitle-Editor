package com.subtitle.psbdx

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.subtitle.psbdx.ui.AppRoot
import com.subtitle.psbdx.ui.EditorViewModel
import com.subtitle.psbdx.ui.theme.SubtitleTheme

class MainActivity : ComponentActivity() {
    private val vm: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { SubtitleTheme { AppRoot(vm) } }
    }

    override fun onStop() {
        vm.studio.onBackground()
        vm.pause()
        vm.flushSave()
        super.onStop()
    }
}
