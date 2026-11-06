package io.github.eliasmeireles.obdblackbox.ui

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import io.github.eliasmeireles.obdblackbox.data.video.BlackBoxState
import io.github.eliasmeireles.obdblackbox.domain.events.EngineStop
import io.github.eliasmeireles.obdblackbox.domain.events.RecordedEvent
import io.github.eliasmeireles.obdblackbox.ui.theme.AppColors

/** Control actions shared by the bottom bar (portrait), the top bar (landscape) and the menu. */
data class Actions(
    val lastEvent: RecordedEvent?,
    val canSimulateStall: Boolean,
    val onMarkEvent: () -> Unit,
    val onSimulateStall: () -> Unit,
    val video: BlackBoxState,
    val onVideoToggle: () -> Unit,
)

/**
 * The app menu (top bar, both orientations): the other screens (Dados, Vídeos salvos), the screen recording and,
 * in simulation, the scripted stall. The Painel is the home screen; the menu offers it back from the others.
 */
@Composable
fun MainMenu(screen: Tab, onScreen: (Tab) -> Unit, actions: Actions, videoCount: Int) {
    var open by remember { mutableStateOf(false) }
    Box {
        TabChip(text(R.string.msg_menu), selected = open) { open = true }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            // A focusable popup takes focus from the activity and brings the system bars back.
            properties = PopupProperties(focusable = false),
            modifier = Modifier.background(AppColors.Surface).widthIn(min = 240.dp),
        ) {
            fun go(target: Tab) {
                onScreen(target)
                open = false
            }
            if (screen != Tab.DASHBOARD) MenuAction(text(R.string.msg_painel), AppColors.Text) { go(Tab.DASHBOARD) }
            MenuAction(text(R.string.msg_dados), AppColors.Text, selected = screen == Tab.DIAGNOSTICS) { go(Tab.DIAGNOSTICS) }
            MenuAction(text(R.string.msg_videos_salvos_value, (videoCount).toString()), AppColors.Text, selected = screen == Tab.VIDEOS) { go(Tab.VIDEOS) }
            HorizontalDivider(color = AppColors.SurfaceHigh)
            MenuAction(if (actions.video.recording) text(R.string.msg_parar_gravacao) else text(R.string.msg_gravar_tela), AppColors.Critical) {
                actions.onVideoToggle()
                open = false
            }
            Text(
                actions.video.message, color = AppColors.Muted, fontSize = 12.sp,
                modifier = Modifier.widthIn(max = 300.dp).padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (actions.canSimulateStall) {
                MenuAction(text(R.string.msg_simular_falha), AppColors.Warning) {
                    actions.onSimulateStall()
                    open = false
                }
            }
            actions.lastEvent?.let { event ->
                HorizontalDivider(color = AppColors.SurfaceHigh)
                Text(
                    describeEvent(event),
                    color = AppColors.Muted,
                    fontSize = 13.sp,
                    modifier = Modifier.widthIn(max = 320.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun MenuAction(label: String, color: Color, selected: Boolean = false, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                if (selected) text(R.string.msg_value_291, (label).toString()) else label,
                color = if (selected) AppColors.Accent else color,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        },
        onClick = onClick,
    )
}

fun describeEvent(event: RecordedEvent): String {
    val time = clockTime(event.timestampMs)
    return when {
        !event.automatic -> text(R.string.msg_evento_marcado_as_value, (time).toString())
        event.label.startsWith(EngineStop.KEY_OFF.eventLabel) -> text(R.string.msg_motor_desligado_pela_chave_as_value_marcado_automaticament, (time).toString())
        else -> text(R.string.msg_motor_desligou_sozinho_as_value_marcado_automaticamente, (time).toString())
    }
}
