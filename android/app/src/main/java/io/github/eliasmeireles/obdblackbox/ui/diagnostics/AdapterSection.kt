package io.github.eliasmeireles.obdblackbox.ui.diagnostics

import io.github.eliasmeireles.obdblackbox.ui.theme.DialogSystemBars
import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import io.github.eliasmeireles.obdblackbox.ui.ProbeState
import io.github.eliasmeireles.obdblackbox.data.elm.ProbeResult
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.eliasmeireles.obdblackbox.data.bridge.BluetoothTarget
import io.github.eliasmeireles.obdblackbox.data.elm.FinderState
import io.github.eliasmeireles.obdblackbox.data.elm.FoundAdapter
import io.github.eliasmeireles.obdblackbox.data.elm.bluetoothPermissions
import io.github.eliasmeireles.obdblackbox.ui.theme.AppColors

/** The ready-made ELM327 adapter (KONNWEI, Uscan…) and the picker that finds and tests it. */
data class AdapterSettings(
    val selected: BluetoothTarget?,
    val finder: FinderState,
    val probe: ProbeState,
    val onSearch: () -> Unit,
    val onStop: () -> Unit,
    val onTest: (FoundAdapter) -> Unit,
    val onProbeSeen: () -> Unit,
    val onForget: () -> Unit,
)

@Composable
internal fun AdapterRow(settings: AdapterSettings) {
    var picking by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        denied = granted.values.any { !it }
        if (!denied) {
            settings.onProbeSeen()
            settings.onSearch()
            picking = true
        }
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AppColors.Surface).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text(R.string.msg_adaptador_obd_bluetooth), color = AppColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        val selected = settings.selected
        Text(
            selected?.let { text(R.string.msg_value_value_value, (it.name).toString(), (it.mode.label).toString(), (it.address).toString()) }
                ?: text(R.string.msg_nenhum_escolha_o_adaptador_elm327_konnwei_uscan_com_a_igni),
            color = AppColors.Muted,
            fontSize = 13.sp,
        )
        if (denied) Text(text(R.string.msg_sem_a_permissao_de_bluetooth_o_app_nao_encontra_o_adaptado), color = AppColors.Warning, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { permissions.launch(bluetoothPermissions()) }, shape = RoundedCornerShape(10.dp)) {
                Text(if (selected == null) text(R.string.msg_procurar_aparelhos) else text(R.string.msg_trocar), color = AppColors.Accent)
            }
            if (selected != null) {
                OutlinedButton(onClick = settings.onForget, shape = RoundedCornerShape(10.dp)) { Text(text(R.string.msg_remover), color = AppColors.Muted) }
            }
        }
    }
    if (picking) {
        AdapterPicker(
            state = settings.finder,
            probe = settings.probe,
            onTest = settings.onTest,
            onSearchAgain = {
                settings.onProbeSeen()
                settings.onSearch()
            },
            onDismiss = {
                settings.onStop()
                settings.onProbeSeen()
                picking = false
            },
        )
    }
}

/**
 * Every device the phone sees, like its Bluetooth screen. Tapping one tests it (connects and asks for the ELM327
 * identity); it is kept only if it works, otherwise the reason and what to try are shown.
 */
@Composable
private fun AdapterPicker(
    state: FinderState,
    probe: ProbeState,
    onTest: (FoundAdapter) -> Unit,
    onSearchAgain: () -> Unit,
    onDismiss: () -> Unit,
) {
    val testing = probe is ProbeState.Testing
    val succeeded = (probe as? ProbeState.Done)?.result is ProbeResult.Compatible
    AlertDialog(
        onDismissRequest = { if (!testing) onDismiss() },
        containerColor = AppColors.SurfaceHigh,
        title = { DialogSystemBars(); Text(text(R.string.msg_aparelhos_bluetooth), color = AppColors.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ProbePanel(probe)
                if (!succeeded) {
                    val hint = when {
                        state.problem != null -> state.problem
                        state.scanning -> text(R.string.msg_procurando_aparelhos_o_adaptador_precisa_estar_na_tomada_o)
                        state.adapters.isEmpty() -> text(R.string.msg_nada_encontrado_confira_se_o_adaptador_acendeu_e_procure_d)
                        else -> text(R.string.msg_toque_no_aparelho_para_testar_os_que_parecem_adaptador_obd)
                    }
                    Text(hint, color = AppColors.Muted, fontSize = 13.sp)
                    LazyColumn(Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(state.adapters, key = { it.address }) { found -> AdapterItem(found, enabled = !testing) { onTest(found) } }
                    }
                }
            }
        },
        confirmButton = {
            when {
                succeeded -> TextButton(onClick = onDismiss) { Text(text(R.string.msg_pronto), color = AppColors.Accent) }
                !state.scanning && !testing -> TextButton(onClick = onSearchAgain) { Text(text(R.string.msg_procurar_de_novo), color = AppColors.Accent) }
            }
        },
        dismissButton = {
            if (!testing && !succeeded) TextButton(onClick = onDismiss) { Text(text(R.string.msg_fechar), color = AppColors.Muted) }
        },
    )
}

@Composable
private fun ProbePanel(probe: ProbeState) {
    when (probe) {
        ProbeState.Idle -> Unit
        is ProbeState.Testing -> Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AppColors.Surface).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(20.dp), color = AppColors.Accent, strokeWidth = 2.dp)
            Text(text(R.string.msg_testando_value_conectando_e_pedindo_a_identificacao_do_ada, (probe.name).toString()), color = AppColors.Text, fontSize = 14.sp)
        }
        is ProbeState.Done -> when (val result = probe.result) {
            is ProbeResult.Compatible -> ResultBox(
                AppColors.Good,
                text(R.string.msg_funcionou_value_pelo_value, (result.identity).toString(), (result.target.mode.label).toString()),
                text(R.string.msg_value_ficou_salvo_como_adaptador_o_app_ja_esta_conectando_, (probe.name).toString()),
            )
            is ProbeResult.Failed -> ResultBox(AppColors.Critical, text(R.string.msg_nao_deu_certo_com_value_value, (probe.name).toString(), (result.reason).toString()), result.hint)
        }
    }
}

@Composable
private fun ResultBox(color: Color, title: String, detail: String) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AppColors.Surface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        if (detail.isNotBlank()) Text(detail, color = AppColors.Muted, fontSize = 13.sp)
    }
}

@Composable
private fun AdapterItem(found: FoundAdapter, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AppColors.Surface).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            found.displayName,
            color = when {
                !enabled -> AppColors.Muted
                found.looksLikeObd -> AppColors.Accent
                found.name == null -> AppColors.Muted
                else -> AppColors.Text
            },
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        val details = listOfNotNull(
            found.kind,
            text(R.string.msg_pareado).takeIf { found.paired },
            found.rssi?.let { text(R.string.msg_sinal_value_dbm, (it).toString()) },
            found.address.takeIf { found.name != null },
        )
        Text(details.joinToString(" · "), color = AppColors.Muted, fontSize = 12.sp)
    }
}
