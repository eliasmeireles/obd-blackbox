package io.github.eliasmeireles.obdblackbox.ui.dashboard

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.eliasmeireles.obdblackbox.domain.format.formatPtOrDash
import io.github.eliasmeireles.obdblackbox.ui.theme.TabularNumbers
import io.github.eliasmeireles.obdblackbox.ui.theme.AppColors
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private const val MAX_RPM = 7_000.0
private const val REDLINE_RPM = 6_000.0
private const val START_ANGLE = 135f
private const val SWEEP = 270f
private const val TICKS = 7

/** Big speed number inside an rpm arc. The caller gives it a square size. */
@Composable
fun SpeedGauge(speedKmh: Double?, rpm: Double?, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = min(maxWidth.value, maxHeight.value)
        val fraction = ((rpm ?: 0.0) / MAX_RPM).toFloat().coerceIn(0f, 1f)
        val redline = (rpm ?: 0.0) >= REDLINE_RPM
        Canvas(Modifier.fillMaxSize()) { drawGauge(fraction, redline) }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val speedSize = (side * 0.30f).sp
            Text(speedKmh.formatPtOrDash(0), color = AppColors.Text, fontSize = speedSize, lineHeight = speedSize, fontWeight = FontWeight.Bold, style = TabularNumbers)
            Text(text(R.string.unit_speed), color = AppColors.Muted, fontSize = (side * 0.065f).sp)
            Text(text(R.string.msg_value_rpm, (rpm.formatPtOrDash(0)).toString()), color = AppColors.Text, fontSize = (side * 0.08f).sp, style = TabularNumbers)
        }
    }
}

private fun DrawScope.drawGauge(fraction: Float, redline: Boolean) {
    val stroke = size.minDimension * 0.075f
    val inset = stroke / 2f + 2f
    val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
    val topLeft = Offset(inset, inset)
    val redStart = (REDLINE_RPM / MAX_RPM).toFloat()
    drawArc(AppColors.Line, START_ANGLE, SWEEP, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    drawArc(AppColors.Critical.copy(alpha = 0.35f), START_ANGLE + SWEEP * redStart, SWEEP * (1 - redStart), false, topLeft, arcSize, style = Stroke(stroke))
    if (fraction > 0f) {
        val color = if (redline) AppColors.Critical else AppColors.Accent
        drawArc(color, START_ANGLE, SWEEP * fraction, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    }
    drawTicks(stroke, arcSize.width / 2f)
}

private fun DrawScope.drawTicks(stroke: Float, radius: Float) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val outer = radius - stroke * 0.9f
    val inner = outer - stroke * 0.55f
    for (k in 0..TICKS) {
        val angle = Math.toRadians((START_ANGLE + SWEEP * k / TICKS).toDouble())
        val dir = Offset(cos(angle).toFloat(), sin(angle).toFloat())
        drawLine(AppColors.Muted, center + dir * inner, center + dir * outer, strokeWidth = 3f, cap = StrokeCap.Round)
    }
}
