package io.github.eliasmeireles.obdblackbox.domain.fuel

const val DEFAULT_TANK_LITERS = 50.0

/**
 * Fuel left and range. The range uses only this car's own average (the app's consumption estimate over the last
 * ~100 km); there is no fixed default, so it stays unknown until [FuelEconomyHistory.MIN_LEARNED_KM] were driven.
 */
data class FuelRange(
    val levelPercent: Double? = null,
    val averageKmPerLiter: Double? = null,
    val learnedDistanceKm: Double = 0.0,
    val tankCapacityL: Double = DEFAULT_TANK_LITERS,
) {
    val litersLeft: Double? get() = levelPercent?.let { it * tankCapacityL / 100.0 }
    val rangeKm: Double? get() = litersLeft?.let { liters -> averageKmPerLiter?.let { liters * it } }

    /** Still learning the car's average: no range yet. */
    val learning: Boolean get() = averageKmPerLiter == null

    /** Kilometres still to drive before the range appears. */
    val kmToLearn: Double get() = (FuelEconomyHistory.MIN_LEARNED_KM - learnedDistanceKm).coerceAtLeast(0.0)
}

data class FuelUsage(val distanceKm: Double, val liters: Double)

interface FuelHistoryStore {
    fun load(): List<FuelUsage>
    fun save(usage: List<FuelUsage>)

    companion object {
        val NONE = object : FuelHistoryStore {
            override fun load() = emptyList<FuelUsage>()
            override fun save(usage: List<FuelUsage>) = Unit
        }
    }
}
