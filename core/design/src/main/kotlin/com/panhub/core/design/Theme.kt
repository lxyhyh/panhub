package com.panhub.core.design

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * 蓝云（LanCloud）风格配色：
 * - 主色是偏冷的蓝，饱和度不高，用在按钮与选中态；
 * - 背景是非常浅的冷灰，卡片纯白 + 8dp 圆角 + 极淡阴影；
 * - 强调色用橙色，只出现在容量条和“已满”这类警示上。
 */
private val BrandBlue = Color(0xFF3D6BF5)
private val BrandBlueDark = Color(0xFF6E92FF)
private val AccentOrange = Color(0xFFFF8A3D)
private val SurfaceLight = Color(0xFFFFFFFF)
private val BackgroundLight = Color(0xFFF5F7FA)
private val SurfaceDark = Color(0xFF1B1E24)
private val BackgroundDark = Color(0xFF12141A)

private val LightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4EBFF),
    onPrimaryContainer = Color(0xFF10214F),
    secondary = Color(0xFF5B6472),
    onSecondary = Color.White,
    tertiary = AccentOrange,
    onTertiary = Color.White,
    background = BackgroundLight,
    onBackground = Color(0xFF1A1D23),
    surface = SurfaceLight,
    onSurface = Color(0xFF1A1D23),
    surfaceVariant = Color(0xFFEFF2F7),
    onSurfaceVariant = Color(0xFF5B6472),
    outline = Color(0xFFD3D9E3),
    error = Color(0xFFE5484D),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = BrandBlueDark,
    onPrimary = Color(0xFF0B1530),
    primaryContainer = Color(0xFF24365F),
    onPrimaryContainer = Color(0xFFDCE5FF),
    secondary = Color(0xFFA9B2C1),
    onSecondary = Color(0xFF1A1D23),
    tertiary = AccentOrange,
    onTertiary = Color(0xFF2A1500),
    background = BackgroundDark,
    onBackground = Color(0xFFE6E9EF),
    surface = SurfaceDark,
    onSurface = Color(0xFFE6E9EF),
    surfaceVariant = Color(0xFF262A33),
    onSurfaceVariant = Color(0xFFA9B2C1),
    outline = Color(0xFF3A404C),
    error = Color(0xFFFF6369),
    onError = Color(0xFF2A0004),
)

private val PanHubTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal),
)

@Composable
fun PanHubTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = colors,
        typography = PanHubTypography,
        content = content,
    )
}
