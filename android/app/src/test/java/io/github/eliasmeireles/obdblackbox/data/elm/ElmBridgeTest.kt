package io.github.eliasmeireles.obdblackbox.data.elm

import io.github.eliasmeireles.obdblackbox.data.SourceStatus
import io.github.eliasmeireles.obdblackbox.data.bridge.BridgeSource
import io.github.eliasmeireles.obdblackbox.domain.Signal
import io.github.eliasmeireles.obdblackbox.domain.VehicleSnapshot
import io.github.eliasmeireles.obdblackbox.domain.obd.Pid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The whole chain the app uses with a ready-made adapter: BridgeSource → ElmBridge → Elm327 → adapter. */
class ElmBridgeTest : io.github.eliasmeireles.obdblackbox.ResourceTest() {
    private val pids = listOf(Pid.RPM, Pid.SPEED, Pid.THROTTLE, Pid.MAP, Pid.COOLANT_TEMP, Pid.MODULE_VOLTAGE, Pid.PEDAL, Pid.O2_B1S2)

    private fun source(fake: FakeElm) =
        BridgeSource({ ElmBridge(fake, ecuRetryMs = 10).start() }, pids = pids, periodMs = 100, retryDelayMs = 10, ioDispatcher = Dispatchers.IO)

    @Test fun `rear o2 is continuously polled with slow pids`() = runBlocking {
        val fake = FakeElm(values = FakeElm.DEFAULT_VALUES + (0x15 to "A0FF"))
        val source = source(fake)
        val readings = withTimeout(10_000) {
            source.snapshots().filter { it[Signal.O2_B1S2] != null }.take(8).toList()
        }
        assertFalse(0x15 in ElmBridge.FAST_PIDS)
        assertEquals(0.80, readings.last()[Signal.O2_B1S2]!!, 0.001)
        assertEquals(true, fake.received.count { it.startsWith("01") && it.drop(2).chunked(2).contains("15") } >= 2)
    }

    @Test fun `samples from the adapter become snapshots`() = runBlocking {
        val source = source(FakeElm())
        val snapshots = withTimeout(10_000) { source.snapshots().take(4).toList() }
        val last = snapshots.last()
        assertEquals(800.0, last[Signal.RPM]!!, 0.001)
        assertEquals(0.0, last[Signal.SPEED]!!, 0.001)
        assertEquals(33.0, last[Signal.MAP]!!, 0.001)
        assertEquals(83.0, last[Signal.COOLANT_TEMP]!!, 0.001)
        assertEquals(14.1, last[Signal.MODULE_VOLTAGE]!!, 0.001)
        assertFalse("unsupported pid is not polled", last.values.containsKey(Signal.PEDAL))
        val status = source.status.value as SourceStatus.Connected
        assertEquals("ELM327-v1.5", status.firmware)
        assertFalse(status.simulatedCar)
    }

    @Test fun `ecu without multi pid requests is read one pid at a time`() = runBlocking {
        val source = source(FakeElm(multiPid = false))
        val snapshot = withTimeout(10_000) { source.snapshots().filter { it.values.containsKey(Signal.MODULE_VOLTAGE) }.first() }
        assertEquals(800.0, snapshot[Signal.RPM]!!, 0.001)
        assertEquals(33.0, snapshot[Signal.MAP]!!, 0.001)
    }

    @Test fun `ignition off is reported as a silent bus until the ecu answers`() = runBlocking {
        val fake = FakeElm(ignition = false)
        val source = source(fake)
        val first = CompletableDeferred<VehicleSnapshot>()
        val job = launch(Dispatchers.IO) { source.snapshots().collect { first.complete(it) } }
        withTimeout(10_000) { source.status.first { (it as? SourceStatus.Connected)?.bus == "silent" } }
        fake.ignition = true
        val snapshot = withTimeout(10_000) { first.await() }
        assertEquals(800.0, snapshot[Signal.RPM]!!, 0.001)
        job.cancel()
    }

    @Test fun `trouble codes are read on request`() = runBlocking {
        val source = source(FakeElm())
        val job = launch(Dispatchers.IO) { source.snapshots().collect { } }
        withTimeout(10_000) { source.status.first { it is SourceStatus.Connected } }
        withTimeout(10_000) {
            while (!source.requestDtcs()) kotlinx.coroutines.delay(10)
            source.dtcs.first { it != null }
        }
        assertEquals(listOf("P0133", "P0171"), source.dtcs.value!!.stored)
        assertEquals(emptyList<String>(), source.dtcs.value!!.pending)
        job.cancel()
    }
}
