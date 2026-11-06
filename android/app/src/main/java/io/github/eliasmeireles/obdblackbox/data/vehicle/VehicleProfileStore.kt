package io.github.eliasmeireles.obdblackbox.data.vehicle

import android.content.Context
import io.github.eliasmeireles.obdblackbox.domain.fuel.FuelType
import io.github.eliasmeireles.obdblackbox.domain.vehicle.VehicleProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class VehicleProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("vehicle", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(load())
    val profile = mutable.asStateFlow()

    fun load() = VehicleProfile(
        prefs.getString("name", "").orEmpty(),
        positive("tank", 50.0), positive("displacement", 1.6),
        runCatching { FuelType.valueOf(prefs.getString("fuel", "").orEmpty()) }.getOrDefault(FuelType.GASOLINE),
    )

    fun save(profile: VehicleProfile) {
        prefs.edit().putString("name", profile.name.trim())
            .putString("tank", profile.tankCapacityL.toString())
            .putString("displacement", profile.displacementL.toString())
            .putString("fuel", profile.fuel.name).apply()
        mutable.value = profile
    }

    private fun positive(key: String, fallback: Double) = prefs.getString(key, null)?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it > 0 } ?: fallback
}
