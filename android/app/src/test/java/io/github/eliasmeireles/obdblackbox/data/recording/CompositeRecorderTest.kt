package io.github.eliasmeireles.obdblackbox.data.recording

import io.github.eliasmeireles.obdblackbox.data.SourceKind
import io.github.eliasmeireles.obdblackbox.domain.snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositeRecorderTest : io.github.eliasmeireles.obdblackbox.ResourceTest() {
    private class Memory(override val name: String) : SessionRecorder {
        val rows = mutableListOf<TelemetryRow>()
        var closed = false
        override fun record(row: TelemetryRow) { rows += row }
        override fun close() { closed = true }
    }

    @Test fun `forwards rows and closes every recorder`() {
        val a = Memory("a.csv")
        val b = Memory("fila")
        val recorder = CompositeRecorder.factory(RecorderFactory { a }, RecorderFactory { b })
            .create(SessionInfo("s1", SourceKind.SIMULATION, 0L))
        recorder.use { it.record(TelemetryRow(snapshot(), null, emptyList(), null)) }
        assertEquals("a.csv", recorder.name)
        assertEquals(1, a.rows.size)
        assertEquals(1, b.rows.size)
        assertTrue(a.closed && b.closed)
    }
}
