package com.example.wadl.ui

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.WindowCompat

class ThemeDef(
    val name: String,
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val text: Color,
    val sub: Color,
    val dark: Boolean
)

val themeDefs = listOf(
    ThemeDef("داكن", Color(0xFF0E1014), Color(0xFF181B22), Color(0xFF232733), Color(0xFFF2F4F8), Color(0xFF98A1B3), true),
    ThemeDef("أمولد", Color(0xFF000000), Color(0xFF0D0D0F), Color(0xFF1A1A1F), Color(0xFFFFFFFF), Color(0xFF8A8F9C), true),
    ThemeDef("فاتح", Color(0xFFF3F5F9), Color(0xFFFFFFFF), Color(0xFFE8ECF3), Color(0xFF14171F), Color(0xFF5B6475), false),
    ThemeDef("أزرق ليلي", Color(0xFF0A1224), Color(0xFF111D38), Color(0xFF1B2B4D), Color(0xFFEAF1FF), Color(0xFF8FA3C7), true)
)

val accentColors = listOf(
    Color(0xFF3D8BFF), Color(0xFF8B5CF6), Color(0xFF10B981),
    Color(0xFFF59E0B), Color(0xFFEF4444), Color(0xFFEC4899)
)

val GreenOk = Color(0xFF22C55E)
val AmberWarn = Color(0xFFF59E0B)
val RedErr = Color(0xFFEF4444)

@Composable
fun AppTheme(themeIdx: Int, accentIdx: Int, content: @Composable () -> Unit) {
    val t = themeDefs[themeIdx.coerceIn(0, themeDefs.size - 1)]
    val acc = accentColors[accentIdx.coerceIn(0, accentColors.size - 1)]

    val scheme = if (t.dark) {
        darkColorScheme(
            primary = acc, onPrimary = Color.White, secondary = acc,
            background = t.bg, onBackground = t.text,
            surface = t.surface, onSurface = t.text,
            surfaceVariant = t.surface2, onSurfaceVariant = t.sub,
            outline = t.sub.copy(alpha = 0.45f),
            primaryContainer = acc.copy(alpha = 0.2f), onPrimaryContainer = acc,
            error = RedErr
        )
    } else {
        lightColorScheme(
            primary = acc, onPrimary = Color.White, secondary = acc,
            background = t.bg, onBackground = t.text,
            surface = t.surface, onSurface = t.text,
            surfaceVariant = t.surface2, onSurfaceVariant = t.sub,
            outline = t.sub.copy(alpha = 0.45f),
            primaryContainer = acc.copy(alpha = 0.15f), onPrimaryContainer = acc,
            error = RedErr
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val w = (view.context as Activity).window
            WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = !t.dark
                isAppearanceLightNavigationBars = !t.dark
            }
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
