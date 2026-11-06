package io.github.eliasmeireles.obdblackbox.ui.diagnostics

import io.github.eliasmeireles.obdblackbox.ui.theme.DialogSystemBars
import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import io.github.eliasmeireles.obdblackbox.ui.theme.AppColors

@Composable
internal fun ClearCodesDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppColors.SurfaceHigh,
        title = {
            DialogSystemBars()
            Text(text(R.string.msg_apagar_codigos_de_falha), color = AppColors.Text)
        },
        text = { Text(
            text(R.string.msg_apaga_os_codigos_e_a_foto_do_momento_da_falha_na_central_c) +
                text(R.string.msg_reinicia_os_autotestes_a_luz_da_injecao_apaga_e_volta_se_o) +
                text(R.string.msg_precisa_da_ignicao_ligada_e_do_motor_desligado),
            color = AppColors.Text,
        ) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(text(R.string.msg_apagar), color = AppColors.Warning) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(text(R.string.msg_cancelar), color = AppColors.Muted) } },
    )
}
