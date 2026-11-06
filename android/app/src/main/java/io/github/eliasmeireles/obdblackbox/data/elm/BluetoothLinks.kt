package io.github.eliasmeireles.obdblackbox.data.elm

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

/** Bluetooth Classic serial port (SPP), used by most ELM327 adapters on Android. */
@SuppressLint("MissingPermission")
class SppLink private constructor(private val socket: BluetoothSocket) : QueuedLink() {
    private val output = socket.outputStream
    private val dropHint = BluetoothDropHint()

    init {
        thread(isDaemon = true, name = "elm-spp-reader") {
            val buffer = ByteArray(BUFFER_BYTES)
            try {
                val input = socket.inputStream
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    deliver(buffer.copyOf(count))
                }
                fail(dropHint.describe(text(R.string.msg_o_adaptador_encerrou_a_conexao)))
            } catch (e: IOException) {
                fail(dropHint.describe(text(R.string.msg_conexao_bluetooth_perdida_value, (e.message).toString())))
            }
        }
    }

    override fun write(data: ByteArray) {
        output.write(data)
        output.flush()
    }

    override fun onEcuAnswer() = dropHint.ecuAnswered()

    override fun close() {
        fail(text(R.string.msg_conexao_encerrada))
        runCatching { socket.close() }
    }

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        private const val BUFFER_BYTES = 1024

        fun open(adapter: BluetoothAdapter, address: String, trace: ElmTrace): SppLink {
            val device = adapter.getRemoteDevice(address)
            runCatching { adapter.cancelDiscovery() }
            // Cheap adapters (KONNWEI) refuse the secure socket at once and accept the insecure one: try that first.
            val attempts = listOf<(BluetoothDevice) -> BluetoothSocket>(
                { it.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
                { it.createRfcommSocketToServiceRecord(SPP_UUID) },
            )
            var last: IOException? = null
            for (create in attempts) {
                val socket = create(device)
                try {
                    socket.connect()
                    trace.log("SPP connected")
                    return SppLink(socket)
                } catch (e: IOException) {
                    trace.log("! SPP failed: ${e.message}")
                    runCatching { socket.close() }
                    last = e
                }
            }
            throw IOException(text(R.string.msg_nao_conectou_ao_adaptador_por_bluetooth_classico_value, (last?.message).toString()))
        }
    }
}

/**
 * Bluetooth Low Energy serial channel (adapters sold "for Android and iOS"). There is no standard service, so the
 * channel is the known ELM327 pairs (FFF0, FFE0, 18F0, vLinker) or else the first service with a notify and a write
 * characteristic. Notifications become read bytes; writes wait for the stack's confirmation, one at a time.
 */
@SuppressLint("MissingPermission")
class BleLink private constructor(private val trace: ElmTrace) : QueuedLink() {
    private val ready = CompletableFuture<Unit>()
    private val written = Semaphore(0)
    private var gatt: BluetoothGatt? = null
    private var writer: BluetoothGattCharacteristic? = null
    private var writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            trace.log("BLE state $newState (status $status)")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                if (!gatt.discoverServices()) ready.completeExceptionally(IOException(text(R.string.msg_falha_ao_listar_os_servicos_ble)))
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                val reason = text(R.string.msg_bluetooth_desconectado_status_value, (status).toString())
                ready.completeExceptionally(IOException(reason))
                fail(reason)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            trace.log("BLE services: " + gatt.services.joinToString("; ") { describe(it) })
            val channel = pickChannel(gatt.services)
                ?: return run { ready.completeExceptionally(IOException(text(R.string.msg_o_adaptador_nao_tem_um_canal_serial_ble_conhecido))) }
            trace.log("BLE channel: notify ${channel.notify.uuid}, write ${channel.write.uuid}")
            writer = channel.write
            writeType = if (channel.write.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            gatt.setCharacteristicNotification(channel.notify, true)
            val cccd = channel.notify.getDescriptor(CCCD)
            if (cccd == null) {
                ready.complete(Unit)
                return
            }
            val value = if (channel.notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
            if (!writeDescriptor(gatt, cccd, value)) ready.completeExceptionally(IOException(text(R.string.msg_falha_ao_ativar_as_notificacoes_ble)))
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) ready.complete(Unit)
            else ready.completeExceptionally(IOException(text(R.string.msg_notificacoes_ble_recusadas_status_value, (status).toString())))
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            deliver(value.copyOf())
        }

        @Deprecated("Android 12 and older")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            characteristic.value?.let { deliver(it.copyOf()) }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            written.release()
        }
    }

    override fun write(data: ByteArray) {
        val gatt = gatt ?: throw IOException(text(R.string.msg_conexao_ble_fechada))
        val characteristic = writer ?: throw IOException(text(R.string.msg_conexao_ble_sem_canal_de_escrita))
        for (start in data.indices step CHUNK_BYTES) {
            val chunk = data.copyOfRange(start, minOf(data.size, start + CHUNK_BYTES))
            written.drainPermits()
            if (!writeCharacteristic(gatt, characteristic, chunk)) throw IOException(text(R.string.msg_o_bluetooth_recusou_o_envio))
            // Writes without response are confirmed by the local stack only; a missing callback is not fatal for them.
            val noResponse = writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            val confirmed = written.tryAcquire(if (noResponse) NO_RESPONSE_WAIT_MS else WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!confirmed && !noResponse) throw IOException(text(R.string.msg_o_bluetooth_nao_confirmou_o_envio))
        }
    }

    override fun close() {
        fail(text(R.string.msg_conexao_encerrada))
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
    }

    private fun connect(context: Context, device: BluetoothDevice, timeoutMs: Long) {
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException(text(R.string.msg_nao_foi_possivel_abrir_a_conexao_ble))
        try {
            ready.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            close()
            throw IOException(text(R.string.msg_o_adaptador_nao_respondeu_pelo_ble_em_value_s, (timeoutMs / 1000).toString()))
        } catch (e: ExecutionException) {
            close()
            throw (e.cause as? IOException) ?: IOException(e.cause?.message ?: text(R.string.msg_falha_na_conexao_ble))
        }
        runCatching { gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
        trace.log("BLE connected")
    }

    private fun writeCharacteristic(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, value, writeType) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.writeType = writeType
                characteristic.value = value
                gatt.writeCharacteristic(characteristic)
            }
        }

    private fun writeDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                descriptor.value = value
                gatt.writeDescriptor(descriptor)
            }
        }

    private data class Channel(val notify: BluetoothGattCharacteristic, val write: BluetoothGattCharacteristic)

    companion object {
        private val CCCD: UUID = uuid16(0x2902)
        private const val CHUNK_BYTES = 20
        private const val WRITE_TIMEOUT_MS = 2_000L
        private const val NO_RESPONSE_WAIT_MS = 300L
        private const val CONNECT_TIMEOUT_MS = 20_000L

        /** Generic GATT services every BLE device has; never the serial channel. */
        private val STANDARD = setOf(0x1800, 0x1801, 0x180A, 0x180F).map(::uuid16).toSet()

        /** Known serial services of ELM327 BLE adapters: service to (notify, write). */
        private val KNOWN = listOf(
            uuid16(0xFFF0) to (uuid16(0xFFF1) to uuid16(0xFFF2)),
            uuid16(0xFFE0) to (uuid16(0xFFE1) to uuid16(0xFFE1)),
            uuid16(0x18F0) to (uuid16(0x2AF0) to uuid16(0x2AF1)),
            UUID.fromString("E7810A71-73AE-499D-8C15-FAA9AEF0C3F2") to
                (UUID.fromString("BEF8D6C9-9C21-4C9E-B632-BD58C1009F9F") to UUID.fromString("BEF8D6C9-9C21-4C9E-B632-BD58C1009F9F")),
        )

        fun open(context: Context, adapter: BluetoothAdapter, address: String, trace: ElmTrace): BleLink =
            BleLink(trace).apply { connect(context, adapter.getRemoteDevice(address), CONNECT_TIMEOUT_MS) }

        private fun pickChannel(services: List<BluetoothGattService>): Channel? {
            for ((service, pair) in KNOWN) {
                val found = services.firstOrNull { it.uuid == service } ?: continue
                val notify = found.getCharacteristic(pair.first) ?: continue
                val write = found.getCharacteristic(pair.second) ?: continue
                return Channel(notify, write)
            }
            return services.filter { it.uuid !in STANDARD }.firstNotNullOfOrNull { service ->
                val notify = service.characteristics.firstOrNull { it.properties and NOTIFY_OR_INDICATE != 0 }
                val write = service.characteristics.firstOrNull { it.properties and ANY_WRITE != 0 }
                if (notify != null && write != null) Channel(notify, write) else null
            }
        }

        private fun describe(service: BluetoothGattService) =
            "${service.uuid} [" + service.characteristics.joinToString(" ") { "${it.uuid.toString().take(8)}/${it.properties}" } + "]"

        private const val NOTIFY_OR_INDICATE = BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE
        private const val ANY_WRITE = BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE

        private fun uuid16(value: Int): UUID = UUID.fromString("%08X-0000-1000-8000-00805F9B34FB".format(value))
    }
}
