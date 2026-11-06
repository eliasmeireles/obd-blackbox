package io.github.eliasmeireles.obdblackbox.data.fuel

import android.content.Context
import io.github.eliasmeireles.obdblackbox.domain.fuel.FuelHistoryStore
import io.github.eliasmeireles.obdblackbox.domain.fuel.FuelUsage

class PreferencesFuelHistoryStore(context: Context, private val profileKey: String = "default") : FuelHistoryStore {
    private val preferences = context.getSharedPreferences("fuel_economy", Context.MODE_PRIVATE)

    override fun load(): List<FuelUsage> = preferences.getString("$HISTORY_KEY:$profileKey", null).orEmpty()
        .split(';').mapNotNull { entry ->
            val parts = entry.split(',')
            val km = parts.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
            val liters = parts.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
            FuelUsage(km, liters)
        }

    override fun save(usage: List<FuelUsage>) {
        preferences.edit().putString("$HISTORY_KEY:$profileKey", usage.joinToString(";") { "${it.distanceKm},${it.liters}" }).apply()
    }

    private companion object {
        const val HISTORY_KEY = "recent_usage"
    }
}
