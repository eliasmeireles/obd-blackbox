package io.github.eliasmeireles.obdblackbox.data.elm

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
/** Tracks ECU replies, not the adapter's AT/NO DATA responses or cached telemetry. */
class BluetoothDropHint(private val clock: () -> Long = System::currentTimeMillis) {
    @Volatile private var lastAnswerMs: Long? = null

    fun ecuAnswered() { lastAnswerMs = clock() }

    fun describe(reason: String): String {
        val answered = lastAnswerMs
        val hint = if (answered != null && clock() - answered in 0..RECENT_ECU_MS) {
            text(R.string.msg_se_outro_app_de_obd_car_scanner_estiver_aberto_feche_o)
        } else text(R.string.msg_o_adaptador_desligou_carro_desligado)
        return text(R.string.msg_value_value, (reason).toString(), (hint).toString())
    }

    private companion object {
        const val RECENT_ECU_MS = 10_000L
    }
}
