package io.github.eliasmeireles.obdblackbox.domain.obd

import org.junit.Assert.*
import org.junit.Test

class DtcDescriptionsTest : io.github.eliasmeireles.obdblackbox.ResourceTest() {
    @Test fun `common codes have a Portuguese description`() {
        assertEquals("Mistura pobre, banco 1", DtcDescriptions.describe("p0171"))
        assertEquals("Falha de combustão no cilindro 3", DtcDescriptions.describe("P0303"))
        assertEquals("Sistema de controle do câmbio: falha", DtcDescriptions.describe("P0700"))
    }

    @Test fun `unknown codes fall back to the family`() {
        assertEquals("Mistura e medição de ar/combustível", DtcDescriptions.describe("P0299"))
        assertEquals("Ignição e falhas de combustão", DtcDescriptions.describe("P0399"))
        assertEquals("Controle de emissões", DtcDescriptions.describe("P0499"))
        assertEquals("Velocidade, marcha lenta e entradas", DtcDescriptions.describe("P0599"))
        assertEquals("Central e saídas", DtcDescriptions.describe("P0699"))
        assertEquals("Câmbio", DtcDescriptions.describe("P0899"))
        assertEquals("Chassi", DtcDescriptions.describe("C1234"))
        assertEquals("Carroceria", DtcDescriptions.describe("B1234"))
        assertEquals("Rede de comunicação entre centrais", DtcDescriptions.describe("U0100"))
    }
}
