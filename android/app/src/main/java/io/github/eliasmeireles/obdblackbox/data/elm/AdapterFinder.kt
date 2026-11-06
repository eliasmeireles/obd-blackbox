package io.github.eliasmeireles.obdblackbox.data.elm

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A Bluetooth device nearby or paired, as the phone's Bluetooth screen would list it. */
data class FoundAdapter(
    val address: String,
    val name: String?,
    val classic: Boolean,
    val ble: Boolean,
    val paired: Boolean,
    val rssi: Int?,
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: text(R.string.msg_sem_nome_value, (address).toString())

    /** Names used by ELM327 adapters, listed first. */
    val looksLikeObd: Boolean get() = OBD_NAMES.any { name.orEmpty().contains(it, ignoreCase = true) }

    val kind: String get() = when {
        classic && ble -> text(R.string.msg_classico_ble)
        classic -> text(R.string.msg_bluetooth_classico)
        else -> text(R.string.msg_bluetooth_ble)
    }

    /** Combines what two sources (paired list, classic search, BLE scan) know about the same device. */
    fun merge(other: FoundAdapter) = copy(
        name = name?.takeIf { it.isNotBlank() } ?: other.name,
        classic = classic || other.classic,
        ble = ble || other.ble,
        paired = paired || other.paired,
        rssi = listOfNotNull(rssi, other.rssi).maxOrNull(),
    )

    private companion object {
        val OBD_NAMES = listOf("OBD", "ELM", "KONNWEI", "KW9", "LINK", "VGATE", "USCAN", "OBDII", "CAR", "SCAN", "V-LINK", "IOS")
    }
}

/** What the adapter picker shows. */
data class FinderState(val adapters: List<FoundAdapter> = emptyList(), val scanning: Boolean = false, val problem: String? = null)

/**
 * Lists every Bluetooth device the phone can see, like its own Bluetooth screen: paired devices, a classic
 * discovery and a BLE scan, merged by address, for [SCAN_MS]. Needs [bluetoothPermissions].
 */
@SuppressLint("MissingPermission")
class AdapterFinder(private val context: Context) {
    private val _state = MutableStateFlow(FinderState())
    val state: StateFlow<FinderState> = _state.asStateFlow()
    private val handler = Handler(Looper.getMainLooper())
    private val stopLater = Runnable { stop() }
    private var receiverRegistered = false

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = addBle(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::addBle)

        override fun onScanFailed(errorCode: Int) {
            _state.update { it.copy(problem = text(R.string.msg_a_busca_ble_falhou_erro_value, (errorCode).toString())) }
        }
    }

    private val classicReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_FOUND) return
            val device = intent.device() ?: return
            val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt().takeIf { it != Short.MIN_VALUE.toInt() }
            val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: device.name
            add(FoundAdapter(device.address, name, classic = true, ble = device.type == BluetoothDevice.DEVICE_TYPE_DUAL,
                paired = device.bondState == BluetoothDevice.BOND_BONDED, rssi = rssi))
        }
    }

    fun start() {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        when {
            adapter == null -> return _state.update { FinderState(problem = text(R.string.msg_celular_sem_bluetooth)) }
            !hasBluetoothPermission(context) -> return _state.update { FinderState(problem = text(R.string.msg_permissao_de_bluetooth_nao_concedida)) }
            !adapter.isEnabled -> return _state.update { FinderState(problem = text(R.string.msg_ligue_o_bluetooth_do_celular)) }
        }
        stop()
        val paired = adapter!!.bondedDevices.orEmpty().map { device ->
            FoundAdapter(device.address, device.name, classic = device.type != BluetoothDevice.DEVICE_TYPE_LE,
                ble = device.type == BluetoothDevice.DEVICE_TYPE_LE || device.type == BluetoothDevice.DEVICE_TYPE_DUAL,
                paired = true, rssi = null)
        }
        _state.value = FinderState(sorted(paired), scanning = true)
        ContextCompat.registerReceiver(context, classicReceiver, IntentFilter(BluetoothDevice.ACTION_FOUND), ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
        runCatching { adapter.cancelDiscovery(); adapter.startDiscovery() }
        adapter.bluetoothLeScanner?.let { scanner ->
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            runCatching { scanner.startScan(null, settings, bleCallback) }
        }
        handler.postDelayed(stopLater, SCAN_MS)
    }

    fun stop() {
        handler.removeCallbacks(stopLater)
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (hasBluetoothPermission(context)) {
            runCatching { adapter?.bluetoothLeScanner?.stopScan(bleCallback) }
            runCatching { adapter?.cancelDiscovery() }
        }
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(classicReceiver) }
            receiverRegistered = false
        }
        _state.update { it.copy(scanning = false) }
    }

    private fun addBle(result: ScanResult) {
        val device = result.device
        val name = result.scanRecord?.deviceName ?: device.name
        add(FoundAdapter(device.address, name, classic = device.type == BluetoothDevice.DEVICE_TYPE_DUAL, ble = true,
            paired = device.bondState == BluetoothDevice.BOND_BONDED, rssi = result.rssi))
    }

    private fun add(found: FoundAdapter) {
        _state.update { state ->
            val existing = state.adapters.firstOrNull { it.address == found.address }
            val merged = existing?.merge(found) ?: found
            state.copy(adapters = sorted(state.adapters.filterNot { it.address == found.address } + merged))
        }
    }

    /** OBD-looking names first, then named devices by signal, then the unnamed ones (as the phone lists them). */
    private fun sorted(list: List<FoundAdapter>) = list.sortedWith(
        compareByDescending<FoundAdapter> { it.looksLikeObd }
            .thenByDescending { it.name != null }
            .thenByDescending { it.paired }
            .thenByDescending { it.rssi ?: Int.MIN_VALUE }
            .thenBy { it.displayName },
    )

    @Suppress("DEPRECATION")
    private fun Intent.device(): BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    } else {
        getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
    }

    private companion object {
        const val SCAN_MS = 15_000L
    }
}
