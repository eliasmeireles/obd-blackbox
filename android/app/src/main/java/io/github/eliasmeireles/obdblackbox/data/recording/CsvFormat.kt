package io.github.eliasmeireles.obdblackbox.data.recording

import io.github.eliasmeireles.obdblackbox.domain.Signal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Plain CSV: comma separator, dot decimals, empty cell for a missing value. */
class CsvFormat(zone: ZoneId = ZoneId.systemDefault()) {
    private val timestamp = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(zone)

    val header: String =
        (listOf("timestamp", "epoch_ms") + Signal.entries.map { it.csvColumn } + listOf("fuel_rate_lph_est", "fuel_liters_est", "fuel_range_km_est", "alerts", "event"))
            .joinToString(",")

    fun line(row: TelemetryRow): String {
        val s = row.snapshot
        val cells = buildList {
            add(timestamp.format(Instant.ofEpochMilli(s.timestampMs)))
            add(s.timestampMs.toString())
            Signal.entries.forEach { add(number(s[it], it.decimals + 1)) }
            add(number(row.fuelRateLph, 2))
            add(number(row.fuelLitersEst, 2))
            add(number(row.fuelRangeKmEst, 1))
            add(row.alerts.joinToString("|") { it.id.name })
            add(row.event?.label.orEmpty())
        }
        return cells.joinToString(",")
    }

    private fun number(value: Double?, decimals: Int): String =
        value?.let { String.format(Locale.ROOT, "%.${decimals}f", it) } ?: ""
}
