package io.github.eliasmeireles.obdblackbox.domain.vehicle

import io.github.eliasmeireles.obdblackbox.ResourceTest
import io.github.eliasmeireles.obdblackbox.domain.Signal
import io.github.eliasmeireles.obdblackbox.domain.snapshot
import io.github.eliasmeireles.obdblackbox.domain.fuel.*
import org.junit.Assert.*
import org.junit.Test

class VehicleProfileTest : ResourceTest() {
    @Test fun `profile displacement and fuel feed the consumption estimate`() {
        val sample = snapshot(Signal.RPM to 2000.0, Signal.MAP to 50.0)
        val small = VehicleProfile(displacementL = 1.0)
        val large = small.copy(displacementL = 2.0)
        val gasoline = small.fuelEstimator().litersPerHour(sample)!!
        assertEquals(gasoline * 2, large.fuelEstimator().litersPerHour(sample)!!, 0.00001)
        assertTrue(small.copy(fuel = FuelType.ETHANOL).fuelEstimator().litersPerHour(sample)!! > gasoline)
        assertEquals(gasoline, small.copy(fuel = FuelType.FLEX).fuelEstimator().litersPerHour(sample)!!, 0.0)
        assertNotEquals(gasoline, small.copy(fuel = FuelType.DIESEL).fuelEstimator().litersPerHour(sample)!!)
    }

    @Test fun `tank capacity feeds liters and range with the same learned economy`() {
        val store = object : FuelHistoryStore {
            override fun load() = listOf(FuelUsage(20.0, 2.0))
            override fun save(usage: List<FuelUsage>) = Unit
        }
        val profile = VehicleProfile(tankCapacityL = 80.0)
        val range = FuelRangeEstimator(store, profile.tankCapacityL)
            .advance(snapshot(Signal.RPM to 800.0, Signal.FUEL_LEVEL to 50.0), null)
        assertEquals(40.0, range.litersLeft!!, 0.0)
        assertEquals(400.0, range.rangeKm!!, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid capacity is rejected`() { VehicleProfile(tankCapacityL = Double.NaN) }
}
