package io.github.eliasmeireles.obdblackbox.data.elm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothDropHintTest : io.github.eliasmeireles.obdblackbox.ResourceTest() {
    @Test fun `recent ecu response suggests closing other obd apps`() {
        var now = 0L
        val hint = BluetoothDropHint { now }
        hint.ecuAnswered()
        now = 10_000
        assertTrue(hint.describe("conexão perdida").contains("Car Scanner"))
    }

    @Test fun `silent ecu or no response suggests powered down adapter`() {
        var now = 0L
        val hint = BluetoothDropHint { now }
        assertTrue(hint.describe("conexão perdida").contains("o adaptador desligou (carro desligado?)"))
        hint.ecuAnswered()
        now = 10_001
        assertTrue(hint.describe("conexão perdida").contains("o adaptador desligou (carro desligado?)"))
        assertFalse(hint.describe("conexão perdida").contains("Car Scanner"))
    }
}
