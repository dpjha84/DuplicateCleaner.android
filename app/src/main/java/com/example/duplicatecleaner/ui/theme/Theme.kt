package com.example.duplicatecleaner.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.example.duplicatecleaner.data.ThemeMode

// Brand: indigo -> violet, with a teal accent that matches the app icon.
val BrandIndigo = Color(0xFF4F46E5)
val BrandViolet = Color(0xFF7C3AED)
val BrandTeal = Color(0xFF0EA5A4)

fun brandGradient(): Brush = Brush.linearGradient(listOf(BrandIndigo, BrandViolet))

private val LightColors = lightColorScheme(
    primary = BrandIndigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE2E0FF),
    onPrimaryContainer = Color(0xFF0C0A60),
    secondary = Color(0xFF0B8F7D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFF5EC),
    onSecondaryContainer = Color(0xFF00382F),
    tertiary = BrandViolet,
    tertiaryContainer = Color(0xFFEBDDFF),
    onTertiaryContainer = Color(0xFF2A0A6B),
    background = Color(0xFFF8F7FF),
    onBackground = Color(0xFF1B1B26),
    surface = Color(0xFFF8F7FF),
    onSurface = Color(0xFF1B1B26),
    surfaceVariant = Color(0xFFEAE8F6),
    onSurfaceVariant = Color(0xFF47464F),
    outline = Color(0xFF777680),
    outlineVariant = Color(0xFFC9C6D6),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBFC1FF),
    onPrimary = Color(0xFF1B1A7A),
    primaryContainer = Color(0xFF3532C4),
    onPrimaryContainer = Color(0xFFE2E0FF),
    secondary = Color(0xFF7FDCC8),
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF0F5A4E),
    onSecondaryContainer = Color(0xFFCFF5EC),
    tertiary = Color(0xFFD3BBFF),
    tertiaryContainer = Color(0xFF5A2DC0),
    onTertiaryContainer = Color(0xFFEBDDFF),
    background = Color(0xFF12121A),
    onBackground = Color(0xFFE5E1EC),
    surface = Color(0xFF12121A),
    onSurface = Color(0xFFE5E1EC),
    surfaceVariant = Color(0xFF2A2A38),
    onSurfaceVariant = Color(0xFFC8C5D4),
    outline = Color(0xFF928F9C),
    outlineVariant = Color(0xFF47464F),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

private val base = Typography()
private val AppTypography = Typography(
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    displaySmall = TextStyle(fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold)
)

@Composable
fun AppTheme(
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }

    // Keep status/navigation bar icons readable when the app theme differs from the system theme.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
