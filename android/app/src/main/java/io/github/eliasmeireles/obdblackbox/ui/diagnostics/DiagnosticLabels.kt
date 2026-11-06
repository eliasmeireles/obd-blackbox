package io.github.eliasmeireles.obdblackbox.ui.diagnostics

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import io.github.eliasmeireles.obdblackbox.domain.Signal

internal object DiagnosticLabels {
    private val extras get() = mapOf(
        "obd_standard" to (text(R.string.msg_padrao_obd) to ""), "run_time_s" to (text(R.string.msg_tempo_de_motor_ligado) to text(R.string.unit_seconds)),
        "distance_mil_km" to (text(R.string.msg_distancia_com_a_luz_acesa) to text(R.string.unit_distance)),
        "warmups_since_clear" to (text(R.string.msg_aquecimentos_desde_a_limpeza) to ""),
        "distance_since_clear_km" to (text(R.string.msg_distancia_desde_a_limpeza) to text(R.string.unit_distance)),
        "baro_kpa" to (text(R.string.msg_pressao_atmosferica) to text(R.string.unit_pressure)), "o2_b1s2_v" to (text(R.string.msg_sonda_lambda_b1s2) to text(R.string.unit_voltage)),
        "relative_throttle_pct" to (text(R.string.msg_borboleta_relativa) to text(R.string.unit_percent)), "throttle_b_pct" to (text(R.string.msg_borboleta_b) to text(R.string.unit_percent)),
        "pedal_e_pct" to (text(R.string.msg_pedal_e) to text(R.string.unit_percent)),
    )
    private val names get() = mapOf(
        "misfire" to text(R.string.msg_falhas_de_combustao), "fuel_system" to text(R.string.msg_sistema_de_combustivel), "components" to text(R.string.msg_componentes),
        "catalyst" to text(R.string.msg_catalisador), "heated_catalyst" to text(R.string.msg_catalisador_aquecido), "evap" to text(R.string.msg_sistema_evaporativo),
        "secondary_air" to text(R.string.msg_ar_secundario), "ac_refrigerant" to text(R.string.msg_fluido_do_ar_condicionado), "o2_sensor" to text(R.string.msg_sondas_lambda),
        "o2_heater" to text(R.string.msg_aquecedores_das_sondas), "egr" to text(R.string.msg_recirculacao_de_gases_egr),
        "open_loop_cold" to text(R.string.msg_malha_aberta_motor_frio), "closed_loop" to text(R.string.msg_malha_fechada),
        "open_loop_load" to text(R.string.msg_malha_aberta_carga), "open_loop_fault" to text(R.string.msg_malha_aberta_falha),
        "closed_loop_fault" to text(R.string.msg_malha_fechada_com_falha),
    )
    fun name(key: String) = names[key] ?: key.replace('_', ' ').replaceFirstChar { it.uppercase() }
    fun value(key: String): Pair<String, String> = Signal.entries.firstOrNull { it.csvColumn == key }
        ?.let { it.label to it.unit } ?: extras[key] ?: (name(key) to "")
}
