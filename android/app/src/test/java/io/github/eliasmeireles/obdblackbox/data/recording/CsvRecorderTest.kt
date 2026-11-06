package io.github.eliasmeireles.obdblackbox.data.recording

import io.github.eliasmeireles.obdblackbox.domain.Signal
import io.github.eliasmeireles.obdblackbox.domain.alerts.Alert
import io.github.eliasmeireles.obdblackbox.domain.alerts.AlertId
import io.github.eliasmeireles.obdblackbox.domain.alerts.Severity
import io.github.eliasmeireles.obdblackbox.domain.events.RecordedEvent
import io.github.eliasmeireles.obdblackbox.domain.snapshot
import java.io.StringWriter
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvRecorderTest : io.github.eliasmeireles.obdblackbox.ResourceTest() {
    private val format = CsvFormat(ZoneOffset.ofHours(-3))

    @Test fun `range and rear o2 have aligned csv columns`() {
        val columns = format.header.split(",")
        val cells = format.line(TelemetryRow(snapshot(Signal.O2_B1S2 to 0.8), null, emptyList(), null, 30.0, 210.0)).split(",")
        assertEquals(columns.size, cells.size)
        assertEquals("0.800", cells[columns.indexOf("o2_b1s2_v")])
        assertEquals("30.00", cells[columns.indexOf("fuel_liters_est")])
        assertEquals("210.0", cells[columns.indexOf("fuel_range_km_est")])
    }

    @Test fun `header lists every signal column once`() {
        val columns = format.header.split(",")
        assertEquals(columns.size, columns.toSet().size)
        assertTrue(columns.containsAll(listOf("timestamp", "rpm", "voltage_v", "fuel_rate_lph_est", "alerts", "event")))
    }

    @Test fun `writes values with dot decimals and empty missing cells`() {
        val out = StringWriter()
        val alert = Alert(AlertId.IDLE_DROP, Severity.CRITICAL, "x")
        CsvRecorder("t.csv", out, format).use {
            it.record(TelemetryRow(snapshot(Signal.RPM to 812.5, Signal.MODULE_VOLTAGE to 14.06, at = 0L), 1.234, listOf(alert), RecordedEvent("marca_1", 0L, automatic = false)))
        }
        val lines = out.toString().trim().lines()
        assertEquals(2, lines.size)
        val cells = lines[1].split(",")
        val header = lines[0].split(",")
        fun cell(name: String) = cells[header.indexOf(name)]
        assertEquals("1969-12-31T21:00:00-03:00", cell("timestamp"))
        assertEquals("812.5", cell("rpm"))
        assertEquals("14.06", cell("voltage_v"))
        assertEquals("", cell("speed_kmh"))
        assertEquals("1.23", cell("fuel_rate_lph_est"))
        assertEquals("IDLE_DROP", cell("alerts"))
        assertEquals("marca_1", cell("event"))
    }
}
