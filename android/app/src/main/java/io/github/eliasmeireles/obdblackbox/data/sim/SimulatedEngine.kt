package io.github.eliasmeireles.obdblackbox.data.sim

import io.github.eliasmeireles.obdblackbox.domain.Signal
import io.github.eliasmeireles.obdblackbox.domain.VehicleSnapshot
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Deterministic (for a given [random]) model of the car, used before the real bridge exists. */
class SimulatedEngine(private val random: Random = Random.Default) {
    private var phase = SimPhase.IDLE
    private var phaseTime = 0.0
    private var totalTime = 0.0
    private var speed = 0.0
    private var rpm = 800.0

    private var stallAfterDecel = false

    @Volatile private var stallRequested = false

    fun requestStall() {
        stallRequested = true
    }

    fun step(dtS: Double, timestampMs: Long): VehicleSnapshot {
        advance(dtS)
        val target = SimTargets.of(phase, phaseTime, speed)
        speed += (target.speedKmh - speed) * min(1.0, dtS * speedRate())
        rpm = if (phase == SimPhase.STALLING || phase == SimPhase.ENGINE_OFF) target.rpm else smooth(rpm, target.rpm, dtS)
        return VehicleSnapshot(timestampMs, signals(target))
    }

    private fun advance(dtS: Double) {
        totalTime += dtS
        phaseTime += dtS
        if (consumeStallRequest() || phaseTime < phase.durationS) return
        val next = if (stallAfterDecel && phase == SimPhase.DECELERATE) SimPhase.STALLING else phase.nextInLoop()
        stallAfterDecel = false
        enter(next)
    }

    /** A stall only happens stopped: when moving, the car first decelerates to zero. */
    private fun consumeStallRequest(): Boolean {
        if (!stallRequested || phase == SimPhase.STALLING || phase == SimPhase.ENGINE_OFF) return false
        stallRequested = false
        stallAfterDecel = speed > STOPPED_KMH
        enter(if (stallAfterDecel) SimPhase.DECELERATE else SimPhase.STALLING)
        return true
    }

    private fun enter(next: SimPhase) {
        phase = next
        phaseTime = 0.0
    }

    private fun speedRate() = if (phase == SimPhase.ACCELERATE) 0.18 else 0.25

    private fun smooth(current: Double, target: Double, dtS: Double) = current + (target - current) * min(1.0, dtS * 2.5)

    private fun signals(target: SimTarget): Map<Signal, Double> {
        val running = rpm > 0.0
        val pedal = target.pedalPct
        return mapOf(
            Signal.SPEED to max(0.0, speed),
            Signal.RPM to max(0.0, rpm + if (running) noise(8.0) else 0.0),
            Signal.COOLANT_TEMP to min(92.0, 58.0 + totalTime * 0.6),
            Signal.ENGINE_LOAD to target.loadPct + if (running) noise(1.5) else 0.0,
            Signal.TIMING_ADVANCE to target.timingDeg + if (running) noise(0.8) else 0.0,
            Signal.PEDAL to pedal,
            Signal.THROTTLE to pedal * 0.95,
            Signal.COMMANDED_THROTTLE to pedal * 0.95,
            Signal.MAP to target.mapKpa + if (running) noise(1.0) else 0.0,
            Signal.INTAKE_TEMP to 38.0 + noise(0.3),
            Signal.SHORT_FUEL_TRIM to (target.shortTrimPct ?: (3.0 * sin(2 * PI * totalTime / 2.3) + noise(0.5))),
            Signal.LONG_FUEL_TRIM to if (running) 2.3 else 0.0,
            Signal.O2_B1S1 to (target.o2Volts ?: (0.45 + 0.4 * sin(2 * PI * totalTime / 1.1))),
            Signal.MODULE_VOLTAGE to (target.voltage ?: 14.1) + noise(0.04),
            Signal.FUEL_LEVEL to max(0.0, 63.0 - totalTime * 0.002),
        )
    }

    private fun noise(amplitude: Double) = (random.nextDouble() * 2 - 1) * amplitude

    private companion object {
        const val STOPPED_KMH = 3.0
    }
}
