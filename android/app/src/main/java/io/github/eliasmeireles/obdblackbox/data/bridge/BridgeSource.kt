package io.github.eliasmeireles.obdblackbox.data.bridge

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import io.github.eliasmeireles.obdblackbox.data.SourceKind
import io.github.eliasmeireles.obdblackbox.data.SourceStatus
import io.github.eliasmeireles.obdblackbox.data.VehicleDataSource
import io.github.eliasmeireles.obdblackbox.domain.VehicleSnapshot
import io.github.eliasmeireles.obdblackbox.domain.obd.Pid
import java.io.IOException
import io.github.eliasmeireles.obdblackbox.data.elm.ReceivedDiagnostic
import io.github.eliasmeireles.obdblackbox.data.logs.AppLog
import io.github.eliasmeireles.obdblackbox.data.logs.LogLevel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DtcReading(val stored: List<String>, val pending: List<String>, val timestampMs: Long)

/** Sources able to read the ECU trouble codes on demand. */
interface DtcReader {
    val dtcs: StateFlow<DtcReading?>

    /** Asks the bridge for the codes; false when there is no connection. The answer arrives in [dtcs]. */
    fun requestDtcs(): Boolean
}

/** Reads the car through the bridge (TMB/1), reconnecting forever while collected. */
class BridgeSource(
    private val transport: BridgeTransport,
    private val pids: List<Pid> = Pid.entries,
    private val periodMs: Long = DEFAULT_PERIOD_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val commandTimeoutMs: Long = 60_000,
    private val saveDiagnostic: (ReceivedDiagnostic) -> Unit = {},
    private val log: AppLog = AppLog.NONE,
) : VehicleDataSource, DtcReader, DiagnosticsReader {

    override val kind = SourceKind.OBD

    private val _status = MutableStateFlow<SourceStatus>(SourceStatus.Connecting)
    override val status: StateFlow<SourceStatus> = _status.asStateFlow()

    private val _dtcs = MutableStateFlow<DtcReading?>(null)
    override val dtcs: StateFlow<DtcReading?> = _dtcs.asStateFlow()

    private val _diagnostics = MutableStateFlow<ReceivedDiagnostic?>(null)
    override val diagnostics: StateFlow<ReceivedDiagnostic?> = _diagnostics.asStateFlow()
    private val commands = DiagnosticCommands(commandTimeoutMs, ioDispatcher, log) { connection }

    @Volatile private var connection: BridgeConnection? = null

    /**
     * Reconnects forever. Failed attempts in a row wait longer, up to [MAX_RETRY_DELAY_MS], so a parked car (adapter
     * off) does not keep the radio busy all night; a session that reached the bridge resets the wait.
     */
    override fun snapshots(): Flow<VehicleSnapshot> = channelFlow {
        var failures = 0
        while (isActive) {
            _status.value = SourceStatus.Connecting
            val reason = withContext(ioDispatcher) { session { send(it) } }
            failures = if (_status.value is SourceStatus.Connected) 0 else failures + 1
            _status.value = SourceStatus.Disconnected(reason)
            delay(minOf(retryDelayMs shl minOf(failures, 3), maxOf(retryDelayMs, MAX_RETRY_DELAY_MS)))
        }
    }

    /**
     * One connection, until it drops. Reads block a thread, so a watcher closes the link as soon as the
     * collector is cancelled; otherwise the read would hang until the socket timeout.
     */
    private suspend fun session(emit: suspend (VehicleSnapshot) -> Unit): String = coroutineScope {
        val link = try {
            transport.connect()
        } catch (e: IOException) {
            return@coroutineScope e.message ?: e.javaClass.simpleName
        }
        (link as? BeforeClearPersistence)?.saveBeforeClear = { json -> saveDiagnostic(ReceivedDiagnostic.parse(json)) }
        connection = link
        val closer = launch { closeWhenCancelled(link) }
        try {
            readUntilClosed(link, emit)
            text(R.string.msg_a_ponte_encerrou_a_conexao)
        } catch (e: IOException) {
            e.message ?: e.javaClass.simpleName
        } finally {
            connection = null
            commands.disconnected()
            closer.cancel()
            runCatching { link.close() }
        }
    }

    private suspend fun closeWhenCancelled(link: BridgeConnection) {
        try {
            awaitCancellation()
        } finally {
            runCatching { link.close() }
        }
    }

    override fun requestDtcs(): Boolean {
        val link = connection ?: return false
        return runCatching { link.send(BridgeProtocol.DTC) }.isSuccess
    }

    override suspend fun requestDiagnostics(): DiagnosticResult = commands.execute(BridgeProtocol.DIAG, text(R.string.msg_diagnostico_lido))

    override suspend fun clearDtcs(): DiagnosticResult = commands.execute(BridgeProtocol.CLEAR_DTCS, text(R.string.msg_codigos_apagados_a_central_confirmou))

    private fun onDiagnostic(reading: ReceivedDiagnostic, locallySaved: Boolean) {
        if (!(locallySaved && reading.snapshot.reason == "before_clear")) saveDiagnostic(reading)
        _diagnostics.value = reading
        val snapshot = reading.snapshot
        _dtcs.value = DtcReading(snapshot.stored.orEmpty(), snapshot.pending.orEmpty(), snapshot.t)
        log.log(LogLevel.INFO, "diag", "${snapshot.reason}: stored=${snapshot.stored}, pending=${snapshot.pending}, permanent=${snapshot.permanent}")
    }

    private suspend fun readUntilClosed(link: BridgeConnection, emit: suspend (VehicleSnapshot) -> Unit) {
        while (true) {
            val line = link.readLine() ?: return
            when (val message = BridgeProtocol.parse(line)) {
                is BridgeMessage.Diagnostics -> onDiagnostic(message.reading, link is BeforeClearPersistence)
                is BridgeMessage.Ok -> commands.complete(message.command, DiagnosticResult(true, ""))
                is BridgeMessage.Err -> commands.complete(message.command, DiagnosticResult(false, message.reason))
                is BridgeMessage.Hello -> onHello(link, message)
                is BridgeMessage.Sample -> emit(snapshotOf(message))
                is BridgeMessage.Dtcs -> _dtcs.value = DtcReading(message.stored, message.pending, clock())
                is BridgeMessage.Status -> _status.update { (it as? SourceStatus.Connected)?.copy(bus = message.bus) ?: it }
                else -> Unit
            }
        }
    }

    /** The bridge forgets the configuration on every connection, so it is sent after each HELLO. */
    private fun onHello(link: BridgeConnection, hello: BridgeMessage.Hello) {
        _status.value = SourceStatus.Connected(hello.firmware, simulatedCar = hello.mode == "sim")
        link.send(BridgeProtocol.rateCommand(periodMs))
        link.send(BridgeProtocol.pidsCommand(pids.map { it.code }))
        link.send(BridgeProtocol.START)
    }

    private fun snapshotOf(sample: BridgeMessage.Sample): VehicleSnapshot {
        val values = buildMap {
            for ((code, bytes) in sample.data) {
                val pid = Pid.fromCode(code) ?: continue
                if (bytes == null || bytes.size < pid.dataBytes) continue
                put(pid.signal, pid.decode(bytes))
            }
        }
        return VehicleSnapshot(clock(), values)
    }

    companion object {
        const val DEFAULT_PERIOD_MS = 200L
        private const val RETRY_DELAY_MS = 3_000L
        private const val MAX_RETRY_DELAY_MS = 15_000L
    }
}
