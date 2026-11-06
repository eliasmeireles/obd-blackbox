package io.github.eliasmeireles.obdblackbox.ui

import io.github.eliasmeireles.obdblackbox.ui.theme.DialogSystemBars
import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
fun VideoRecordingNotice(onDismiss: () -> Unit, onStart: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { DialogSystemBars(); Text(text(R.string.msg_gravar_tela_e_microfone)) },
        text = {
            Text(text(R.string.msg_grava_a_tela_do_app_e_o_som_do_microfone_do_celular_inclui) +
                text(R.string.msg_os_videos_ficam_no_celular_o_android_sempre_pede_autorizac))
        },
        confirmButton = { TextButton(onClick = onStart) { Text(text(R.string.msg_continuar)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(text(R.string.msg_cancelar)) } },
    )
}
