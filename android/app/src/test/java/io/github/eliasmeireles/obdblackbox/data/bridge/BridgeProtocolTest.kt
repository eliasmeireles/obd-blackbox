package io.github.eliasmeireles.obdblackbox.data.bridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BridgeProtocolTest : io.github.eliasmeireles.obdblackbox.ResourceTest() {
    @Test fun `parses hello`() =
        assertEquals(BridgeMessage.Hello("tmb/1", "0.1.0", "sim"), BridgeProtocol.parse("HELLO tmb/1 fw=0.1.0 mode=sim\r\n"))

    @Test fun `parses sample with bytes and NA`() {
        val sample = BridgeProtocol.parse("S 42 123456 0C=1AF8 0D=00 49=NA") as BridgeMessage.Sample
        assertEquals(42L, sample.seq)
        assertEquals(123456L, sample.uptimeMs)
        assertArrayEquals(intArrayOf(0x1A, 0xF8), sample.data[0x0C])
        assertArrayEquals(intArrayOf(0x00), sample.data[0x0D])
        assertEquals(true, sample.data.containsKey(0x49))
        assertNull(sample.data[0x49])
    }

    @Test fun `ignores malformed sample tokens`() {
        val sample = BridgeProtocol.parse("S 1 2 ZZ=00 0C=1 0D 0B=21") as BridgeMessage.Sample
        assertEquals(setOf(0x0C, 0x0B), sample.data.keys)
        assertNull(sample.data[0x0C])
    }

    @Test fun `parses trouble codes including empty lists`() {
        assertEquals(BridgeMessage.Dtcs(listOf("P0101", "P0300"), emptyList()), BridgeProtocol.parse("DTC stored=P0101,P0300 pending="))
    }

    @Test fun `parses status, replies and events`() {
        assertEquals(BridgeMessage.Status("silent", 10, 2, 0), BridgeProtocol.parse("STATUS bus=silent rx=10 tx=2 err=0"))
        assertEquals(BridgeMessage.Ok("START"), BridgeProtocol.parse("OK START"))
        assertEquals(BridgeMessage.Err("RATE", "out of range"), BridgeProtocol.parse("ERR RATE out of range"))
        assertEquals(BridgeMessage.Pong, BridgeProtocol.parse("PONG"))
        assertEquals(BridgeMessage.Event("can_started", "500000"), BridgeProtocol.parse("EV can_started 500000"))
    }

    @Test fun `unknown and empty lines are ignored`() {
        assertNull(BridgeProtocol.parse("F 7E8 0441"))
        assertNull(BridgeProtocol.parse(""))
        assertNull(BridgeProtocol.parse("S x 1"))
    }

    @Test fun `formats commands`() {
        assertEquals("PIDS 0C,0D,42", BridgeProtocol.pidsCommand(listOf(0x0C, 0x0D, 0x42)))
        assertEquals("RATE 200", BridgeProtocol.rateCommand(200))
    }
}
