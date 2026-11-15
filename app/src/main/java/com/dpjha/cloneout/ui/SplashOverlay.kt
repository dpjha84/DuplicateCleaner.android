package com.dpjha.cloneout.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.dpjha.cloneout.R
import kotlinx.coroutines.delay

/**
 * In-app splash: the same indigo background and logo as the system splash, so it shows on every phone
 * (some manufacturers' system splash omits the icon). The logo pops in, then the screen fades into the app.
 * Shown once per launch; rotation does not replay it.
 */
@Composable
fun SplashOverlay() {
    var visible by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(750)
        visible = false
    }
    AnimatedVisibility(visible = visible, exit = fadeOut(tween(300))) {
        val pop by animateFloatAsState(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
            label = "logoPop"
        )
        Box(
            Modifier.fillMaxSize().background(Color(0xFF4F46E5)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.ic_splash_logo),
                contentDescription = null,
                modifier = Modifier.size(240.dp).scale(0.7f + 0.3f * pop)
            )
        }
    }
}
