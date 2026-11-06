package io.github.eliasmeireles.obdblackbox.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.eliasmeireles.obdblackbox.ui.theme.TabularNumbers
import io.github.eliasmeireles.obdblackbox.ui.theme.AppColors

@Composable
fun MetricTile(
    label: String,
    value: String,
    unit: String,
    level: Level,
    modifier: Modifier = Modifier,
    progress: Float? = null,
) {
    BoxWithConstraints(modifier.clip(RoundedCornerShape(12.dp)).background(AppColors.Surface)) {
        // Short tiles (landscape) get a smaller number so it is never clipped.
        val compact = maxHeight < 76.dp
        val valueSize = if (compact) 22.sp else 28.sp
        Row(Modifier.fillMaxHeight()) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(level.color))
            Column(
                Modifier.fillMaxHeight().fillMaxWidth().padding(horizontal = 12.dp, vertical = if (compact) 2.dp else 6.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(label, color = AppColors.Muted, fontSize = if (compact) 12.sp else 13.sp, maxLines = 1)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(value, color = AppColors.Text, fontSize = valueSize, lineHeight = valueSize, fontWeight = FontWeight.Bold, style = TabularNumbers, maxLines = 1)
                    Spacer(Modifier.width(4.dp))
                    Text(unit, color = AppColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 3.dp), maxLines = 1)
                }
                if (progress != null) ProgressBar(progress, level)
            }
        }
    }
}

@Composable
private fun ProgressBar(progress: Float, level: Level) {
    Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(AppColors.Line)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(level.color))
    }
}

@Composable
fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 6.dp)) {
        Text(label, color = AppColors.Muted, fontSize = 12.sp, maxLines = 1)
        Text(value, color = AppColors.Text, fontSize = 17.sp, style = TabularNumbers, maxLines = 1)
    }
}
