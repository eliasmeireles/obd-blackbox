package io.github.eliasmeireles.obdblackbox.domain.alerts

import io.github.eliasmeireles.obdblackbox.domain.Signal
import io.github.eliasmeireles.obdblackbox.domain.idle
import io.github.eliasmeireles.obdblackbox.domain.with
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertRulesTest : io.github.eliasmeireles.obdblackbox.ResourceTest() {
    private val rules = AlertRules()

    private fun ids(vararg changes: Pair<Signal, Double>) = rules.evaluate(idle().with(*changes)).map { it.id }

    @Test fun `healthy idle has no alerts`() = assertTrue(rules.evaluate(idle()).isEmpty())

    @Test fun `low voltage only matters with engine running`() {
        assertEquals(listOf(AlertId.LOW_VOLTAGE), ids(Signal.MODULE_VOLTAGE to 12.4))
        assertTrue(ids(Signal.MODULE_VOLTAGE to 12.4, Signal.RPM to 0.0).isEmpty())
    }

    @Test fun `idle drop when stopped and pedal released`() {
        assertEquals(listOf(AlertId.IDLE_DROP), ids(Signal.RPM to 520.0))
        assertTrue(ids(Signal.RPM to 520.0, Signal.SPEED to 40.0).isEmpty())
    }

    @Test fun `pedal pressed with low rpm means no throttle response`() {
        assertEquals(listOf(AlertId.NO_THROTTLE_RESPONSE), ids(Signal.RPM to 480.0, Signal.PEDAL to 35.0))
    }

    @Test fun `throttle is used when pedal pid is missing`() {
        val alerts = rules.evaluate(
            idle().copy(values = idle().values - Signal.PEDAL + (Signal.THROTTLE to 30.0) + (Signal.RPM to 450.0)),
        )
        assertEquals(listOf(AlertId.NO_THROTTLE_RESPONSE), alerts.map { it.id })
    }

    @Test fun `fuel trim beyond limits flags mixture`() {
        assertEquals(listOf(AlertId.LEAN_MIXTURE), ids(Signal.SHORT_FUEL_TRIM to 24.0))
        assertEquals(listOf(AlertId.RICH_MIXTURE), ids(Signal.SHORT_FUEL_TRIM to -23.0))
    }

    @Test fun `critical alerts come first and message uses comma decimals`() {
        val alerts = rules.evaluate(idle().with(Signal.RPM to 500.0, Signal.MODULE_VOLTAGE to 12.4, Signal.SHORT_FUEL_TRIM to 25.0))
        assertEquals(Severity.CRITICAL, alerts.first().severity)
        assertTrue(alerts.any { it.message == "Tensão baixa com motor ligado: 12,4 V" })
    }

    @Test fun `overheat is critical`() = assertEquals(listOf(AlertId.OVERHEAT), ids(Signal.COOLANT_TEMP to 108.0))
}
