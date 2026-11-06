package io.github.eliasmeireles.obdblackbox.data.bridge

import org.junit.Assert.*
import org.junit.Test

class DiagnosticProtocolTest : io.github.eliasmeireles.obdblackbox.ResourceTest() {
    @Test fun `diagnostic parser preserves unknown fields and parses UI data`() {
        val json = """{"t":123,"reason":"manual","mil_on":true,"dtc_count":1,"values":{"distance_mil_km":12},"dtcs":{"stored":["P0171"],"pending":[],"permanent":[]},"future":"preservado"}"""
        val reading = (BridgeProtocol.parse("DIAG $json") as BridgeMessage.Diagnostics).reading
        assertEquals(json, reading.json)
        assertEquals(true, reading.snapshot.milOn)
        assertEquals(listOf("P0171"), reading.snapshot.stored)
        assertEquals(12.0, reading.snapshot.values.getValue("distance_mil_km"), 0.0)
    }

    @Test fun `DIAG accepts large valid messages up to its own limit`() {
        val json = """{"t":123,"reason":"manual","extension":"${"a".repeat(1000)}"}"""
        assertNotNull(BridgeProtocol.parse("DIAG $json"))
        assertNull(BridgeProtocol.parse("DIAG " + "a".repeat(8192)))
        assertNull(BridgeProtocol.parse("DIAG {invalid}"))
    }

    @Test fun `compound clear response preserves command and Portuguese reason`() {
        assertEquals(BridgeMessage.Ok("DTC CLEAR"), BridgeProtocol.parse("OK DTC CLEAR"))
        assertEquals(BridgeMessage.Err("DTC CLEAR", "motor ligado"), BridgeProtocol.parse("ERR DTC CLEAR motor ligado"))
    }
}
