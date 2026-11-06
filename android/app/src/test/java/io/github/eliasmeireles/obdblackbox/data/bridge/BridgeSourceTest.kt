package io.github.eliasmeireles.obdblackbox.data.bridge

import io.github.eliasmeireles.obdblackbox.data.SourceStatus
import io.github.eliasmeireles.obdblackbox.domain.Signal
import io.github.eliasmeireles.obdblackbox.domain.obd.Pid
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeSourceTest : io.github.eliasmeireles.obdblackbox.ResourceTest() {
    private class ScriptedConnection(lines: List<String>) : BridgeConnection {
        private val incoming = LinkedBlockingQueue(lines.map { Line(it) })
        val sent = mutableListOf<String>()
        override fun readLine(): String? = incoming.take().value
        override fun send(line: String) { sent += line }
        override fun close() { incoming.put(Line(null)) }
        private data class Line(val value: String?)
    }

    private val script = listOf(
        "HELLO tmb/1 fw=fake-0.1 mode=sim",
        "OK RATE",
        "S 1 100 0C=0C80 0D=00 42=3714 49=NA",
        "STATUS bus=ok rx=10 tx=10 err=0",
        "DTC stored=P0101 pending=",
        "S 2 300 0C=0000 0D=00",
    )

    @Test fun `handshake configures the bridge and samples become snapshots`() = runBlocking {
        val link = ScriptedConnection(script)
        val source = BridgeSource({ link }, pids = listOf(Pid.RPM, Pid.SPEED), clock = { 7L }, ioDispatcher = Dispatchers.IO)
        val snapshots = withTimeout(5_000) { source.snapshots().take(2).toList() }
        assertEquals(listOf("RATE 200", "PIDS 0C,0D", "START"), link.sent)
        assertEquals(800.0, snapshots[0][Signal.RPM]!!, 0.001)
        assertEquals(14.1, snapshots[0][Signal.MODULE_VOLTAGE]!!, 0.001)
        assertFalse(snapshots[0].values.containsKey(Signal.PEDAL))
        assertEquals(7L, snapshots[0].timestampMs)
        assertEquals(0.0, snapshots[1][Signal.RPM]!!, 0.0)
        assertEquals(listOf("P0101"), source.dtcs.value!!.stored)
        val status = source.status.value as SourceStatus.Connected
        assertTrue(status.simulatedCar)
        assertEquals("ok", status.bus)
    }

    @Test fun `failed connection is reported and retried`() = runBlocking {
        var attempts = 0
        val link = ScriptedConnection(script)
        val transport = BridgeTransport { if (attempts++ == 0) throw IOException("sem rota") else link }
        val source = BridgeSource(transport, retryDelayMs = 10, ioDispatcher = Dispatchers.IO)
        withTimeout(5_000) { source.snapshots().first() }
        assertEquals(2, attempts)
    }

    @Test fun `trouble codes cannot be requested without a connection`() {
        assertFalse(BridgeSource({ throw IOException("x") }).requestDtcs())
    }
}
