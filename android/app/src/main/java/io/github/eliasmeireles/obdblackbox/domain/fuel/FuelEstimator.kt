package io.github.eliasmeireles.obdblackbox.domain.fuel

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import io.github.eliasmeireles.obdblackbox.domain.Signal
import io.github.eliasmeireles.obdblackbox.domain.VehicleSnapshot

enum class FuelType(private val labelId: Int, val stoichiometricAfr: Double, val densityKgPerL: Double) {
    GASOLINE(R.string.msg_gasolina, 14.7, 0.745),
    ETHANOL(R.string.msg_etanol, 9.0, 0.789),
    FLEX(R.string.msg_flex, 14.7, 0.745),
    DIESEL(R.string.msg_diesel, 14.5, 0.832);

    val label: String get() = text(labelId)
}

/** Volumetric efficiency is an average guess, corrected by [calibration]. */
data class EngineSpec(val displacementL: Double = 1.6, val volumetricEfficiency: Double = 0.80)

/**
 * Speed-density estimate using MAP, rpm and intake temperature.
 * Approximate until calibrated against a real tank.
 */
class FuelEstimator(
    private val engine: EngineSpec = EngineSpec(),
    private val fuel: FuelType = FuelType.GASOLINE,
    private val calibration: Double = 1.0,
) {
    fun litersPerHour(s: VehicleSnapshot): Double? {
        val rpm = s[Signal.RPM] ?: return null
        val mapKpa = s[Signal.MAP] ?: return null
        if (rpm <= 0.0) return 0.0
        val intakeK = (s[Signal.INTAKE_TEMP] ?: DEFAULT_INTAKE_C) + KELVIN
        val airKgPerS = mapKpa * PA_PER_KPA * (engine.displacementL / L_PER_M3) * (rpm / RPM_PER_CYCLE) *
            engine.volumetricEfficiency / (AIR_GAS_CONSTANT * intakeK)
        val trim = 1.0 + ((s[Signal.SHORT_FUEL_TRIM] ?: 0.0) + (s[Signal.LONG_FUEL_TRIM] ?: 0.0)) / 100.0
        val fuelKgPerH = airKgPerS / fuel.stoichiometricAfr * trim * SECONDS_PER_HOUR
        return fuelKgPerH / fuel.densityKgPerL * calibration
    }

    fun kmPerLiter(speedKmh: Double?, litersPerHour: Double?): Double? {
        if (speedKmh == null || litersPerHour == null || speedKmh < MIN_SPEED_KMH || litersPerHour <= 0.0) return null
        return speedKmh / litersPerHour
    }

    private companion object {
        const val DEFAULT_INTAKE_C = 30.0
        const val KELVIN = 273.15
        const val PA_PER_KPA = 1_000.0
        const val L_PER_M3 = 1_000.0
        const val RPM_PER_CYCLE = 120.0
        const val AIR_GAS_CONSTANT = 287.05
        const val SECONDS_PER_HOUR = 3_600.0
        const val MIN_SPEED_KMH = 3.0
    }
}
