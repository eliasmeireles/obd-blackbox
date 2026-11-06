package io.github.eliasmeireles.obdblackbox.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Dialogs have a separate window: apply the chosen palette to their transient system bars too. */
@Composable
fun DialogSystemBars() {
    val view = LocalView.current
    val dark = AppColors.dark
    val background = AppColors.Background
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        @Suppress("DEPRECATION")
        window.apply {
            statusBarColor = background.toArgb()
            navigationBarColor = background.toArgb()
        }
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
