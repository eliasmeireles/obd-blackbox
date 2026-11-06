package io.github.eliasmeireles.obdblackbox.ui

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import androidx.compose.ui.graphics.Color
import io.github.eliasmeireles.obdblackbox.data.SourceStatus
import io.github.eliasmeireles.obdblackbox.ui.theme.AppColors

/** Short top-bar text + color for where the data comes from. */
fun sourceLabel(status: SourceStatus): Pair<String, Color> = when (status) {
    SourceStatus.Simulated -> text(R.string.msg_simulacao) to AppColors.Warning
    SourceStatus.Connecting -> text(R.string.source_connecting) to AppColors.Warning
    is SourceStatus.Disconnected -> text(R.string.source_disconnected) to AppColors.Critical
    is SourceStatus.Connected -> when {
        status.bus == "silent" -> text(R.string.source_off) to AppColors.Warning
        status.simulatedCar -> text(R.string.source_test) to AppColors.Good
        else -> text(R.string.source_connected) to AppColors.Good
    }
}

/** Longer description for the Dados tab. */
fun sourceDescription(status: SourceStatus): String = when (status) {
    SourceStatus.Simulated -> text(R.string.msg_simulador_interno_do_app)
    SourceStatus.Connecting -> text(R.string.msg_procurando_a_ponte)
    is SourceStatus.Disconnected -> text(R.string.msg_sem_conexao_value, (status.reason).toString())
    is SourceStatus.Connected -> buildString {
        append(text(R.string.msg_conectada_firmware_value, (status.firmware).toString()))
        if (status.simulatedCar) append(text(R.string.msg_carro_simulado))
        status.bus?.let { append(text(R.string.msg_barramento_value, (it).toString())) }
    }
}
