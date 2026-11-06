package io.github.eliasmeireles.obdblackbox.data.upload

import io.github.eliasmeireles.obdblackbox.data.SourceKind
import io.github.eliasmeireles.obdblackbox.data.recording.SessionInfo
import io.github.eliasmeireles.obdblackbox.data.recording.TelemetryRow
import io.github.eliasmeireles.obdblackbox.domain.Signal
import io.github.eliasmeireles.obdblackbox.domain.alerts.Alert
import io.github.eliasmeireles.obdblackbox.domain.alerts.AlertId
import io.github.eliasmeireles.obdblackbox.domain.alerts.Severity
import io.github.eliasmeireles.obdblackbox.domain.events.RecordedEvent
import io.github.eliasmeireles.obdblackbox.domain.snapshot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UploadPayloadTest : io.github.eliasmeireles.obdblackbox.ResourceTest() {
    @Test fun `fuel range and rear o2 flow to uploaded values`() {
        val row = TelemetryRow(snapshot(Signal.O2_B1S2 to 0.8), 1.0, emptyList(), null, 30.0, 210.0)
        val values = JSONObject(UploadPayload.sample(row)).getJSONObject("v")
        assertEquals(30.0, values.getDouble("fuel_liters_est"), 0.0)
        assertEquals(210.0, values.getDouble("fuel_range_km_est"), 0.0)
        assertEquals(0.8, values.getDouble("o2_b1s2_v"), 0.0)
    }

    @Test fun `sample uses csv column names, alerts and event`() {
        val row = TelemetryRow(
            snapshot(Signal.RPM to 812.5, Signal.MODULE_VOLTAGE to 14.1, at = 1_793_925_150_857),
            fuelRateLph = 1.2,
            alerts = listOf(Alert(AlertId.IDLE_DROP, Severity.CRITICAL, "x")),
            event = RecordedEvent("marca_1", 1_793_925_150_857, automatic = false),
        )
        val json = JSONObject(UploadPayload.sample(row))
        assertEquals(1_793_925_150_857, json.getLong("t"))
        assertEquals(812.5, json.getJSONObject("v").getDouble("rpm"), 0.0)
        assertEquals(1.2, json.getJSONObject("v").getDouble("fuel_rate_lph_est"), 0.0)
        assertEquals("IDLE_DROP", json.getJSONArray("alerts").getString(0))
        assertEquals("marca_1", json.getJSONObject("event").getString("label"))
        assertFalse(json.getJSONObject("event").getBoolean("automatic"))
    }

    @Test fun `non finite values and missing event are left out`() {
        val json = JSONObject(UploadPayload.sample(TelemetryRow(snapshot(Signal.RPM to Double.NaN), null, emptyList(), null)))
        assertFalse(json.getJSONObject("v").has("rpm"))
        assertFalse(json.has("event"))
    }

    @Test fun `session body follows the api contract`() {
        val json = JSONObject(UploadPayload.session(SessionInfo("id-1", SourceKind.SIMULATION, 0L, "Example car"), "0.1.0"))
        assertEquals("sim", json.getString("source"))
        assertEquals("Example car", json.getString("vehicle"))
        assertEquals("1970-01-01T00:00:00Z", json.getString("started_at"))
        assertEquals("0.1.0", json.getString("app_version"))
    }

    @Test fun `batch wraps serialized samples`() {
        val json = JSONObject(UploadPayload.batch(listOf("{\"t\":1}", "{\"t\":2}")))
        assertEquals(2, json.getJSONArray("samples").length())
    }
}
