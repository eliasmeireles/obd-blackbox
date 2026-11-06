package io.github.eliasmeireles.obdblackbox.domain.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripStatsTest : io.github.eliasmeireles.obdblackbox.ResourceTest() {
    @Test fun `one minute at 60 kmh is one km`() {
        var trip = TripStats()
        for (second in 0..60) trip = trip.advance(second * 1_000L, 60.0, 6.0)
        assertEquals(1.0, trip.distanceKm, 0.001)
        assertEquals(0.1, trip.fuelUsedL, 0.001)
        assertEquals(10.0, trip.averageKmPerLiter!!, 0.01)
        assertEquals(60_000L, trip.elapsedMs)
    }

    @Test fun `long gaps are not integrated`() {
        val trip = TripStats().advance(0, 100.0, 8.0).advance(60_000, 100.0, 8.0)
        assertEquals(100.0 * 5.0 / 3_600.0, trip.distanceKm, 0.0001)
    }

    @Test fun `tracks max speed and has no average without fuel`() {
        val trip = TripStats().advance(0, 30.0, null).advance(1_000, 95.0, null).advance(2_000, 40.0, null)
        assertEquals(95.0, trip.maxSpeedKmh, 0.0)
        assertNull(trip.averageKmPerLiter)
    }
}
