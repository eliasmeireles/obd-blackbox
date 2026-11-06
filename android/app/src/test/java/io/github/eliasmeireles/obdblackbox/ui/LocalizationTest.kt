package io.github.eliasmeireles.obdblackbox.ui

import io.github.eliasmeireles.obdblackbox.R
import io.github.eliasmeireles.obdblackbox.ResourceTest
import io.github.eliasmeireles.obdblackbox.TestStrings
import io.github.eliasmeireles.obdblackbox.data.video.BlackBoxState
import io.github.eliasmeireles.obdblackbox.domain.Signal
import io.github.eliasmeireles.obdblackbox.domain.alerts.AlertRules
import io.github.eliasmeireles.obdblackbox.domain.events.RecordedEvent
import io.github.eliasmeireles.obdblackbox.domain.obd.DtcDescriptions
import io.github.eliasmeireles.obdblackbox.domain.snapshot
import io.github.eliasmeireles.obdblackbox.i18n.quantity
import io.github.eliasmeireles.obdblackbox.i18n.text
import org.junit.Assert.*
import org.junit.Test

class LocalizationTest : ResourceTest() {
    @Test fun `English resources cover events trouble codes alerts and notifications`() {
        TestStrings.install("en")
        assertTrue(describeEvent(RecordedEvent("marca_1", 0, false)).startsWith("Event marked at"))
        assertEquals("System too lean, bank 1", DtcDescriptions.describe("P0171"))
        assertEquals("Cylinder 3 misfire", DtcDescriptions.describe("P0303"))
        assertEquals("Screen recording", text(R.string.msg_gravacao_de_tela))
        assertTrue(AlertRules().evaluate(snapshot(Signal.COOLANT_TEMP to 110.0)).single().message.startsWith("Engine overheating"))
    }

    @Test fun `cached descriptions enum labels and recording state follow language changes`() {
        val state = BlackBoxState()
        assertEquals("Gravação parada", state.message)
        assertEquals("Mistura pobre, banco 1", DtcDescriptions.describe("P0171"))
        TestStrings.install("en")
        assertEquals("Recording stopped", state.message)
        assertEquals("Engine", Signal.RPM.group.label)
        assertEquals("System too lean, bank 1", DtcDescriptions.describe("P0171"))
    }

    @Test fun `recent recording counts use singular and plural forms`() {
        assertEquals("1 gravação recente", quantity(R.plurals.recent_recording_count, 1, 1))
        assertEquals("2 gravações recentes", quantity(R.plurals.recent_recording_count, 2, 2))
        TestStrings.install("en")
        assertEquals("1 recent recording", quantity(R.plurals.recent_recording_count, 1, 1))
        assertEquals("2 recent recordings", quantity(R.plurals.recent_recording_count, 2, 2))
    }
}
