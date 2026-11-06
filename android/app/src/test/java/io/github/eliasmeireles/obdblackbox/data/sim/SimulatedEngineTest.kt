package io.github.eliasmeireles.obdblackbox.data.sim

import io.github.eliasmeireles.obdblackbox.domain.Signal
import io.github.eliasmeireles.obdblackbox.domain.VehicleSnapshot
import io.github.eliasmeireles.obdblackbox.domain.alerts.AlertId
import io.github.eliasmeireles.obdblackbox.domain.alerts.AlertRules
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatedEngineTest : io.github.eliasmeireles.obdblackbox.ResourceTest() {
    private fun run(engine: SimulatedEngine, seconds: Double): List<VehicleSnapshot> =
        (1..(seconds / 0.2).toInt()).map { engine.step(0.2, it * 200L) }

    @Test fun `idle settles around 800 rpm stopped`() {
        val last = run(SimulatedEngine(Random(1)), 10.0).last()
        assertTrue(last[Signal.RPM]!! in 750.0..850.0)
        assertEquals(0.0, last[Signal.SPEED]!!, 0.5)
    }

    @Test fun `driving loop reaches cruise speed`() {
        val maxSpeed = run(SimulatedEngine(Random(1)), 70.0).maxOf { it[Signal.SPEED]!! }
        assertTrue("max $maxSpeed", maxSpeed > 85.0)
    }

    @Test fun `stall weakens the engine, triggers alerts and dies`() {
        val engine = SimulatedEngine(Random(1))
        run(engine, 2.0)
        engine.requestStall()
        val samples = run(engine, 14.0)
        val alerts = samples.flatMap { s -> AlertRules().evaluate(s).map { it.id } }.toSet()
        assertTrue(alerts.containsAll(listOf(AlertId.IDLE_DROP, AlertId.NO_THROTTLE_RESPONSE, AlertId.LEAN_MIXTURE)))
        assertEquals(0.0, samples.last()[Signal.RPM]!!, 0.0)
    }

    @Test fun `stall requested while driving waits until the car stops`() {
        val engine = SimulatedEngine(Random(1))
        run(engine, 45.0)
        engine.requestStall()
        val samples = run(engine, 30.0)
        val firstDead = samples.indexOfFirst { it[Signal.RPM]!! == 0.0 }
        assertTrue(firstDead > 0)
        assertTrue(samples[firstDead][Signal.SPEED]!! < 3.0)
    }

    @Test fun `engine restarts after being off`() {
        val engine = SimulatedEngine(Random(1))
        engine.requestStall()
        val last = run(engine, 30.0).last()
        assertTrue(last[Signal.RPM]!! > 600.0)
    }
}
