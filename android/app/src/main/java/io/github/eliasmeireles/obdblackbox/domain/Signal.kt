package io.github.eliasmeireles.obdblackbox.domain

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R

enum class SignalGroup(private val labelId: Int) {
    ENGINE(R.string.msg_motor),
    MIXTURE(R.string.msg_mistura),
    INTAKE(R.string.msg_admissao),
    ELECTRICAL(R.string.msg_eletrica),
    VEHICLE(R.string.msg_veiculo);

    val label: String get() = text(labelId)
}

enum class Signal(
    private val labelId: Int,
    private val unitId: Int,
    val csvColumn: String,
    val decimals: Int,
    val group: SignalGroup,
) {
    SPEED(R.string.msg_velocidade, R.string.unit_speed, "speed_kmh", 0, SignalGroup.VEHICLE),
    RPM(R.string.msg_rotacao, R.string.unit_rpm, "rpm", 0, SignalGroup.ENGINE),
    COOLANT_TEMP(R.string.msg_temperatura_do_motor, R.string.unit_temperature, "coolant_c", 0, SignalGroup.ENGINE),
    ENGINE_LOAD(R.string.msg_carga, R.string.unit_percent, "load_pct", 0, SignalGroup.ENGINE),
    TIMING_ADVANCE(R.string.msg_avanco_de_ignicao, R.string.unit_degrees, "timing_deg", 1, SignalGroup.ENGINE),
    PEDAL(R.string.msg_pedal, R.string.unit_percent, "pedal_pct", 0, SignalGroup.INTAKE),
    THROTTLE(R.string.msg_borboleta, R.string.unit_percent, "throttle_pct", 0, SignalGroup.INTAKE),
    COMMANDED_THROTTLE(R.string.msg_borboleta_comandada, R.string.unit_percent, "cmd_throttle_pct", 0, SignalGroup.INTAKE),
    MAP(R.string.msg_pressao_do_coletor, R.string.unit_pressure, "map_kpa", 0, SignalGroup.INTAKE),
    INTAKE_TEMP(R.string.msg_temperatura_do_ar, R.string.unit_temperature, "intake_c", 0, SignalGroup.INTAKE),
    SHORT_FUEL_TRIM(R.string.msg_correcao_curta_stft, R.string.unit_percent, "stft_pct", 1, SignalGroup.MIXTURE),
    LONG_FUEL_TRIM(R.string.msg_correcao_longa_ltft, R.string.unit_percent, "ltft_pct", 1, SignalGroup.MIXTURE),
    O2_B1S1(R.string.msg_sonda_lambda_b1s1, R.string.unit_voltage, "o2_b1s1_v", 2, SignalGroup.MIXTURE),
    O2_B1S2(R.string.msg_sonda_depois_do_catalisador, R.string.unit_voltage, "o2_b1s2_v", 2, SignalGroup.MIXTURE),
    MODULE_VOLTAGE(R.string.msg_tensao, R.string.unit_voltage, "voltage_v", 1, SignalGroup.ELECTRICAL),
    FUEL_LEVEL(R.string.msg_combustivel_no_tanque, R.string.unit_percent, "fuel_level_pct", 0, SignalGroup.VEHICLE);

    val unit: String get() = text(unitId)
    val label: String get() = text(labelId)
}
