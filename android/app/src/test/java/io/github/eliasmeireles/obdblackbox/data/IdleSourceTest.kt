package io.github.eliasmeireles.obdblackbox.data

import io.github.eliasmeireles.obdblackbox.data.bridge.SourceConfig
import io.github.eliasmeireles.obdblackbox.data.sim.SimulatedSource
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleSourceTest : io.github.eliasmeireles.obdblackbox.ResourceTest() {
    @Test fun `idle source never emits and reports no bridge`() = runTest {
        val idle = IdleSource()
        val received = mutableListOf<Any>()
        val job = launch { idle.snapshots().toList(received) }
        advanceUntilIdle()
        assertTrue(received.isEmpty())
        assertTrue(idle.status.value is SourceStatus.Disconnected)
        job.cancel()
    }

    @Test fun `simulator and simulated bridges count as simulation`() {
        assertEquals(SourceKind.SIMULATION, SimulatedSource().currentKind())
        assertEquals(SourceKind.OBD, IdleSource().currentKind())
    }

    @Test fun `simulation is off by default and tcp address is validated`() {
        assertFalse(SourceConfig().simulation)
        assertEquals("localhost" to 35000, SourceConfig("localhost:35000").tcpEndpoint())
        assertNull(SourceConfig("localhost").tcpEndpoint())
        assertNull(SourceConfig(":80").tcpEndpoint())
    }
}
