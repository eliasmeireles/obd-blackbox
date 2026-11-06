package io.github.eliasmeireles.obdblackbox.domain.fuel

/** Distance/fuel totals in roughly 1 km buckets; trims the oldest bucket proportionally at 100 km. */
class FuelEconomyHistory(private val store: FuelHistoryStore = FuelHistoryStore.NONE) {
    private val usage = ArrayDeque(store.load().filter {
        it.distanceKm.isFinite() && it.liters.isFinite() && it.distanceKm >= 0 && it.liters >= 0
    })
    private var previous: Reading? = null
    private var lastSaveMs: Long? = null
    private var dirty = false

    init { trim() }

    val distanceKm: Double get() = usage.sumOf { it.distanceKm }
    val learning: Boolean get() = distanceKm + 1e-8 < MIN_LEARNED_KM || usage.sumOf { it.liters } <= 0.0

    /** km/l over the remembered distance, or null while [learning]. */
    val averageKmPerLiter: Double?
        get() = if (learning) null else distanceKm / usage.sumOf { it.liters }

    fun advance(at: Long, speedKmh: Double?, rateLph: Double?) {
        val current = if (speedKmh != null && speedKmh.isFinite() && speedKmh >= 0 &&
            rateLph != null && rateLph.isFinite() && rateLph >= 0
        ) Reading(at, speedKmh, rateLph) else null
        val before = previous
        previous = current
        if (before == null || current == null) return
        val gap = at - before.at
        if (gap !in 1..MAX_GAP_MS) return
        val hours = gap / MS_PER_HOUR
        add(FuelUsage((before.speed + current.speed) / 2 * hours, (before.rate + current.rate) / 2 * hours))
        if (lastSaveMs == null || at - lastSaveMs!! >= SAVE_INTERVAL_MS) {
            flush()
            lastSaveMs = at
        }
    }

    fun flush() {
        if (!dirty) return
        store.save(usage.toList())
        dirty = false
    }

    private fun add(value: FuelUsage) {
        dirty = true
        val last = usage.lastOrNull()
        if (last != null && last.distanceKm < BUCKET_KM) {
            usage.removeLast()
            usage.addLast(FuelUsage(last.distanceKm + value.distanceKm, last.liters + value.liters))
        } else usage.addLast(value)
        trim()
    }

    private fun trim() {
        var excess = distanceKm - WINDOW_KM
        while (excess > 1e-8 && usage.isNotEmpty()) {
            val first = usage.removeFirst()
            if (first.distanceKm <= excess) excess -= first.distanceKm
            else {
                val fraction = (first.distanceKm - excess) / first.distanceKm
                usage.addFirst(FuelUsage(first.distanceKm * fraction, first.liters * fraction))
                excess = 0.0
            }
        }
    }

    private data class Reading(val at: Long, val speed: Double, val rate: Double)

    companion object {
        /** Distance driven before the average is trusted; shorter and the first minutes of idle dominate it. */
        const val MIN_LEARNED_KM = 1.0
        private const val WINDOW_KM = 100.0
        private const val BUCKET_KM = 1.0
        private const val MAX_GAP_MS = 5_000L
        private const val SAVE_INTERVAL_MS = 30_000L
        private const val MS_PER_HOUR = 3_600_000.0
    }
}
