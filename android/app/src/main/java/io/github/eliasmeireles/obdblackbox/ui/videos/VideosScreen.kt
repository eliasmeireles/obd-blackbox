package io.github.eliasmeireles.obdblackbox.ui.videos

import io.github.eliasmeireles.obdblackbox.i18n.quantity
import io.github.eliasmeireles.obdblackbox.i18n.TextResources
import io.github.eliasmeireles.obdblackbox.ui.theme.DialogSystemBars
import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.eliasmeireles.obdblackbox.data.video.RecentRecording
import io.github.eliasmeireles.obdblackbox.data.video.SavedVideo
import io.github.eliasmeireles.obdblackbox.data.video.VideoNames
import io.github.eliasmeireles.obdblackbox.ui.theme.TabularNumbers
import io.github.eliasmeireles.obdblackbox.ui.theme.AppColors
import java.text.SimpleDateFormat
import kotlinx.coroutines.delay
import java.util.Date
import java.util.Locale

/** What the Vídeos screen can do with the gallery clips and the recent (ring buffer) recordings. */
data class VideoActions(
    val onRefresh: () -> Unit,
    val onPlay: (SavedVideo) -> Unit,
    val onShare: (List<SavedVideo>) -> Unit,
    val onDelete: (SavedVideo) -> Unit,
    val onPlayRecent: (RecentRecording) -> Unit,
    val onShareRecent: (RecentRecording) -> Unit,
    val onSaveRecent: (RecentRecording) -> Unit,
    val onSaveAllRecent: () -> Unit,
    val onDeleteAllRecent: () -> Unit,
)

/**
 * Saved clips (gallery) and recent recordings (the last ~2 h of the black box, with or without an event), newest
 * first, to watch, share (Drive, WhatsApp…), save to the gallery or delete.
 */
@Composable
fun VideosScreen(videos: List<SavedVideo>, recent: List<RecentRecording>, actions: VideoActions, modifier: Modifier = Modifier) {
    LaunchedEffect(Unit) {
        while (true) {
            actions.onRefresh()
            delay(REFRESH_MS)
        }
    }
    var deleting by remember { mutableStateOf<SavedVideo?>(null) }
    var clearingRecent by remember { mutableStateOf(false) }
    val inGallery = videos.map { it.name }.toSet()
    LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(text(R.string.msg_salvas_na_galeria_value, (videos.size).toString()), color = AppColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text(R.string.msg_trechos_com_evento_marcar_evento_motor_apagou_sozinho_e_os) +
                            text(R.string.msg_compartilhar_abre_a_lista_do_android_google_drive_salvar_n),
                        color = AppColors.Muted,
                        fontSize = 13.sp,
                    )
                }
                if (videos.size > 1) TextButton(onClick = { actions.onShare(videos) }) { Text(text(R.string.msg_compartilhar_todas), color = AppColors.Accent) }
            }
        }
        if (videos.isEmpty()) {
            item {
                Text(
                    text(R.string.msg_nenhum_video_na_galeria_ainda_com_gravar_tela_ligado_marca) +
                        text(R.string.msg_motor_apagar_sozinho_o_app_salva_sozinho_as_gravacoes_sem_),
                    color = AppColors.Muted,
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AppColors.Surface).padding(16.dp),
                )
            }
        }
        items(videos, key = { it.uri.toString() }) { video -> VideoCard(video, actions, onDelete = { deleting = video }) }
        item {
            Column(Modifier.padding(top = 12.dp)) {
                Text(
                    quantity(R.plurals.recent_recording_count, recent.size, recent.size),
                    color = AppColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                )
                if (recent.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        val unsaved = recent.count { it.galleryName !in inGallery }
                        TextButton(onClick = actions.onSaveAllRecent, enabled = unsaved > 0) {
                            Text(if (unsaved > 0) text(R.string.msg_salvar_todas_na_galeria_value, (unsaved).toString()) else text(R.string.msg_todas_ja_na_galeria),
                                color = if (unsaved > 0) AppColors.Accent else AppColors.Muted)
                        }
                        TextButton(onClick = { clearingRecent = true }) { Text(text(R.string.msg_apagar_todas), color = AppColors.Muted) }
                    }
                }
                Text(
                    text(R.string.msg_tudo_o_que_a_caixa_preta_gravou_nas_ultimas_2_horas_em_tre) +
                        text(R.string.msg_reaproveitado_toque_em_salvar_na_galeria_para_manter_um_tr),
                    color = AppColors.Muted,
                    fontSize = 13.sp,
                )
            }
        }
        if (recent.isEmpty()) {
            item { Text(text(R.string.msg_nenhuma_gravacao_recente), color = AppColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp)) }
        }
        items(recent, key = { "recent-" + it.id }) { recording -> RecentCard(recording, recording.galleryName in inGallery, actions) }
    }
    if (clearingRecent) {
        AlertDialog(
            onDismissRequest = { clearingRecent = false },
            containerColor = AppColors.SurfaceHigh,
            title = { DialogSystemBars(); Text(text(R.string.msg_apagar_as_gravacoes_recentes), color = AppColors.Text) },
            text = {
                val unsaved = recent.count { it.galleryName !in inGallery }
                Text(
                    quantity(R.plurals.delete_recent_count, recent.size, recent.size) +
                        (if (unsaved > 0) quantity(R.plurals.unsaved_recording_count, unsaved, unsaved) else "") +
                        text(R.string.msg_os_videos_ja_salvos_na_galeria_continuam_la_a_gravacao_em_),
                    color = AppColors.Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    actions.onDeleteAllRecent()
                    clearingRecent = false
                }) { Text(text(R.string.msg_apagar_todas), color = AppColors.Critical) }
            },
            dismissButton = { TextButton(onClick = { clearingRecent = false }) { Text(text(R.string.msg_cancelar), color = AppColors.Muted) } },
        )
    }
    deleting?.let { video ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = AppColors.SurfaceHigh,
            title = { DialogSystemBars(); Text(text(R.string.msg_apagar_gravacao), color = AppColors.Text) },
            text = { Text(text(R.string.msg_value_value_o_video_sai_da_galeria_do_celular_e_nao_pode_s, (takenAt(video)).toString(), (video.reason).toString()), color = AppColors.Muted) },
            confirmButton = {
                TextButton(onClick = {
                    actions.onDelete(video)
                    deleting = null
                }) { Text(text(R.string.msg_apagar), color = AppColors.Critical) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(text(R.string.msg_cancelar), color = AppColors.Muted) } },
        )
    }
}

@Composable
private fun VideoCard(video: SavedVideo, actions: VideoActions, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AppColors.Surface).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(text(R.string.msg_value_value_491, (takenAt(video)).toString(), (video.reason).toString()), color = AppColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(text(R.string.msg_value_value_491, (duration(video.durationMs)).toString(), (size(video.sizeBytes)).toString()), color = AppColors.Muted, fontSize = 13.sp, style = TabularNumbers)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { actions.onPlay(video) }) { Text(text(R.string.msg_assistir), color = AppColors.Accent) }
            TextButton(onClick = { actions.onShare(listOf(video)) }) { Text(text(R.string.msg_compartilhar), color = AppColors.Accent) }
            TextButton(onClick = onDelete) { Text(text(R.string.msg_apagar), color = AppColors.Muted) }
        }
    }
}

@Composable
private fun RecentCard(recording: RecentRecording, saved: Boolean, actions: VideoActions) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AppColors.Surface).padding(horizontal = 14.dp, vertical = 10.dp)) {
        val tag = recording.labels.firstOrNull()?.let { " · " + VideoNames.reason("obd-0-0-$it.mp4") }.orEmpty()
        Text("${format(recording.startMs)}$tag", color = AppColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(text(R.string.msg_value_value_491, (duration(recording.durationMs)).toString(), (size(recording.sizeBytes)).toString()), color = AppColors.Muted, fontSize = 13.sp, style = TabularNumbers)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { actions.onPlayRecent(recording) }) { Text(text(R.string.msg_assistir), color = AppColors.Accent) }
            TextButton(onClick = { actions.onShareRecent(recording) }) { Text(text(R.string.msg_compartilhar), color = AppColors.Accent) }
            TextButton(onClick = { actions.onSaveRecent(recording) }, enabled = !saved) {
                Text(if (saved) text(R.string.msg_na_galeria) else text(R.string.msg_salvar_na_galeria), color = if (saved) AppColors.Muted else AppColors.Accent)
            }
        }
    }
}

private fun format(ms: Long): String = SimpleDateFormat("dd/MM HH:mm:ss", TextResources.locale()).format(Date(ms))

private fun takenAt(video: SavedVideo): String = SimpleDateFormat("dd/MM HH:mm:ss", TextResources.locale()).format(Date(video.startedAtMs))

private fun duration(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

private fun size(bytes: Long): String = "%.1f MB".format(TextResources.locale(), bytes / 1_000_000.0)

/** New clips appear while the tab is open (a segment is saved when it ends). */
private const val REFRESH_MS = 10_000L
