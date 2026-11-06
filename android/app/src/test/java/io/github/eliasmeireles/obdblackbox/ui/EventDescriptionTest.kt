package io.github.eliasmeireles.obdblackbox.ui

import io.github.eliasmeireles.obdblackbox.domain.events.RecordedEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventDescriptionTest : io.github.eliasmeireles.obdblackbox.ResourceTest() {
    @Test fun `key off is described without suggesting a stall`() {
        val text = describeEvent(RecordedEvent("desligado_pela_chave_1", 0L, automatic = true))
        assertTrue(text.contains("desligado pela chave"))
        assertTrue(text.contains("marcado automaticamente"))
        assertFalse(text.contains("sozinho"))
    }

    @Test fun `stall keeps its automatic event description`() {
        val text = describeEvent(RecordedEvent("desligou_sozinho_1", 0L, automatic = true))
        assertTrue(text.contains("Motor desligou sozinho"))
        assertTrue(text.contains("marcado automaticamente"))
    }

    @Test fun `manual mark stays manual`() {
        val text = describeEvent(RecordedEvent("marca_1", 0L, automatic = false))
        assertTrue(text.startsWith("Evento marcado às"))
        assertFalse(text.contains("automaticamente"))
    }
}
