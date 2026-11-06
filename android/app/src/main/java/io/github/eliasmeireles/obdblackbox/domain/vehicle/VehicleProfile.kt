package io.github.eliasmeireles.obdblackbox.domain.vehicle

import io.github.eliasmeireles.obdblackbox.domain.fuel.EngineSpec
import io.github.eliasmeireles.obdblackbox.domain.fuel.FuelEstimator
import io.github.eliasmeireles.obdblackbox.domain.fuel.FuelType

/** A profile is fixed for one session; saving changes starts a fresh session. */
data class VehicleProfile(
    val name: String = "",
    val tankCapacityL: Double = 50.0,
    val displacementL: Double = 1.6,
    val fuel: FuelType = FuelType.GASOLINE,
) {
    init {
        require(tankCapacityL.isFinite() && tankCapacityL > 0)
        require(displacementL.isFinite() && displacementL > 0)
    }

    fun fuelEstimator() = FuelEstimator(EngineSpec(displacementL), fuel)

    /** Keep learned economy separate when the vehicle or its fuel assumptions change. */
    val historyKey: String get() = "$name|$tankCapacityL|$displacementL|${fuel.name}"
}
