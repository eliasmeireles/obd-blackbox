package io.github.eliasmeireles.obdblackbox.ui.diagnostics

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.eliasmeireles.obdblackbox.data.elm.DiagnosticSnapshot
import io.github.eliasmeireles.obdblackbox.domain.format.formatPtOrDash
import io.github.eliasmeireles.obdblackbox.domain.obd.DtcDescriptions
import io.github.eliasmeireles.obdblackbox.ui.theme.AppColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** State and actions for the ECU section; the dialog is local, operations survive recomposition. */
data class EngineDiagnostics(
    val supported: Boolean,
    val snapshot: DiagnosticSnapshot?,
    val busy: Boolean,
    val result: String?,
    val onRead: () -> Unit,
    val onClear: () -> Unit,
)

private val readingTime = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZoneId.systemDefault())

internal fun LazyListScope.engineSection(engine: EngineDiagnostics, connected: Boolean) {
    if (!engine.supported) return
    item { SectionTitle(text(R.string.msg_central_do_motor)) }
    item { EngineActions(engine, connected) }
    val snapshot = engine.snapshot
    if (snapshot == null) {
        item { ValueRow(text(R.string.msg_diagnostico), text(R.string.msg_aguardando_leitura_da_central), "") }
        return
    }
    item { ValueRow(text(R.string.msg_leitura), readingTime.format(Instant.ofEpochMilli(snapshot.t)), "") }
    item { ValueRow(text(R.string.msg_luz_da_injecao), when (snapshot.milOn) { true -> text(R.string.msg_acesa); false -> text(R.string.msg_apagada); null -> text(R.string.msg_nao_informada) }, "") }
    item { ValueRow(text(R.string.msg_quantidade_de_codigos), snapshot.dtcCount?.toString() ?: text(R.string.msg_nao_informada), "") }
    val distance = snapshot.values["distance_mil_km"]
    item { ValueRow(text(R.string.msg_distancia_com_a_luz_acesa), distance.formatPtOrDash(0), text(R.string.unit_distance)) }
    codeRows(text(R.string.msg_gravados), snapshot.stored)
    codeRows(text(R.string.msg_pendentes), snapshot.pending)
    codeRows(text(R.string.msg_permanentes), snapshot.permanent)
    item { SectionTitle(text(R.string.msg_foto_do_momento_da_falha)) }
    val frame = snapshot.freezeFrame
    item { ValueRow(text(R.string.msg_codigo_que_gravou_a_foto), frame?.dtc ?: text(R.string.msg_sem_foto_disponivel), "") }
    frame?.let { valueRows(it.values) }
    item { SectionTitle(text(R.string.msg_autotestes)) }
    if (snapshot.monitors.isEmpty()) item { ValueRow(text(R.string.msg_autotestes), text(R.string.msg_nao_informados), "") }
    snapshot.monitors.forEach { monitor ->
        item { ValueRow(DiagnosticLabels.name(monitor.name), when { !monitor.available -> text(R.string.msg_nao_existe); monitor.complete -> text(R.string.msg_concluido); else -> text(R.string.msg_pendente) }, "") }
    }
    item { ValueRow(text(R.string.msg_sistema_de_combustivel), snapshot.fuelSystem?.let(DiagnosticLabels::name) ?: text(R.string.msg_nao_informado), "") }
    item { SectionTitle(text(R.string.msg_identificacao)) }
    mapOf(text(R.string.msg_vin) to snapshot.vin, text(R.string.msg_calibracao) to snapshot.calibrationId, text(R.string.msg_nome_da_central) to snapshot.ecuName,
        text(R.string.msg_endereco_da_central) to snapshot.ecuId, text(R.string.msg_protocolo_obd) to snapshot.protocol, text(R.string.msg_adaptador) to snapshot.adapter).forEach { (label, value) ->
        item { DiagnosticTextRow(label, value ?: text(R.string.msg_nao_informado)) }
    }
    item { DiagnosticTextRow(text(R.string.msg_pids_suportados), snapshot.supportedPids.joinToString(", ") { "%02X".format(it) }) }
    item { SectionTitle(text(R.string.msg_outros_valores_da_central)) }
    valueRows(snapshot.values.filterKeys { it != "distance_mil_km" })
}

private fun LazyListScope.codeRows(label: String, codes: List<String>?) {
    item { ValueRow(label, when { codes == null -> text(R.string.msg_nao_informados); codes.isEmpty() -> text(R.string.msg_nenhum); else -> codes.size.toString() }, "") }
    codes.orEmpty().forEach { code -> item { DiagnosticTextRow(code, DtcDescriptions.describe(code)) } }
}

private fun LazyListScope.valueRows(values: Map<String, Double>) {
    values.forEach { (key, value) ->
        val (label, unit) = DiagnosticLabels.value(key)
        item { ValueRow(label, value.formatPtOrDash(2), unit) }
    }
}

@Composable
private fun EngineActions(engine: EngineDiagnostics, connected: Boolean) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = engine.onRead, enabled = connected && !engine.busy, modifier = Modifier.fillMaxWidth()) {
            Text(text(R.string.msg_ler_de_novo), color = AppColors.Accent)
        }
        OutlinedButton(onClick = { confirming = true }, enabled = connected && !engine.busy, modifier = Modifier.fillMaxWidth()) {
            Text(text(R.string.msg_apagar_codigos_de_falha_439), color = AppColors.Warning)
        }
        if (engine.busy) Text(text(R.string.msg_lendo_a_central_aguarde), color = AppColors.Accent)
        engine.result?.let { Text(it, color = AppColors.Text) }
    }
    if (confirming) ClearCodesDialog(onDismiss = { confirming = false }, onConfirm = {
        confirming = false
        engine.onClear()
    })
}

@Composable
private fun DiagnosticTextRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AppColors.Surface).padding(12.dp)) {
        Text(label, color = AppColors.Accent)
        Text(value, color = AppColors.Text)
    }
}
