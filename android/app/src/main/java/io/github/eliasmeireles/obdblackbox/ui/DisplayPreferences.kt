package io.github.eliasmeireles.obdblackbox.ui

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import android.content.Context
import android.content.pm.ActivityInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ScreenOrientation(private val labelId: Int, val activityInfo: Int) {
    AUTO(R.string.msg_automatica, ActivityInfo.SCREEN_ORIENTATION_SENSOR),
    PORTRAIT(R.string.msg_retrato, ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT),
    LANDSCAPE(R.string.msg_paisagem, ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

    val label: String get() = text(labelId)
}

/** Screen preferences of the dashboard (the phone lives in the car mount). */
class DisplayPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("display", Context.MODE_PRIVATE)
    private val _orientation = MutableStateFlow(load())
    private val mutableTheme = MutableStateFlow(AppTheme.parse(prefs.getString("theme", null)))
    val theme = mutableTheme.asStateFlow()

    fun setTheme(value: AppTheme) {
        prefs.edit().putString("theme", value.name).apply()
        mutableTheme.value = value
    }
    val orientation: StateFlow<ScreenOrientation> = _orientation.asStateFlow()

    fun setOrientation(value: ScreenOrientation) {
        prefs.edit().putString(KEY_ORIENTATION, value.name).apply()
        _orientation.value = value
    }

    private fun load(): ScreenOrientation =
        runCatching { ScreenOrientation.valueOf(prefs.getString(KEY_ORIENTATION, null).orEmpty()) }.getOrDefault(ScreenOrientation.AUTO)

    private companion object {
        const val KEY_ORIENTATION = "orientation"
    }
}
