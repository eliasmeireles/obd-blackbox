package io.github.eliasmeireles.obdblackbox.domain

data class VehicleSnapshot(
    val timestampMs: Long,
    val values: Map<Signal, Double>,
) {
    operator fun get(signal: Signal): Double? = values[signal]

    fun isEngineRunning(minRpm: Double = ENGINE_RUNNING_RPM): Boolean = (this[Signal.RPM] ?: 0.0) >= minRpm

    companion object {
        const val ENGINE_RUNNING_RPM = 300.0
    }
}
