package io.github.eliasmeireles.obdblackbox.ui

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import android.Manifest
import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import io.github.eliasmeireles.obdblackbox.ObdBlackBoxApp
import io.github.eliasmeireles.obdblackbox.data.logs.LogLevel
import io.github.eliasmeireles.obdblackbox.service.BlackBoxService

/** Launchers are registered with the activity, so a configuration change preserves pending consent. */
class ScreenRecordingConsent(private val activity: ComponentActivity) {
    private val container get() = (activity.application as ObdBlackBoxApp).container
    private var requesting = false
    private val projection = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        requesting = false
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            runCatching { BlackBoxService.start(activity, result.resultCode, data) }.onFailure {
                container.log.log(LogLevel.ERROR, "video", "failed to start service: ${it.message}")
                container.blackBox.stopped(R.string.msg_gravacao_parada_nao_foi_possivel_iniciar)
            }
        } else container.blackBox.stopped(R.string.msg_gravacao_parada_captura_nao_autorizada)
    }
    private val permissions = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.all { it }) {
            projection.launch(activity.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
        } else {
            requesting = false
            container.blackBox.stopped(R.string.msg_gravacao_parada_autorize_o_microfone_para_gravar)
        }
    }

    fun toggle() {
        if (container.blackBox.state.value.recording) {
            BlackBoxService.stop(activity)
        } else if (!requesting) {
            requesting = true
            val required = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT <= 28) required += Manifest.permission.WRITE_EXTERNAL_STORAGE
            permissions.launch(required.toTypedArray())
        }
    }
}
