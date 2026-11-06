package io.github.eliasmeireles.obdblackbox.data.sim

import kotlin.math.max
import kotlin.math.min

internal object SimTargets {
    private const val IDLE_RPM = 800.0
    private const val CRUISE_KMH = 100.0

    /** The driver notices the idle dropping and presses the pedal. */
    private const val PEDAL_PRESSED_AT_S = 7.0

    fun of(phase: SimPhase, t: Double, speedKmh: Double): SimTarget = when (phase) {
        SimPhase.IDLE -> SimTarget(0.0, IDLE_RPM, 32.0, 0.0, 22.0, 10.0)
        SimPhase.ACCELERATE -> SimTarget(CRUISE_KMH, shiftingRpm(speedKmh), 82.0, 42.0, 72.0, 16.0)
        SimPhase.CRUISE -> SimTarget(CRUISE_KMH, 2_200.0, 62.0, 18.0, 38.0, 30.0)
        SimPhase.DECELERATE -> SimTarget(0.0, max(IDLE_RPM, 800.0 + speedKmh * 14.0), 24.0, 0.0, 14.0, 24.0)
        SimPhase.STALLING -> stalling(t)
        SimPhase.ENGINE_OFF -> SimTarget(0.0, 0.0, 96.0, 0.0, 0.0, 0.0, shortTrimPct = 0.0, o2Volts = 0.0, voltage = 12.4)
    }

    /** Sawtooth that mimics the automatic gearbox shifting while accelerating. */
    private fun shiftingRpm(speedKmh: Double): Double = 1_600.0 + (speedKmh % 25.0) / 25.0 * 1_700.0

    /**
     * Engine weakens while stopped: rpm falls, the driver presses the pedal, the throttle opens
     * (MAP rises) but rpm keeps falling, the mixture goes lean (O2 low, trim at maximum) and it dies.
     */
    private fun stalling(t: Double): SimTarget {
        val rpm = if (t < 10.0) 780.0 - 33.0 * t else max(0.0, 450.0 - 225.0 * (t - 10.0))
        val pedal = if (t >= PEDAL_PRESSED_AT_S) 35.0 else 0.0
        val voltage = if (rpm >= 500.0) 14.1 else 12.6 + rpm / 500.0 * 1.2
        return SimTarget(
            speedKmh = 0.0,
            rpm = rpm,
            mapKpa = if (pedal > 0) min(96.0, 40.0 + (t - PEDAL_PRESSED_AT_S) * 6.0) else 34.0,
            pedalPct = pedal,
            loadPct = if (pedal > 0) 55.0 else 25.0,
            timingDeg = 8.0,
            shortTrimPct = min(25.0, 3.0 + t * 2.5),
            o2Volts = 0.08,
            voltage = voltage,
        )
    }
}
