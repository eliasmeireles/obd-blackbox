package io.github.eliasmeireles.obdblackbox.data.elm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ElmDiagnosticsTest : io.github.eliasmeireles.obdblackbox.ResourceTest() {
    @Test fun `self-tests of a spark engine, with supported monitors`() {
        // PID 01 = 01 07 65 00: lamp off, 1 code; misfire and components available and complete; catalyst,
        // O2 sensor and heater available and complete.
        val monitors = ElmDiagnostics.monitors(intArrayOf(0x01, 0x05, 0x61, 0x00)).associateBy { it.name }
        assertTrue(monitors.getValue("misfire").available)
        assertTrue(monitors.getValue("misfire").complete)
        assertFalse(monitors.getValue("fuel_system").available)
        assertTrue(monitors.getValue("catalyst").available)
        assertTrue(monitors.getValue("o2_sensor").available)
        assertFalse(monitors.getValue("egr").available)
        assertEquals(11, monitors.size)
    }

    @Test fun `an unfinished self-test is reported incomplete`() {
        val monitors = ElmDiagnostics.monitors(intArrayOf(0x00, 0x05, 0x21, 0x20)).associateBy { it.name }
        assertFalse(monitors.getValue("o2_sensor").complete)
        assertTrue(monitors.getValue("catalyst").complete)
    }

    @Test fun `snapshot json follows the server contract`() {
        val snapshot = DiagnosticSnapshot(
            t = 1, reason = "connect", adapter = "ELM327 v1.5", protocol = "7", ecuId = "18DAF110", vin = null,
            calibrationId = null, ecuName = null, milOn = false, dtcCount = 1, monitors = emptyList(), fuelSystem = "closed_loop",
            supportedPids = listOf(0x0C, 0x01), values = mapOf("baro_kpa" to 92.0), stored = listOf("P0130"), pending = emptyList(),
            permanent = null, freezeFrame = FreezeFrame("P0130", mapOf("rpm" to 2682.0)),
        )
        val json = snapshot.toJson()
        assertEquals("P0130", json.getJSONObject("dtcs").getJSONArray("stored").getString(0))
        assertEquals("01", json.getJSONArray("supported_pids").getString(0))
        assertEquals(2682.0, json.getJSONObject("freeze_frame").getJSONObject("values").getDouble("rpm"), 0.0)
        assertFalse(json.has("vin"))
        assertEquals(0, json.getJSONObject("dtcs").getJSONArray("permanent").length())
    }

    @Test fun `vehicle readings through the adapter`() {
        val fake = FakeElm(values = FakeElm.DEFAULT_VALUES + (0x01 to "81076500") + (0x03 to "0200"))
        val elm = Elm327(fake).apply { initialize() }
        val supported = elm.connectEcu()!!
        val snapshot = ElmDiagnostics.read(elm, supported, "ELM327 v1.5", "connect") { 7L }
        assertEquals(true, snapshot.milOn)
        assertEquals(1, snapshot.dtcCount)
        assertEquals("closed_loop", snapshot.fuelSystem)
        assertEquals(listOf("P0133", "P0171"), snapshot.stored)
        assertTrue(fake.received.none { it == "04" })
    }
}
