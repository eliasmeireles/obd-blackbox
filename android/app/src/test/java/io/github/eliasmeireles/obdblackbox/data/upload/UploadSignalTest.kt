package io.github.eliasmeireles.obdblackbox.data.upload

import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class UploadSignalTest : io.github.eliasmeireles.obdblackbox.ResourceTest() {
    @Test fun `returns at once when a sample was queued`() = runTest {
        val signal = UploadSignal()
        signal.notifyQueued()
        signal.notifyQueued()

        signal.awaitQueued(5_000)

        assertEquals(0L, currentTime)
    }

    @Test fun `waits until the timeout when nothing was queued`() = runTest {
        val signal = UploadSignal()
        signal.notifyQueued()
        signal.awaitQueued(5_000)

        signal.awaitQueued(5_000)

        assertEquals(5_000L, currentTime)
    }
}
