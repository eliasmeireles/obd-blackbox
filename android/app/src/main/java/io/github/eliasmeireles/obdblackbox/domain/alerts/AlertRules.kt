package io.github.eliasmeireles.obdblackbox.domain.alerts

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import io.github.eliasmeireles.obdblackbox.domain.Signal
import io.github.eliasmeireles.obdblackbox.domain.VehicleSnapshot
import io.github.eliasmeireles.obdblackbox.domain.format.formatPt

class AlertRules(private val limits: AlertThresholds = AlertThresholds()) {

    fun evaluate(snapshot: VehicleSnapshot): List<Alert> =
        listOfNotNull(
            lowVoltage(snapshot),
            overheat(snapshot),
            idleDrop(snapshot),
            noThrottleResponse(snapshot),
            mixture(snapshot),
        ).sortedBy { it.severity != Severity.CRITICAL }

    private fun lowVoltage(s: VehicleSnapshot): Alert? {
        val volts = s[Signal.MODULE_VOLTAGE] ?: return null
        if (!s.isEngineRunning() || volts >= limits.minRunningVoltage) return null
        return Alert(AlertId.LOW_VOLTAGE, Severity.WARNING, text(R.string.msg_tensao_baixa_com_motor_ligado_value_v, (volts.formatPt(1)).toString()))
    }

    private fun overheat(s: VehicleSnapshot): Alert? {
        val coolant = s[Signal.COOLANT_TEMP] ?: return null
        if (coolant <= limits.maxCoolantC) return null
        return Alert(AlertId.OVERHEAT, Severity.CRITICAL, text(R.string.msg_motor_quente_value_c, (coolant.formatPt(0)).toString()))
    }

    private fun idleDrop(s: VehicleSnapshot): Alert? {
        val rpm = s[Signal.RPM] ?: return null
        if (!s.isEngineRunning() || rpm >= limits.minIdleRpm || isStopped(s).not() || isPedalPressed(s)) return null
        return Alert(AlertId.IDLE_DROP, Severity.CRITICAL, text(R.string.msg_marcha_lenta_caindo_value_rpm, (rpm.formatPt(0)).toString()))
    }

    private fun noThrottleResponse(s: VehicleSnapshot): Alert? {
        val rpm = s[Signal.RPM] ?: return null
        val pedal = pedalOrThrottle(s) ?: return null
        if (!s.isEngineRunning() || rpm >= limits.minIdleRpm || !isPedalPressed(s)) return null
        return Alert(
            AlertId.NO_THROTTLE_RESPONSE,
            Severity.CRITICAL,
            text(R.string.msg_acelerador_sem_resposta_pedal_value_value_rpm, (pedal.formatPt(0)).toString(), (rpm.formatPt(0)).toString()),
        )
    }

    private fun mixture(s: VehicleSnapshot): Alert? {
        val trim = s[Signal.SHORT_FUEL_TRIM] ?: return null
        if (!s.isEngineRunning()) return null
        return when {
            trim > limits.maxFuelTrimPct ->
                Alert(AlertId.LEAN_MIXTURE, Severity.WARNING, text(R.string.msg_mistura_pobre_correcao_value, (trim.formatPt(0)).toString()))
            trim < -limits.maxFuelTrimPct ->
                Alert(AlertId.RICH_MIXTURE, Severity.WARNING, text(R.string.msg_mistura_rica_correcao_value, (trim.formatPt(0)).toString()))
            else -> null
        }
    }

    private fun isStopped(s: VehicleSnapshot) = (s[Signal.SPEED] ?: 0.0) < limits.stoppedSpeedKmh

    private fun isPedalPressed(s: VehicleSnapshot) = (pedalOrThrottle(s) ?: 0.0) >= limits.pressedPedalPct

    private fun pedalOrThrottle(s: VehicleSnapshot) = s[Signal.PEDAL] ?: s[Signal.THROTTLE]
}
