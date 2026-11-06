package io.github.eliasmeireles.obdblackbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import io.github.eliasmeireles.obdblackbox.domain.alerts.Alert
import io.github.eliasmeireles.obdblackbox.ui.dashboard.level
import io.github.eliasmeireles.obdblackbox.ui.theme.AppColors

/** Shows the most severe alert; the others are counted and listed in Diagnóstico. */
@Composable
fun AlertBanner(alerts: List<Alert>) {
    val first = alerts.firstOrNull() ?: return
    val color = first.severity.level().color
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(color.copy(alpha = 0.18f)).border(1.dp, color, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⚠", color = color, fontSize = 18.sp)
        Spacer(Modifier.width(8.dp))
        Text(first.message, color = AppColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (alerts.size > 1) Text("+${alerts.size - 1}", color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}
