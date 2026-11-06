package io.github.eliasmeireles.obdblackbox.ui

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.eliasmeireles.obdblackbox.data.SourceStatus
import io.github.eliasmeireles.obdblackbox.session.DashboardState
import io.github.eliasmeireles.obdblackbox.data.video.BlackBoxState
import io.github.eliasmeireles.obdblackbox.ui.theme.TabularNumbers
import io.github.eliasmeireles.obdblackbox.ui.theme.AppColors

/** Screens of the app; the Painel (dashboard) is the home screen, the others open from the menu. */
enum class Tab(private val labelId: Int) { DASHBOARD(R.string.msg_painel), DIAGNOSTICS(R.string.msg_dados), VIDEOS(R.string.msg_videos_salvos);
    val label: String get() = text(labelId)
}

@Composable
fun TopBar(
    state: DashboardState,
    source: SourceStatus,
    video: BlackBoxState = BlackBoxState(),
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        val (label, color) = sourceLabel(source)
        Dot(color)
        Spacer(Modifier.width(8.dp))
        Text(
            label, color = AppColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (video.recording) {
            Spacer(Modifier.width(8.dp))
            Text(text(R.string.msg_rec_value, (shortElapsed(video.elapsedMs)).toString()), color = AppColors.Critical, fontSize = 11.sp, style = TabularNumbers, maxLines = 1)
        } else if (state.recordingName != null) {
            Spacer(Modifier.width(12.dp))
            Dot(AppColors.Critical, 8.dp)
            Spacer(Modifier.width(6.dp))
            Text(elapsed(state.trip.elapsedMs), color = AppColors.Muted, fontSize = 13.sp, style = TabularNumbers)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            trailing()
        }
    }
}

@Composable
private fun Dot(color: Color, size: Dp = 10.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) AppColors.Background else AppColors.Text,
        fontSize = 14.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) AppColors.Accent else AppColors.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** m:ss under an hour, h:mm:ss after: keeps the REC label short next to the status. */
private fun shortElapsed(ms: Long): String {
    val total = ms / 1000
    val (h, m, s) = Triple(total / 3600, total % 3600 / 60, total % 60)
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
