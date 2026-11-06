package io.github.eliasmeireles.obdblackbox.domain.trip

import kotlin.math.max

data class TripStats(
    val startedAtMs: Long? = null,
    val lastSampleMs: Long? = null,
    val distanceKm: Double = 0.0,
    val fuelUsedL: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
) {
    val averageKmPerLiter: Double?
        get() = if (fuelUsedL >= MIN_FUEL_FOR_AVERAGE_L) distanceKm / fuelUsedL else null

    val elapsedMs: Long
        get() = if (startedAtMs == null || lastSampleMs == null) 0 else lastSampleMs - startedAtMs

    fun advance(timestampMs: Long, speedKmh: Double?, fuelRateLph: Double?): TripStats {
        val speed = speedKmh ?: 0.0
        val previous = lastSampleMs
            ?: return copy(startedAtMs = timestampMs, lastSampleMs = timestampMs, maxSpeedKmh = speed)
        val hours = (timestampMs - previous).coerceIn(0, MAX_GAP_MS) / MS_PER_HOUR
        return copy(
            lastSampleMs = timestampMs,
            distanceKm = distanceKm + speed * hours,
            fuelUsedL = fuelUsedL + (fuelRateLph ?: 0.0) * hours,
            maxSpeedKmh = max(maxSpeedKmh, speed),
        )
    }

    companion object {
        /** Gaps longer than this (reconnects, pauses) are not integrated. */
        const val MAX_GAP_MS = 5_000L
        private const val MS_PER_HOUR = 3_600_000.0
        private const val MIN_FUEL_FOR_AVERAGE_L = 0.05
    }
}
