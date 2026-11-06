package io.github.eliasmeireles.obdblackbox.data.elm

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.eliasmeireles.obdblackbox.data.bridge.BluetoothTarget
import io.github.eliasmeireles.obdblackbox.data.bridge.BridgeConnection
import io.github.eliasmeireles.obdblackbox.data.bridge.BridgeTransport
import io.github.eliasmeireles.obdblackbox.data.bridge.LinkMode
import java.io.IOException

/** Opens the chosen ELM327 adapter over Bluetooth and presents it as a TMB/1 bridge ([ElmBridge]). */
class ElmBluetoothTransport(
    private val context: Context,
    private val target: BluetoothTarget,
    private val trace: ElmTrace,
) : BridgeTransport {

    override fun connect(): BridgeConnection {
        if (!hasBluetoothPermission(context)) throw IOException(text(R.string.msg_permissao_de_bluetooth_nao_concedida_dados_adaptador))
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: throw IOException(text(R.string.msg_celular_sem_bluetooth))
        if (!adapter.isEnabled) throw IOException(text(R.string.msg_bluetooth_do_celular_desligado))
        trace.log("connecting to ${target.name} ${target.address} (${target.mode.tag})")
        val link = when (target.mode) {
            LinkMode.SPP -> SppLink.open(adapter, target.address, trace)
            LinkMode.BLE -> BleLink.open(context, adapter, target.address, trace)
        }
        return ElmBridge(link, trace).start()
    }
}

/** Permissions needed to find and talk to Bluetooth adapters on this Android version. */
fun bluetoothPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
} else {
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

fun hasBluetoothPermission(context: Context): Boolean =
    bluetoothPermissions().all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
