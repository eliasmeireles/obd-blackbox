package io.github.eliasmeireles.obdblackbox.ui.dashboard

import androidx.compose.ui.graphics.Color
import io.github.eliasmeireles.obdblackbox.domain.alerts.Severity
import io.github.eliasmeireles.obdblackbox.ui.theme.AppColors

enum class Level {
    NORMAL, WARNING, CRITICAL, UNKNOWN;
    val color: Color get() = when (this) {
        NORMAL -> AppColors.Accent
        WARNING -> AppColors.Warning
        CRITICAL -> AppColors.Critical
        UNKNOWN -> AppColors.Line
    }
}

fun Severity.level(): Level = if (this == Severity.CRITICAL) Level.CRITICAL else Level.WARNING

/** Tile colors. Thresholds mirror the alert rules so screen and alerts agree. */
object Levels {
    fun fuel(percent: Double?): Level = when {
        percent == null -> Level.UNKNOWN
        percent < 8.0 -> Level.CRITICAL
        percent < 15.0 -> Level.WARNING
        else -> Level.NORMAL
    }

    fun voltage(volts: Double?, engineRunning: Boolean): Level = when {
        volts == null -> Level.UNKNOWN
        !engineRunning -> Level.NORMAL
        volts < 12.0 -> Level.CRITICAL
        volts < 13.0 -> Level.WARNING
        else -> Level.NORMAL
    }

    fun coolant(celsius: Double?): Level = when {
        celsius == null -> Level.UNKNOWN
        celsius > 105.0 -> Level.CRITICAL
        celsius > 100.0 -> Level.WARNING
        else -> Level.NORMAL
    }

    fun known(value: Double?): Level = if (value == null) Level.UNKNOWN else Level.NORMAL
}
