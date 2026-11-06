package io.github.eliasmeireles.obdblackbox.ui

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.eliasmeireles.obdblackbox.data.upload.UploadStatus
import io.github.eliasmeireles.obdblackbox.ui.theme.AppColors

/** Portrait bottom bar: upload status and the last event on the left, a compact Marcar evento on the right. */
@Composable
fun ActionBar(actions: Actions, upload: UploadStatus) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            val (label, color) = uploadLabel(upload)
            Text(label, color = color, fontSize = 13.sp, maxLines = 1)
            actions.lastEvent?.let { Text(describeEvent(it), color = AppColors.Muted, fontSize = 12.sp, maxLines = 2) }
        }
        if (actions.canSimulateStall) {
            OutlinedButton(
                onClick = actions.onSimulateStall, modifier = Modifier.height(44.dp), shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, AppColors.Warning), contentPadding = PaddingValues(horizontal = 14.dp),
            ) { Text(text(R.string.msg_simular_falha), color = AppColors.Warning, fontSize = 14.sp) }
        }
        MarkEventButton(actions.onMarkEvent)
    }
}

/** Compact, still easy to hit while parked: the button the driver uses at the moment of a failure. */
@Composable
fun MarkEventButton(onMarkEvent: () -> Unit, height: Dp = 44.dp) {
    val haptics = LocalHapticFeedback.current
    Button(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onMarkEvent()
        },
        modifier = Modifier.height(height),
        shape = RoundedCornerShape(height / 2),
        contentPadding = PaddingValues(horizontal = 18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = AppColors.Background),
    ) { Text(text(R.string.msg_marcar_evento), fontWeight = FontWeight.Bold, fontSize = 15.sp) }
}
