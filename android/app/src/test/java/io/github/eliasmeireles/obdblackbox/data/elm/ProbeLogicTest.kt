package io.github.eliasmeireles.obdblackbox.data.elm

import io.github.eliasmeireles.obdblackbox.data.bridge.LinkMode
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeLogicTest : io.github.eliasmeireles.obdblackbox.ResourceTest() {
    /** A Bluetooth gadget that answers every command, but is not an ELM327 (e.g. a speaker with a serial port). */
    private class NotAnAdapter : ElmLink {
        private val out = LinkedBlockingQueue<ByteArray>()
        override fun write(data: ByteArray) { out.put("OK\r\r>".toByteArray()) }
        override fun read(timeoutMs: Long): ByteArray? = out.poll(timeoutMs, TimeUnit.MILLISECONDS)
        override fun close() = Unit
    }

    private fun device(classic: Boolean = true, ble: Boolean = false, paired: Boolean = true) =
        FoundAdapter("02:00:00:00:00:01", "KONNWEI", classic, ble, paired, rssi = -60)

    @Test fun `an ELM327 over classic bluetooth is compatible`() {
        val result = ProbeLogic(ElmTrace.NONE) { FakeElm() }.probe(device())
        val compatible = result as ProbeResult.Compatible
        assertEquals("ELM327 v1.5", compatible.identity)
        assertEquals(LinkMode.SPP, compatible.target.mode)
        assertEquals("KONNWEI", compatible.target.name)
    }

    @Test fun `classic refused, BLE works`() {
        val tried = mutableListOf<LinkMode>()
        val result = ProbeLogic(ElmTrace.NONE) { mode ->
            tried += mode
            if (mode == LinkMode.SPP) throw IOException("não conectou ao adaptador por Bluetooth clássico: read failed") else FakeElm()
        }.probe(device(classic = true, ble = true))
        assertEquals(listOf(LinkMode.SPP, LinkMode.BLE), tried)
        assertEquals(LinkMode.BLE, (result as ProbeResult.Compatible).target.mode)
    }

    @Test fun `a device that connects but is not an adapter is reported as not compatible`() {
        val result = ProbeLogic(ElmTrace.NONE) { NotAnAdapter() }.probe(device()) as ProbeResult.Failed
        assertTrue(result.reason, result.reason.contains("não respondeu como adaptador OBD"))
        assertTrue(result.hint.contains("não é compatível"))
    }

    @Test fun `a BLE device without the serial channel is not compatible`() {
        val result = ProbeLogic(ElmTrace.NONE) { throw IOException("o adaptador não tem um canal serial BLE conhecido") }
            .probe(device(classic = false, ble = true)) as ProbeResult.Failed
        assertTrue(result.reason.contains("canal de dados"))
    }

    @Test fun `an unpaired classic device that refuses suggests the pairing code`() {
        val result = ProbeLogic(ElmTrace.NONE) { throw IOException("não conectou: read failed, socket might closed or timeout") }
            .probe(device(paired = false)) as ProbeResult.Failed
        assertTrue(result.reason.contains("não aceitou a conexão"))
        assertTrue(result.hint.contains("1234"))
    }
}
