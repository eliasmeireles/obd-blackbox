package io.github.eliasmeireles.obdblackbox.domain.fuel

import io.github.eliasmeireles.obdblackbox.domain.Signal
import io.github.eliasmeireles.obdblackbox.domain.snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelEstimatorTest : io.github.eliasmeireles.obdblackbox.ResourceTest() {
    private val estimator = FuelEstimator()

    @Test fun `warm idle consumption is plausible`() {
        val lph = estimator.litersPerHour(snapshot(Signal.RPM to 800.0, Signal.MAP to 32.0, Signal.INTAKE_TEMP to 40.0))!!
        assertTrue("idle $lph L/h", lph in 0.5..2.0)
    }

    @Test fun `cruise gives realistic km per liter`() {
        val lph = estimator.litersPerHour(snapshot(Signal.RPM to 2200.0, Signal.MAP to 65.0, Signal.INTAKE_TEMP to 35.0))
        val kmpl = estimator.kmPerLiter(100.0, lph)!!
        assertTrue("cruise $kmpl km/l", kmpl in 7.0..20.0)
    }

    @Test fun `ethanol burns more volume than gasoline`() {
        val s = snapshot(Signal.RPM to 2000.0, Signal.MAP to 50.0)
        val ethanol = FuelEstimator(fuel = FuelType.ETHANOL).litersPerHour(s)!!
        assertTrue(ethanol > estimator.litersPerHour(s)!!)
    }

    @Test fun `stopped engine burns nothing and missing data gives null`() {
        assertEquals(0.0, estimator.litersPerHour(snapshot(Signal.RPM to 0.0, Signal.MAP to 98.0))!!, 0.0)
        assertNull(estimator.litersPerHour(snapshot(Signal.RPM to 800.0)))
        assertNull(estimator.kmPerLiter(0.0, 1.0))
    }
}
