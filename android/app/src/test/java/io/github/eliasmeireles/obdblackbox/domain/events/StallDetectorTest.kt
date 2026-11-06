package io.github.eliasmeireles.obdblackbox.domain.events

import io.github.eliasmeireles.obdblackbox.domain.Signal
import io.github.eliasmeireles.obdblackbox.domain.snapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class StallDetectorTest : io.github.eliasmeireles.obdblackbox.ResourceTest() {
    private fun rpms(vararg values: Double): List<Boolean> {
        val detector = StallDetector()
        return values.map { detector.onSnapshot(snapshot(Signal.RPM to it)) != null }
    }

    @Test fun `fires once when running engine drops to zero`() =
        assertEquals(listOf(false, false, false, true, false), rpms(800.0, 600.0, 300.0, 0.0, 0.0))

    @Test fun `does not fire when engine never ran`() =
        assertEquals(listOf(false, false), rpms(0.0, 0.0))

    @Test fun `fires again after a restart`() =
        assertEquals(listOf(false, true, false, true), rpms(800.0, 0.0, 850.0, 0.0))

    private fun sample(at: Long, rpm: Double, volts: Double, throttle: Double) = snapshot(
        Signal.RPM to rpm, Signal.SPEED to 0.0, Signal.MODULE_VOLTAGE to volts, Signal.THROTTLE to throttle, at = at,
    )

    @Test fun `power latch after key off is not a stall`() {
        val detector = StallDetector()
        val samples = listOf(
            sample(0, 892.0, 13.62, 16.1),
            sample(600, 901.0, 13.62, 16.1),
            sample(1200, 918.0, 12.62, 11.0),
            sample(1650, 0.0, 12.62, 17.3),
            sample(2150, 0.0, 12.62, 17.3),
            sample(31200, 0.0, 12.31, 17.3),
        )
        assertEquals(listOf(null, null, null, EngineStop.KEY_OFF, null, null), samples.map { detector.onSnapshot(it) })
    }

    @Test fun `rpm sag with charging voltage and opening throttle is a stall`() {
        val detector = StallDetector()
        val samples = listOf(
            sample(0, 900.0, 13.62, 16.1),
            sample(500, 820.0, 13.62, 18.0),
            sample(1000, 650.0, 13.60, 20.0),
            sample(1500, 300.0, 12.7, 24.0),
            sample(2000, 0.0, 12.62, 24.0),
        )
        assertEquals(listOf(null, null, null, null, EngineStop.STALL), samples.map { detector.onSnapshot(it) })
    }

    @Test fun `voltage drop without throttle closure is a stall`() {
        val detector = StallDetector()
        detector.onSnapshot(sample(0, 900.0, 13.62, 16.1))
        detector.onSnapshot(sample(500, 900.0, 12.62, 16.1))
        assertEquals(EngineStop.STALL, detector.onSnapshot(sample(1000, 0.0, 12.62, 17.3)))
    }

    @Test fun `second key-off on the car, voltage only dropping on the zero sample`() {
        val detector = StallDetector()
        val samples = listOf(
            sample(0, 880.0, 13.88, 14.1),
            sample(250, 881.0, 13.88, 14.1),
            sample(500, 880.0, 13.88, 14.1),
            sample(750, 872.0, 13.88, 14.1),
            sample(1000, 874.0, 13.88, 11.4),
            sample(1250, 874.0, 13.88, 11.0),
            sample(1500, 0.0, 12.81, 17.3),
        )
        assertEquals(EngineStop.KEY_OFF, samples.map { detector.onSnapshot(it) }.last())
    }

    @Test fun `sudden stop without the throttle closing is a stall`() {
        val detector = StallDetector()
        listOf(sample(0, 880.0, 13.88, 14.1), sample(500, 880.0, 13.88, 14.1), sample(1000, 878.0, 13.88, 14.5))
            .forEach { detector.onSnapshot(it) }
        assertEquals(EngineStop.STALL, detector.onSnapshot(sample(1500, 0.0, 12.81, 17.3)))
    }

    @Test fun `sag prevents key off classification even with throttle closure`() {
        val detector = StallDetector()
        detector.onSnapshot(sample(0, 900.0, 13.62, 16.1))
        detector.onSnapshot(sample(500, 700.0, 13.62, 18.0))
        detector.onSnapshot(sample(1000, 400.0, 12.62, 11.0))
        assertEquals(EngineStop.STALL, detector.onSnapshot(sample(1500, 0.0, 12.62, 17.3)))
    }

    @Test fun `old drop is not evidence for key off`() {
        val detector = StallDetector()
        detector.onSnapshot(sample(0, 900.0, 13.62, 16.1))
        detector.onSnapshot(sample(400, 900.0, 12.62, 11.0))
        assertEquals(EngineStop.STALL, detector.onSnapshot(sample(1800, 0.0, 12.62, 17.3)))
    }

    @Test fun `falling rpm must not lower the idle reference and hide a sag`() {
        val detector = StallDetector()
        detector.onSnapshot(sample(0, 900.0, 13.62, 16.1))
        detector.onSnapshot(sample(500, 760.0, 13.62, 18.0))
        detector.onSnapshot(sample(1000, 750.0, 12.62, 11.0))
        assertEquals(EngineStop.STALL, detector.onSnapshot(sample(1500, 0.0, 12.62, 17.3)))
    }
}
