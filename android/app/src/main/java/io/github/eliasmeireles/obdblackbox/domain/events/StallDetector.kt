package io.github.eliasmeireles.obdblackbox.domain.events

import io.github.eliasmeireles.obdblackbox.domain.Signal
import io.github.eliasmeireles.obdblackbox.domain.VehicleSnapshot

/**
 * Classifies a running → zero rpm transition even while an ECU power latch keeps replying after key-off.
 *
 * Typical key-off signature: rpm steady at idle, the ECU closes the throttle by ~3–5
 * points in the last second, then rpm is 0 on the next sample; the voltage falls ~1 V to battery level on the last
 * running sample or on the zero sample (voltage is polled less often, so it is supporting evidence, not required).
 * A stall instead sags (rpm falls over the last seconds) and the ECU opens the throttle trying to save it; a sudden
 * stall with no sag (e.g. crank sensor) does not close the throttle first.
 *
 * So: key-off = no sag (every rpm in the 2 s before stays >= 85 % of the peak) AND throttle closed by >= 2.5 points
 * within the last second compared with the second before. Anything else is a stall.
 */
class StallDetector(
    private val runningRpm: Double = 500.0,
    private val stoppedRpm: Double = 50.0,
) {
    private var wasRunning = false
    private val history = ArrayDeque<VehicleSnapshot>()

    /** Returns a stop once, on the first sample at zero rpm. */
    fun onSnapshot(snapshot: VehicleSnapshot): EngineStop? {
        while (history.isNotEmpty() && snapshot.timestampMs - history.first().timestampMs > HISTORY_MS) history.removeFirst()
        val rpm = snapshot[Signal.RPM]?.takeIf { it.isFinite() } ?: return null
        val stop = if (wasRunning && rpm <= stoppedRpm) {
            wasRunning = false
            if (isKeyOff(snapshot.timestampMs)) EngineStop.KEY_OFF else EngineStop.STALL
        } else null
        if (rpm >= runningRpm) {
            wasRunning = true
        }
        if (stop != null) history.clear() else history.addLast(snapshot)
        return stop
    }

    private fun isKeyOff(at: Long): Boolean {
        val rpms = history.mapNotNull { it[Signal.RPM] }
        if (rpms.size < 2) return false
        val peak = rpms.max()
        if (peak < runningRpm || rpms.min() < peak * NO_SAG_FRACTION) return false
        val (recent, earlier) = history.partition { at - it.timestampMs <= KEY_OFF_WINDOW_MS }
        val before = earlier.mapNotNull { it[Signal.THROTTLE] }.maxOrNull() ?: return false
        val after = recent.mapNotNull { it[Signal.THROTTLE] }.minOrNull() ?: return false
        return before - after >= THROTTLE_CLOSURE
    }

    private companion object {
        const val HISTORY_MS = 2_000L
        const val KEY_OFF_WINDOW_MS = 1_000L
        const val THROTTLE_CLOSURE = 2.5
        const val NO_SAG_FRACTION = 0.85
    }
}

enum class EngineStop(val eventLabel: String) {
    KEY_OFF("desligado_pela_chave"),
    STALL("desligou_sozinho"),
}
