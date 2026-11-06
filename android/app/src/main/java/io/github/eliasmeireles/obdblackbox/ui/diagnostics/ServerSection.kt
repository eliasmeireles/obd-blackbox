package io.github.eliasmeireles.obdblackbox.ui.diagnostics

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.eliasmeireles.obdblackbox.ui.theme.AppColors
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import io.github.eliasmeireles.obdblackbox.data.upload.UploadStatus
import io.github.eliasmeireles.obdblackbox.ui.clockTime

data class ServerSettings(val url: String, val apiKey: String, val onSave: (String) -> Unit)

internal fun LazyListScope.serverSection(upload: UploadStatus, settings: ServerSettings) {
    item { SectionTitle(text(R.string.msg_servidor)) }
    if (settings.url.isBlank()) {
        item {
            SettingCard(text(R.string.msg_servidor)) {
                Text(text(R.string.msg_envio_ao_servidor_desativado_nesta_versao_sem_url_do_servi), color = AppColors.Muted, fontSize = 13.sp)
            }
        }
        return
    }
    item {
        SettingCard(text(R.string.msg_endereco)) {
            Text(settings.url, color = AppColors.Muted, fontSize = 13.sp)
        }
    }
    item { ApiKeyEditor(settings) }
    item { ValueRow(text(R.string.msg_chave_de_acesso), if (upload.configured) text(R.string.msg_configurada) else text(R.string.msg_nao_configurada), "") }
    item { ValueRow(text(R.string.msg_leituras_na_fila), upload.pending.toString(), "") }
    item { ValueRow(text(R.string.msg_ultimo_envio), upload.lastSuccessMs?.let(::clockTime) ?: "—", "") }
    item { ValueRow(text(R.string.msg_ultimo_erro), upload.lastError ?: "—", "") }
}

@Composable
private fun ApiKeyEditor(settings: ServerSettings) {
    // The key is kept only in app-private preferences, never in saved instance state or logs.
    var key by remember { mutableStateOf(settings.apiKey) }
    var saved by remember { mutableStateOf(false) }
    SettingCard(text(R.string.msg_chave_de_acesso)) {
        OutlinedTextField(value = key, onValueChange = { key = it; saved = false }, label = { Text(text(R.string.msg_api_key)) },
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { settings.onSave(key); saved = true }, shape = RoundedCornerShape(10.dp)) {
            Text(text(R.string.msg_salvar_chave), color = AppColors.Accent)
        }
        if (saved) Text(text(R.string.msg_chave_salva), color = AppColors.Good, fontSize = 13.sp)
    }
}
