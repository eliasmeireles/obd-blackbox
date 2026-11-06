package io.github.eliasmeireles.obdblackbox.domain.fuel

import io.github.eliasmeireles.obdblackbox.domain.Signal
import io.github.eliasmeireles.obdblackbox.domain.snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelRangeEstimatorTest : io.github.eliasmeireles.obdblackbox.ResourceTest() {
    private class MemoryStore(var usage: List<FuelUsage> = emptyList()) : FuelHistoryStore {
        override fun load() = usage
        override fun save(usage: List<FuelUsage>) { this.usage = usage }
    }

    @Test fun `unknown tank has no liters or range`() {
        val range = FuelRangeEstimator().advance(snapshot(), null)
        assertNull(range.litersLeft)
        assertNull(range.rangeKm)
    }

    @Test fun `median rejects slosh and expires readings after thirty seconds`() {
        val estimator = FuelRangeEstimator()
        fun read(at: Long, level: Double) = estimator.advance(snapshot(Signal.FUEL_LEVEL to level, Signal.RPM to 850.0, at = at), null)
        read(0, 50.0)
        read(500, 51.0)
        val slosh = read(1000, 90.0)
        assertEquals(51.0, slosh.levelPercent!!, 0.0)
        assertEquals(25.5, slosh.litersLeft!!, 0.001)
        assertNull("no fixed average: no range until the car's own is learned", slosh.rangeKm)
        assertTrue(slosh.learning)
        assertEquals(51.0, estimator.advance(snapshot(at = 60_000), null).levelPercent!!, 0.0)
        assertEquals(40.0, read(61_000, 40.0).levelPercent!!, 0.0)
    }

    @Test fun `even median and invalid readings retain level`() {
        val estimator = FuelRangeEstimator()
        estimator.advance(snapshot(Signal.FUEL_LEVEL to 40.0, Signal.RPM to 850.0), null)
        assertEquals(50.0, estimator.advance(snapshot(Signal.FUEL_LEVEL to 60.0, Signal.RPM to 850.0), null).levelPercent!!, 0.0)
        assertEquals(50.0, estimator.advance(snapshot(Signal.FUEL_LEVEL to Double.NaN), null).levelPercent!!, 0.0)
    }

    @Test fun `learns consumption after one km and persists between trips without bridging the gap`() {
        val store = MemoryStore()
        val history = FuelEconomyHistory(store)
        for (second in 0..35) history.advance(second * 1000L, 100.0, 10.0)
        assertTrue(history.learning)
        assertNull(history.averageKmPerLiter)
        history.advance(36_000, 100.0, 10.0)
        assertFalse(history.learning)
        assertEquals(10.0, history.averageKmPerLiter!!, 0.001)
        history.flush()
        val nextTrip = FuelEconomyHistory(store)
        nextTrip.advance(90_000_000, 100.0, 10.0)
        assertEquals(1.0, nextTrip.distanceKm, 0.001)
        val range = FuelRangeEstimator(store).advance(snapshot(Signal.FUEL_LEVEL to 50.0, Signal.RPM to 850.0), null)
        assertEquals(250.0, range.rangeKm!!, 0.001)
        assertFalse(range.learning)
    }

    @Test fun `most recent hundred km replaces earlier fuel usage and storage stays bounded`() {
        val store = MemoryStore()
        val history = FuelEconomyHistory(store)
        for (second in 0..3600) history.advance(second * 1000L, 100.0, 20.0)
        for (second in 3601..7200) history.advance(second * 1000L, 100.0, 10.0)
        history.flush()
        assertEquals(100.0, history.distanceKm, 0.001)
        assertEquals(10.0, history.averageKmPerLiter!!, 0.01)
        assertTrue(store.usage.size <= 102)
    }

    @Test fun `includes idle fuel but skips missing inputs and reconnect gaps`() {
        val store = MemoryStore(listOf(FuelUsage(20.0, 2.0)))
        val history = FuelEconomyHistory(store)
        for (second in 0..3600) history.advance(second * 1000L, 0.0, 1.0)
        history.advance(3_601_000, 100.0, null)
        history.advance(3_602_000, 100.0, 10.0)
        history.advance(4_000_000, 100.0, 10.0)
        assertEquals(20.0, history.distanceKm, 0.001)
        assertEquals(20.0 / 3.0, history.averageKmPerLiter!!, 0.001)
    }

    @Test fun `readings with the engine stopped are ignored (the ECU reports 0 % after key-off)`() {
        val estimator = FuelRangeEstimator()
        estimator.advance(snapshot(Signal.FUEL_LEVEL to 48.0, Signal.RPM to 880.0, at = 0), null)
        repeat(60) { estimator.advance(snapshot(Signal.FUEL_LEVEL to 0.0, Signal.RPM to 0.0, at = 1_000L + it * 500), null) }
        assertEquals(48.0, estimator.advance(snapshot(Signal.RPM to 0.0, at = 40_000), null).levelPercent!!, 0.0)
        assertEquals(48.0, estimator.advance(snapshot(Signal.FUEL_LEVEL to 0.0, at = 41_000), null).levelPercent!!, 0.0)
    }
}
