package io.github.eliasmeireles.obdblackbox.domain.obd

import org.junit.Assert.assertEquals
import org.junit.Test

class PidTest : io.github.eliasmeireles.obdblackbox.ResourceTest() {
    @Test fun `rpm uses two bytes divided by four`() = assertEquals(1726.0, Pid.RPM.decode(intArrayOf(0x1A, 0xF8)), 0.001)

    @Test fun `coolant is offset by 40`() = assertEquals(83.0, Pid.COOLANT_TEMP.decode(intArrayOf(0x7B)), 0.001)

    @Test fun `fuel trim is centered on 128`() {
        assertEquals(0.0, Pid.SHORT_FUEL_TRIM.decode(intArrayOf(0x80)), 0.001)
        assertEquals(25.0, Pid.SHORT_FUEL_TRIM.decode(intArrayOf(0xA0)), 0.001)
        assertEquals(-100.0, Pid.LONG_FUEL_TRIM.decode(intArrayOf(0x00)), 0.001)
    }

    @Test fun `module voltage is millivolts`() = assertEquals(14.0, Pid.MODULE_VOLTAGE.decode(intArrayOf(0x36, 0xB0)), 0.001)

    @Test fun `o2 voltage uses first byte`() = assertEquals(0.45, Pid.O2_B1S1.decode(intArrayOf(0x5A, 0xFF)), 0.001)

    @Test fun `rear o2 voltage uses first byte and ignores trim byte`() {
        assertEquals(Pid.O2_B1S2, Pid.fromCode(0x15))
        assertEquals(0.80, Pid.O2_B1S2.decode(intArrayOf(0xA0, 0x00)), 0.001)
        assertEquals(0.80, Pid.O2_B1S2.decode(intArrayOf(0xA0, 0xFF)), 0.001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rear o2 needs two bytes`() { Pid.O2_B1S2.decode(intArrayOf(0xA0)) }

    @Test fun `percent pids scale to 100`() = assertEquals(100.0, Pid.THROTTLE.decode(intArrayOf(0xFF)), 0.001)

    @Test fun `timing advance is offset`() = assertEquals(10.0, Pid.TIMING_ADVANCE.decode(intArrayOf(148)), 0.001)

    @Test(expected = IllegalArgumentException::class)
    fun `decode rejects short data`() {
        Pid.RPM.decode(intArrayOf(0x1A))
    }
}
