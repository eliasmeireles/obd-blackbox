package io.github.eliasmeireles.obdblackbox.data.elm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ElmResponseTest : io.github.eliasmeireles.obdblackbox.ResourceTest() {
    @Test fun `lines drop the echo, the prompt and the search message`() {
        assertEquals(listOf("410C1AF8"), ElmResponse.lines("010C\rSEARCHING...\r410C1AF8\r\r", "010C"))
    }

    @Test fun `single frame reply with one pid`() {
        val payloads = ElmResponse.payloads(listOf("410C1AF8"))
        assertEquals(1, payloads.size)
        assertArrayEquals(intArrayOf(0x1A, 0xF8), ElmResponse.mode01(payloads[0])[0x0C])
    }

    @Test fun `multi frame reply with several pids is reassembled`() {
        val lines = listOf("00E", "0:410C0BE40D00", "1:1120491A4C1C0B", "2:21000000000000")
        val data = ElmResponse.mode01(ElmResponse.payloads(lines).single())
        assertEquals(listOf(0x0C, 0x0D, 0x11, 0x49, 0x4C, 0x0B), data.keys.toList())
        assertArrayEquals(intArrayOf(0x0B, 0xE4), data[0x0C])
        assertArrayEquals(intArrayOf(0x21), data[0x0B])
    }

    @Test fun `spaces left by clones are ignored`() {
        val data = ElmResponse.mode01(ElmResponse.payloads(listOf("41 0C 1A F8 0D 32")).single())
        assertArrayEquals(intArrayOf(0x32), data[0x0D])
    }

    @Test fun `adapter errors are reported, not parsed`() {
        val reply = ElmResponse.obd(listOf("NO DATA"))
        assertFalse(reply.ok)
        assertEquals("NO DATA", reply.error)
        assertEquals("UNABLE TO CONNECT", ElmResponse.obd(listOf("UNABLE TO CONNECT")).error)
    }

    @Test fun `unknown pid stops the parse instead of misreading`() {
        assertEquals(setOf(0x0C), ElmResponse.mode01(intArrayOf(0x41, 0x0C, 0x1A, 0xF8, 0x99, 0x01)).keys)
    }

    @Test fun `supported pids bitmap`() {
        val supported = ElmResponse.supportedPids(0x00, intArrayOf(0xBE, 0x1F, 0xA8, 0x13))
        assertEquals(setOf(0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x13, 0x15, 0x1C, 0x1F, 0x20), supported)
    }

    @Test fun `trouble codes with and without the count byte`() {
        assertEquals(listOf("P0133", "P0171"), ElmResponse.dtcs(intArrayOf(0x43, 0x02, 0x01, 0x33, 0x01, 0x71), mode = 3))
        assertEquals(listOf("P0133"), ElmResponse.dtcs(intArrayOf(0x43, 0x01, 0x33, 0x00, 0x00), mode = 3))
        assertEquals(listOf("C0123", "U0100"), ElmResponse.dtcs(intArrayOf(0x47, 0x02, 0x41, 0x23, 0xC1, 0x00), mode = 7))
        assertTrue(ElmResponse.dtcs(intArrayOf(0x43, 0x00), mode = 3).isEmpty())
    }

    @Test fun `a refusal from the ecu is an error, not data`() {
        val reply = ElmResponse.obd(listOf("7F0111"))
        assertFalse(reply.ok)
        assertEquals("recusado pela central: 7F 01 11", reply.error)
    }

    @Test fun `responders are told apart by their can id`() {
        val lines = listOf("18DAF11806410098180001", "18DAF110064100BE3EB813")
        assertEquals(mapOf("18DAF118" to 6, "18DAF110" to 18), ElmResponse.responders(lines, idLength = 8))
        assertEquals(mapOf("7E8" to 17), ElmResponse.responders(listOf("7E8064100BE1FA813"), idLength = 3))
    }
}
