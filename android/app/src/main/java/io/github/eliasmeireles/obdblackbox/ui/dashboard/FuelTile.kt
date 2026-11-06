package io.github.eliasmeireles.obdblackbox.ui.dashboard

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.eliasmeireles.obdblackbox.domain.format.formatPtOrDash
import io.github.eliasmeireles.obdblackbox.domain.fuel.FuelRange
import io.github.eliasmeireles.obdblackbox.ui.theme.TabularNumbers
import io.github.eliasmeireles.obdblackbox.ui.theme.AppColors

@Composable
fun FuelTile(fuel: FuelRange, modifier: Modifier = Modifier) {
    val level = Levels.fuel(fuel.levelPercent)
    val color = if (level == Level.UNKNOWN) AppColors.Muted else level.color
    val status = when (level) {
        Level.CRITICAL -> text(R.string.msg_nivel_critico)
        Level.WARNING -> text(R.string.msg_combustivel_baixo)
        Level.UNKNOWN -> text(R.string.msg_aguardando_nivel)
        Level.NORMAL -> text(R.string.msg_nivel_normal)
    }
    BoxWithConstraints(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AppColors.Surface)) {
        val compact = maxHeight < 100.dp
        val valueSize = if (compact) 22.sp else 26.sp
        Box(Modifier.fillMaxHeight().width(4.dp).background(level.color))
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text(R.string.msg_combustivel), color = AppColors.Muted, fontSize = 12.sp)
                Text(status, color = color, fontSize = 12.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text(R.string.msg_value_338, (fuel.levelPercent.formatPtOrDash(0)).toString()), color = color, fontSize = valueSize, lineHeight = valueSize,
                    fontWeight = FontWeight.Bold, style = TabularNumbers)
                Text(text(R.string.msg_value_km_339, (fuel.rangeKm.formatPtOrDash(0)).toString()), color = AppColors.Text, fontSize = valueSize, lineHeight = valueSize,
                    fontWeight = FontWeight.Bold, style = TabularNumbers)
            }
            Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(AppColors.Line)) {
                Box(Modifier.fillMaxWidth(((fuel.levelPercent ?: 0.0) / 100.0).toFloat()).fillMaxHeight().background(level.color))
            }
            val average = if (fuel.learning) {
                text(R.string.msg_calculando_a_media_do_seu_carro_rode_mais_value_km, (fuel.kmToLearn.formatPtOrDash(1)).toString())
            } else {
                text(R.string.msg_media_do_seu_carro_value_km_l_nos_ultimos_value_km, (fuel.averageKmPerLiter.formatPtOrDash(1)).toString(), (fuel.learnedDistanceKm.formatPtOrDash(0)).toString())
            }
            Text(text(R.string.msg_value_l_value, (fuel.litersLeft.formatPtOrDash(1)).toString(), (average).toString()), color = AppColors.Muted, fontSize = 11.sp, lineHeight = 13.sp)
        }
    }
}
