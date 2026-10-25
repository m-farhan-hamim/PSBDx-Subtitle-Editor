package com.subtitle.psbdx

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.subtitle.psbdx.ui.AppRoot
import com.subtitle.psbdx.ui.EditorViewModel
import com.subtitle.psbdx.ui.theme.SubtitleTheme

class MainActivity : ComponentActivity() {
    private val vm: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { SubtitleTheme { AppRoot(vm) } }
    }

    override fun onStop() {
        vm.dub.stop()
        vm.pause()
        vm.flushSave()
        super.onStop()
    }
}
