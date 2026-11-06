package io.github.eliasmeireles.obdblackbox.data.logs

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteElmTraceTest : io.github.eliasmeireles.obdblackbox.ResourceTest() {
    private val sent = mutableListOf<Pair<LogLevel, String>>()
    private val trace = RemoteElmTrace({ level, _, message -> sent += level to message }, debugBudget = 2, context = 2)

    @Test fun `milestones always go, request lines only within the budget`() {
        trace.log("connecting to KONNWEI")
        trace.log("> 0100")
        trace.log("< 4100BE1FA813 (40 ms)")
        trace.log("> 010C")
        trace.log("summary: 300 readings")

        assertEquals(
            listOf(
                LogLevel.INFO to "connecting to KONNWEI",
                LogLevel.DEBUG to "> 0100",
                LogLevel.DEBUG to "< 4100BE1FA813 (40 ms)",
                LogLevel.INFO to "summary: 300 readings",
            ),
            sent,
        )
    }

    @Test fun `a problem sends the lines that came right before it`() {
        trace.log("connecting to KONNWEI")
        repeat(2) { trace.log("> inicial") }
        trace.log("> 010C")
        trace.log("> 010D")
        trace.log("> 0111")
        trace.log("! the adapter did not answer 0111")

        assertEquals(
            listOf(LogLevel.DEBUG to "> 010D", LogLevel.DEBUG to "> 0111", LogLevel.WARN to "the adapter did not answer 0111"),
            sent.takeLast(3),
        )
    }

    @Test fun `a new connection restores the budget`() {
        trace.log("connecting to KONNWEI")
        repeat(3) { trace.log("> before") }
        trace.log("connecting to KONNWEI")
        trace.log("> after")

        assertEquals(LogLevel.DEBUG to "> after", sent.last())
    }
}
