package io.github.eliasmeireles.obdblackbox.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import io.github.eliasmeireles.obdblackbox.ui.AppTheme

object AppColors {
    private val darkState = mutableStateOf(true)
    internal var dark: Boolean
        get() = darkState.value
        set(value) { darkState.value = value }
    val Background get() = if (dark) Color(0xFF0E1116) else Color(0xFFF4F7FA)
    val Surface get() = if (dark) Color(0xFF161B22) else Color(0xFFFFFFFF)
    val SurfaceHigh get() = if (dark) Color(0xFF1E2530) else Color(0xFFE5ECF2)
    val Line get() = if (dark) Color(0xFF2A3240) else Color(0xFFBCC8D4)
    val Text get() = if (dark) Color(0xFFE8ECF1) else Color(0xFF142536)
    val Muted get() = if (dark) Color(0xFF8A94A6) else Color(0xFF4C6072)
    val Accent get() = if (dark) Color(0xFF2EE6C5) else Color(0xFF006B58)
    val Warning get() = if (dark) Color(0xFFF5B83D) else Color(0xFF865300)
    val Critical get() = if (dark) Color(0xFFFF5A4F) else Color(0xFFB3261E)
    val Good get() = if (dark) Color(0xFF59D98E) else Color(0xFF176D36)
}

val TabularNumbers = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun ObdBlackBoxTheme(preference: AppTheme = AppTheme.AUTO, content: @Composable () -> Unit) {
    val dark = when (preference) {
        AppTheme.AUTO -> isSystemInDarkTheme()
        AppTheme.DARK -> true
        AppTheme.LIGHT -> false
    }
    AppColors.dark = dark
    val colors = if (dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(
        colorScheme = colors.copy(
            primary = AppColors.Accent, onPrimary = AppColors.Background,
            background = AppColors.Background, onBackground = AppColors.Text,
            surface = AppColors.Surface, onSurface = AppColors.Text,
            surfaceVariant = AppColors.SurfaceHigh, onSurfaceVariant = AppColors.Muted,
            outline = AppColors.Muted, error = AppColors.Critical,
            secondary = AppColors.Accent, onSecondary = AppColors.Background,
        ),
        content = content,
    )
}
