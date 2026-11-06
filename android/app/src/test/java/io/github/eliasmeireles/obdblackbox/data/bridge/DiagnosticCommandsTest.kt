package io.github.eliasmeireles.obdblackbox.data.bridge

import io.github.eliasmeireles.obdblackbox.data.logs.AppLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticCommandsTest : io.github.eliasmeireles.obdblackbox.ResourceTest() {
    private class Link : BridgeConnection {
        val sent = mutableListOf<String>()
        var closed = false
        override fun readLine(): String? = null
        override fun send(line: String) { sent += line }
        override fun close() { closed = true }
    }

    @Test fun `no connection gives an immediate Portuguese failure`() = runTest {
        val commands = DiagnosticCommands(60_000, StandardTestDispatcher(testScheduler), AppLog.NONE) { null }
        assertEquals(DiagnosticResult(false, "o app não está conectado ao carro"), commands.execute("DIAG", "diagnóstico lido"))
    }

    @Test fun `only the matching compound command completes a pending operation`() = runTest {
        val link = Link()
        val commands = DiagnosticCommands(60_000, StandardTestDispatcher(testScheduler), AppLog.NONE) { link }
        val result = async { commands.execute("DTC CLEAR", "apagado") }
        runCurrent()
        commands.complete("DTC", DiagnosticResult(true, ""))
        assertFalse(result.isCompleted)
        commands.complete("DTC CLEAR", DiagnosticResult(false, "motor ligado"))
        assertEquals(DiagnosticResult(false, "motor ligado"), result.await())
        assertEquals(listOf("DTC CLEAR"), link.sent)
    }

    @Test fun `60 second timeout closes the stream so late replies cannot complete the next command`() = runTest {
        val link = Link()
        val commands = DiagnosticCommands(60_000, StandardTestDispatcher(testScheduler), AppLog.NONE) { link }
        val result = async { commands.execute("DIAG", "diagnóstico lido") }
        runCurrent()
        advanceTimeBy(60_001)
        runCurrent()
        assertEquals(DiagnosticResult(false, "tempo esgotado"), result.await())
        assertTrue(link.closed)
    }
}
