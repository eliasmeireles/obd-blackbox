package io.github.eliasmeireles.obdblackbox.data.elm

import io.github.eliasmeireles.obdblackbox.data.bridge.BridgeMessage
import io.github.eliasmeireles.obdblackbox.data.bridge.BridgeProtocol
import io.github.eliasmeireles.obdblackbox.data.bridge.BridgeSource
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ElmDiagnosticCommandsTest : io.github.eliasmeireles.obdblackbox.ResourceTest() {
    private fun next(bridge: ElmBridge, predicate: (BridgeMessage) -> Boolean): BridgeMessage {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            val message = BridgeProtocol.parse(bridge.readLine() ?: error("ponte fechada")) ?: continue
            if (predicate(message)) return message
        }
        error("tempo esgotado aguardando resposta da ponte")
    }

    private fun connect(fake: FakeElm): ElmBridge = ElmBridge(fake).start().also { bridge ->
        next(bridge) { it is BridgeMessage.Diagnostics }
    }

    @Test fun `DIAG defaults to manual and preserves freeze frame and vehicle identification`() {
        val fake = FakeElm(freezeFrame = "0171", vehicleInfo = mapOf(2 to "EXAMPLEVIN00000001"))
        val bridge = connect(fake)
        try {
            bridge.send("DIAG")
            val reading = next(bridge) { it is BridgeMessage.Diagnostics } as BridgeMessage.Diagnostics
            assertEquals("manual", reading.reading.snapshot.reason)
            assertEquals("P0171", reading.reading.snapshot.freezeFrame!!.dtc)
            assertEquals(800.0, reading.reading.snapshot.freezeFrame!!.values.getValue("rpm"), 0.001)
            assertEquals("EXAMPLEVIN00000001", reading.reading.snapshot.vin)
            assertEquals(BridgeMessage.Ok("DIAG"), next(bridge) { it is BridgeMessage.Ok })
        } finally { bridge.close() }
    }

    @Test fun `clear persists before mode 04 and emits before and after snapshots`() {
        val fake = FakeElm()
        val bridge = connect(fake)
        val saved = CopyOnWriteArrayList<String>()
        bridge.saveBeforeClear = { json ->
            assertFalse(fake.received.contains("04"))
            saved += json
        }
        try {
            bridge.send("DTC CLEAR")
            val before = next(bridge) { it is BridgeMessage.Diagnostics } as BridgeMessage.Diagnostics
            val after = next(bridge) { it is BridgeMessage.Diagnostics } as BridgeMessage.Diagnostics
            assertEquals("before_clear", before.reading.snapshot.reason)
            assertEquals(listOf("P0133", "P0171"), before.reading.snapshot.stored)
            assertEquals("after_clear", after.reading.snapshot.reason)
            assertEquals(emptyList<String>(), after.reading.snapshot.stored)
            assertEquals(1, saved.size)
            assertEquals(BridgeMessage.Ok("DTC CLEAR"), next(bridge) { it is BridgeMessage.Ok })
        } finally { bridge.close() }
    }

    @Test fun `ECU refusal is returned in Portuguese and still reads after snapshot`() {
        val bridge = connect(FakeElm(engineRunning = true))
        try {
            bridge.send("DTC CLEAR")
            next(bridge) { it is BridgeMessage.Diagnostics }
            val after = next(bridge) { it is BridgeMessage.Diagnostics } as BridgeMessage.Diagnostics
            assertEquals("after_clear", after.reading.snapshot.reason)
            val error = next(bridge) { it is BridgeMessage.Err } as BridgeMessage.Err
            assertEquals("DTC CLEAR", error.command)
            assertEquals("a central só apaga com a ignição ligada e o motor desligado", error.reason)
        } finally { bridge.close() }
    }

    @Test fun `a failed durable save prevents mode 04`() {
        val fake = FakeElm()
        val bridge = connect(fake)
        bridge.saveBeforeClear = { error("disco cheio") }
        try {
            bridge.send("DTC CLEAR")
            next(bridge) { it is BridgeMessage.Err }
            assertFalse(fake.received.contains("04"))
        } finally { bridge.close() }
    }

    @Test fun `BridgeSource exposes snapshots saves every reading and awaits clear result`() = runBlocking {
        val readings = CopyOnWriteArrayList<ReceivedDiagnostic>()
        val source = BridgeSource({ ElmBridge(FakeElm()).start() }, saveDiagnostic = { readings += it })
        val job = launch(Dispatchers.IO) { source.snapshots().collect { } }
        try {
            withTimeout(10_000) { source.diagnostics.first { it != null } }
            assertTrue(source.requestDiagnostics().ok)
            assertTrue(source.clearDtcs().ok)
            assertEquals(listOf("connect", "manual", "before_clear", "after_clear"), readings.map { it.snapshot.reason })
            assertEquals("after_clear", source.diagnostics.value!!.snapshot.reason)
        } finally { job.cancel() }
    }

    @Test fun `losing the ECU before the snapshot prevents mode 04`() {
        val fake = FakeElm()
        val bridge = connect(fake)
        fake.ignition = false
        try {
            bridge.send("DTC CLEAR")
            next(bridge) { it is BridgeMessage.Err }
            assertFalse(fake.received.contains("04"))
        } finally { bridge.close() }
    }
}
