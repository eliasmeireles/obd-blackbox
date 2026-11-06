package io.github.eliasmeireles.obdblackbox.domain

fun snapshot(vararg values: Pair<Signal, Double>, at: Long = 0L) = VehicleSnapshot(at, mapOf(*values))

fun idle(at: Long = 0L) = snapshot(
    Signal.RPM to 800.0,
    Signal.SPEED to 0.0,
    Signal.MODULE_VOLTAGE to 14.1,
    Signal.COOLANT_TEMP to 90.0,
    Signal.SHORT_FUEL_TRIM to 2.0,
    Signal.PEDAL to 0.0,
    at = at,
)

fun VehicleSnapshot.with(vararg changes: Pair<Signal, Double>) = copy(values = values + changes)
