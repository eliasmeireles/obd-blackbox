package io.github.eliasmeireles.obdblackbox.ui

import io.github.eliasmeireles.obdblackbox.i18n.TextResources
import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import androidx.compose.ui.graphics.Color
import io.github.eliasmeireles.obdblackbox.data.upload.UploadStatus
import io.github.eliasmeireles.obdblackbox.ui.theme.AppColors
import java.text.NumberFormat
import java.util.Locale

private val count get() = NumberFormat.getIntegerInstance(TextResources.locale())

/** Short text + color telling whether the data is reaching the server. */
fun uploadLabel(status: UploadStatus): Pair<String, Color> = when {
    !status.enabled -> text(R.string.msg_envio_ao_servidor_desativado_nesta_versao_sem_url_do_servi) to AppColors.Muted
    !status.configured -> text(R.string.msg_servidor_nao_configurado) to AppColors.Muted
    status.lastError != null -> text(R.string.msg_sem_envio_fila_value, (count.format(status.pending)).toString()) to AppColors.Warning
    status.pending > PENDING_WARNING -> text(R.string.msg_enviando_fila_value, (count.format(status.pending)).toString()) to AppColors.Warning
    status.live -> text(R.string.msg_ao_vivo) to AppColors.Good
    status.lastSuccessMs != null -> text(R.string.msg_enviado_value, (clockTime(status.lastSuccessMs)).toString()) to AppColors.Good
    else -> text(R.string.msg_aguardando) to AppColors.Muted
}

private const val PENDING_WARNING = 50L
