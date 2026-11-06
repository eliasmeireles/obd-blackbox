package io.github.eliasmeireles.obdblackbox.domain.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObdResponseParserTest : io.github.eliasmeireles.obdblackbox.ResourceTest() {
    @Test fun `parses spaced reply`() {
        val reading = ObdResponseParser.parse("41 0C 1A F8")!!
        assertEquals(Pid.RPM, reading.pid)
        assertEquals(1726.0, reading.value, 0.001)
    }

    @Test fun `parses compact lowercase reply`() {
        assertEquals(Pid.SPEED, ObdResponseParser.parse("410d3c")!!.pid)
    }

    @Test fun `ignores errors and other modes`() {
        assertNull(ObdResponseParser.parse("NO DATA"))
        assertNull(ObdResponseParser.parse("?"))
        assertNull(ObdResponseParser.parse("43 01 33"))
        assertNull(ObdResponseParser.parse(""))
    }

    @Test fun `ignores unknown pid and truncated data`() {
        assertNull(ObdResponseParser.parse("41 A6 00 00"))
        assertNull(ObdResponseParser.parse("41 0C 1A"))
    }
}
