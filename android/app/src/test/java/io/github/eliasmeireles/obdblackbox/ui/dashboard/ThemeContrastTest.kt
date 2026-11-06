package io.github.eliasmeireles.obdblackbox.ui.dashboard

import androidx.compose.ui.graphics.Color
import io.github.eliasmeireles.obdblackbox.ui.theme.AppColors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    @Test fun `text and semantic colors remain readable on both palettes`() {
        try {
            for (dark in listOf(false, true)) {
                AppColors.dark = dark
                for (background in listOf(AppColors.Background, AppColors.Surface, AppColors.SurfaceHigh)) {
                    for (foreground in listOf(AppColors.Text, AppColors.Muted, AppColors.Accent, AppColors.Warning, AppColors.Critical, AppColors.Good)) {
                        val contrast = contrast(foreground, background)
                        assertTrue("dark=$dark: contrast $contrast", contrast >= 4.5)
                    }
                }
            }
        } finally { AppColors.dark = true }
    }

    private fun contrast(a: Color, b: Color): Double {
        val first = luminance(a)
        val second = luminance(b)
        return (max(first, second) + 0.05) / (min(first, second) + 0.05)
    }

    private fun luminance(color: Color): Double {
        fun linear(channel: Float): Double = channel.toDouble().let {
            if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
    }
}
