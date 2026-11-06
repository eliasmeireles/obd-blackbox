package io.github.eliasmeireles.obdblackbox.data.bridge

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import android.content.Context

/** How the app reaches a Bluetooth adapter: Classic serial port (SPP) or Low Energy (BLE). */
enum class LinkMode(val tag: String, private val labelId: Int) {
    SPP("spp", R.string.msg_bluetooth_classico),
    BLE("ble", R.string.msg_bluetooth_ble),
    ;

    val label: String get() = text(labelId)

    companion object {
        fun fromTag(tag: String?): LinkMode? = entries.firstOrNull { it.tag == tag }
    }
}

/** A ready-made ELM327 adapter (KONNWEI, Uscan…) chosen in the Dados tab. */
data class BluetoothTarget(val address: String, val name: String, val mode: LinkMode)

/**
 * Which source the app reads. [simulation] (off by default) uses the internal simulator; otherwise the bridge over
 * TCP when [tcpAddress] is set (development, fake bridge), or the ELM327 adapter in [bluetooth].
 */
data class SourceConfig(val tcpAddress: String? = null, val simulation: Boolean = false, val bluetooth: BluetoothTarget? = null) {
    /** "host:port" split, or null when not a valid address. */
    fun tcpEndpoint(): Pair<String, Int>? {
        val address = tcpAddress ?: return null
        val host = address.substringBeforeLast(':', "")
        val port = address.substringAfterLast(':', "").toIntOrNull() ?: return null
        return if (host.isBlank() || port !in 1..65_535) null else host to port
    }
}

class SourceConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("source", Context.MODE_PRIVATE)

    fun load(): SourceConfig {
        val address = prefs.getString(KEY_BT_ADDRESS, null)
        val mode = LinkMode.fromTag(prefs.getString(KEY_BT_MODE, null))
        val bluetooth = if (address != null && mode != null) BluetoothTarget(address, prefs.getString(KEY_BT_NAME, null).orEmpty(), mode) else null
        return SourceConfig(prefs.getString(KEY_TCP, null), prefs.getBoolean(KEY_SIMULATION, false), bluetooth)
    }

    fun save(config: SourceConfig) {
        prefs.edit()
            .putString(KEY_TCP, config.tcpAddress)
            .putBoolean(KEY_SIMULATION, config.simulation)
            .putString(KEY_BT_ADDRESS, config.bluetooth?.address)
            .putString(KEY_BT_NAME, config.bluetooth?.name)
            .putString(KEY_BT_MODE, config.bluetooth?.mode?.tag)
            .apply()
    }

    private companion object {
        const val KEY_TCP = "bridge_tcp"
        const val KEY_SIMULATION = "simulation"
        const val KEY_BT_ADDRESS = "bt_address"
        const val KEY_BT_NAME = "bt_name"
        const val KEY_BT_MODE = "bt_mode"
    }
}
