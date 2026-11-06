package io.github.eliasmeireles.obdblackbox.domain.fuel

import io.github.eliasmeireles.obdblackbox.domain.Signal
import io.github.eliasmeireles.obdblackbox.domain.VehicleSnapshot

/**
 * Median over the last 30 seconds; missing readings retain the last displayed level. Readings only count while the
 * engine is known to run: some ECUs keep answering after key-off and report 0 % fuel,
 * and the last cycles before it goes silent carry the cached 0 % without rpm.
 */
class FuelRangeEstimator(store: FuelHistoryStore = FuelHistoryStore.NONE, private val tankCapacityL: Double = DEFAULT_TANK_LITERS) {
    private val economy = FuelEconomyHistory(store)
    private val levels = ArrayDeque<Pair<Long, Double>>()
    private var lastLevel: Double? = null

    @Synchronized
    fun advance(snapshot: VehicleSnapshot, fuelRateLph: Double?): FuelRange {
        val at = snapshot.timestampMs
        while (levels.isNotEmpty() && at - levels.first().first > LEVEL_WINDOW_MS) levels.removeFirst()
        val running = (snapshot[Signal.RPM] ?: 0.0) >= MIN_RUNNING_RPM
        snapshot[Signal.FUEL_LEVEL]?.takeIf { running && it.isFinite() && it in 0.0..100.0 }?.let { level ->
            levels.addLast(at to level)
            val sorted = levels.map { it.second }.sorted()
            val middle = sorted.size / 2
            lastLevel = if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2 else sorted[middle]
        }
        economy.advance(at, snapshot[Signal.SPEED], fuelRateLph)
        return FuelRange(lastLevel, economy.averageKmPerLiter, economy.distanceKm, tankCapacityL)
    }

    /** The range as known now, without a new reading (level unknown until the engine runs). */
    @Synchronized
    fun current(): FuelRange = FuelRange(lastLevel, economy.averageKmPerLiter, economy.distanceKm, tankCapacityL)

    @Synchronized
    fun flush() { economy.flush() }

    private companion object {
        const val LEVEL_WINDOW_MS = 30_000L
        const val MIN_RUNNING_RPM = 400.0
    }
}
