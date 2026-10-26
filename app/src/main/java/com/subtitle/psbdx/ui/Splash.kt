package com.subtitle.psbdx.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val SplashBg = Color(0xFF0F172A)
private val Accent = Color(0xFF38BDF8)

/** Branded launch screen shown right after the system splash: animated equalizer + name. */
@Composable
fun SplashOverlay(onDone: () -> Unit) {
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(1500)
        visible = false
        delay(350)
        onDone()
    }
    AnimatedVisibility(visible = visible, exit = fadeOut(tween(300))) {
        val alpha = remember { Animatable(0f) }
        LaunchedEffect(Unit) { alpha.animateTo(1f, tween(700)) }
        Box(Modifier.fillMaxSize().background(SplashBg), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                EqualizerLogo()
                Spacer(Modifier.height(28.dp))
                Text(
                    "Subtitle Editor",
                    color = Color.White.copy(alpha = alpha.value),
                    fontSize = 28.sp, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Dubbing Studio · by PSBDx",
                    color = Color(0xFF94A3B8).copy(alpha = alpha.value),
                    fontSize = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun EqualizerLogo() {
    val t = rememberInfiniteTransition(label = "eq")
    val durations = listOf(620, 480, 760, 540, 680)
    val heights = durations.mapIndexed { i, d ->
        val h by t.animateFloat(
            initialValue = 0.25f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(d, easing = LinearEasing), RepeatMode.Reverse),
            label = "bar$i",
        )
        h
    }
    Canvas(Modifier.size(width = 150.dp, height = 100.dp)) {
        val n = heights.size
        val gap = 10.dp.toPx()
        val bw = (size.width - gap * (n - 1)) / n
        heights.forEachIndexed { i, h ->
            val bh = size.height * h
            drawRoundRect(
                color = if (i % 2 == 0) Accent else Color.White,
                topLeft = Offset(i * (bw + gap), (size.height - bh) / 2),
                size = Size(bw, bh),
                cornerRadius = CornerRadius(bw / 2, bw / 2),
            )
        }
    }
}
