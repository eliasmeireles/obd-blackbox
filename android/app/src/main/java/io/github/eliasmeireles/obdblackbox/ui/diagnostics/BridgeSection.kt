package io.github.eliasmeireles.obdblackbox.ui.diagnostics

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.eliasmeireles.obdblackbox.data.SourceStatus
import io.github.eliasmeireles.obdblackbox.ui.clockTime
import io.github.eliasmeireles.obdblackbox.ui.sourceDescription
import io.github.eliasmeireles.obdblackbox.ui.theme.AppColors

/** Bridge connection and ECU trouble codes. Reading codes never clears them. */
fun LazyListScope.bridgeSection(source: SourceStatus, dtcs: BridgeDtcs) {
    item { SectionTitle(text(R.string.msg_ponte)) }
    item { ValueRow(sourceDescription(source), "", "") }
    if (!dtcs.canRead) return
    item { SectionTitle(text(R.string.msg_codigos_de_falha)) }
    item {
        OutlinedButton(
            onClick = dtcs.onRead,
            enabled = source is SourceStatus.Connected,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) { Text(text(R.string.msg_ler_codigos_de_falha_sem_apagar), color = AppColors.Accent) }
    }
    val reading = dtcs.reading ?: return
    item { ValueRow(text(R.string.msg_lidos_as), clockTime(reading.timestampMs), "") }
    item { ValueRow(text(R.string.msg_gravados), reading.stored.ifEmpty { listOf(text(R.string.msg_nenhum)) }.joinToString(", "), "") }
    item { ValueRow(text(R.string.msg_pendentes), reading.pending.ifEmpty { listOf(text(R.string.msg_nenhum)) }.joinToString(", "), "") }
}
