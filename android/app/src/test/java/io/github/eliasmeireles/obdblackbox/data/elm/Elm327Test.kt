package io.github.eliasmeireles.obdblackbox.data.elm

import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Elm327Test : io.github.eliasmeireles.obdblackbox.ResourceTest() {
    @Test fun `only actual ecu replies count as ecu activity`() {
        val fake = FakeElm()
        var answers = 0
        val link = object : ElmLink by fake {
            override fun onEcuAnswer() { answers++ }
        }
        val elm = Elm327(link).apply { initialize() }
        assertEquals(0, answers)
        elm.readPids(listOf(0x0C))
        assertEquals(1, answers)
        fake.ignition = false
        elm.readPids(listOf(0x0C))
        elm.send("ATI")
        assertEquals(1, answers)
    }

    @Test fun `initialize configures the adapter and reads its version`() {
        val fake = FakeElm()
        assertEquals("ELM327 v1.5", Elm327(fake).initialize())
        assertTrue(fake.received.containsAll(listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATAT2", "ATSPA6", "ATI")))
    }

    @Test fun `connecting to the engine ecu reads every supported range and keeps only its replies`() {
        val fake = FakeElm()
        val elm = Elm327(fake).apply { initialize() }
        val supported = elm.connectEcu()!!
        assertTrue(supported.containsAll(listOf(0x05, 0x0B, 0x0C, 0x0D, 0x11, 0x42)))
        assertTrue("ATCRA7E8" in fake.received)
        assertTrue("0140" in fake.received)
    }

    @Test fun `ignition off means no ecu`() {
        val elm = Elm327(FakeElm(ignition = false)).apply { initialize() }
        assertNull(elm.connectEcu())
    }

    @Test fun `several pids in one request, including multi frame replies`() {
        val elm = Elm327(FakeElm()).apply { initialize(); connectEcu() }
        val data = elm.readPids(listOf(0x0C, 0x0D, 0x11, 0x0B, 0x05, 0x42))
        assertArrayEquals(intArrayOf(0x0C, 0x80), data[0x0C])
        assertArrayEquals(intArrayOf(0x37, 0x14), data[0x42])
        assertEquals(6, data.size)
    }

    @Test fun `short requests ask for a single reply so the adapter does not wait`() {
        val fake = FakeElm()
        val elm = Elm327(fake).apply { initialize(); connectEcu() }
        elm.readPids(listOf(0x0C, 0x0D))
        assertTrue("010C0D1" in fake.received)
    }

    @Test fun `trouble codes are read without clearing them`() {
        val fake = FakeElm()
        val elm = Elm327(fake).apply { initialize(); connectEcu() }
        assertEquals(listOf("P0133", "P0171"), elm.readDtcs(3))
        assertEquals(emptyList<String>(), elm.readDtcs(7))
        assertTrue(fake.received.none { it == "04" })
    }

    @Test(expected = IOException::class)
    fun `a silent adapter times out`() {
        val silent = object : ElmLink {
            override fun write(data: ByteArray) = Unit
            override fun read(timeoutMs: Long): ByteArray? = null.also { Thread.sleep(minOf(timeoutMs, 5)) }
            override fun close() = Unit
        }
        Elm327(silent).send("ATI", timeoutMs = 50)
    }

    @Test fun `on 29-bit can the engine is found by its reply id, so the gearbox never answers rpm`() {
        val fake = FakeElm(protocol = "A7", gearbox = mapOf(0x00 to "80180000", 0x0C to "0000"))
        val elm = Elm327(fake).apply { initialize() }
        val supported = elm.connectEcu()!!
        assertTrue("ATCRA18DAF110" in fake.received)
        assertTrue(supported.containsAll(listOf(0x0C, 0x42)))
        assertArrayEquals(intArrayOf(0x0C, 0x80), elm.readPids(listOf(0x0C))[0x0C])
    }

    @Test fun `an adapter without receive filters still reads, taking the first ecu to answer`() {
        val fake = FakeElm(protocol = "A7", gearbox = mapOf(0x0C to "0000"), craSupported = false)
        val elm = Elm327(fake).apply { initialize(); connectEcu() }
        assertArrayEquals(intArrayOf(0x00, 0x00), elm.readPids(listOf(0x0C))[0x0C])
    }

    @Test fun `without direct addressing the first ecu to answer wins`() {
        val fake = FakeElm(protocol = "A3", gearbox = mapOf(0x0C to "0000"))
        val elm = Elm327(fake).apply { initialize(); connectEcu() }
        assertArrayEquals(intArrayOf(0x00, 0x00), elm.readPids(listOf(0x0C))[0x0C])
    }
}
