package io.github.eliasmeireles.obdblackbox.data.elm

import io.github.eliasmeireles.obdblackbox.data.bridge.BeforeClearPersistence
import io.github.eliasmeireles.obdblackbox.data.bridge.BridgeSource
import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import io.github.eliasmeireles.obdblackbox.data.bridge.BridgeConnection
import io.github.eliasmeireles.obdblackbox.data.bridge.BridgeProtocol
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Makes a ready-made ELM327 adapter look like our bridge (TMB/1, docs/bridge-protocol.md), so [BridgeSource][BridgeSource]
 * and the rest of the app work unchanged. A worker thread polls the ECU through [Elm327] and turns the replies into
 * TMB/1 lines; the app's commands (`RATE`, `PIDS`, `START`, `DTC`…) are answered locally.
 *
 * Polling: the fast PIDs (rpm, throttle, pedal, MAP, speed) every cycle, plus a few of the slow ones in rotation.
 * Slow values are repeated from the last reading for a few seconds, so the dashboard does not blink.
 */
class ElmBridge(
    link: ElmLink,
    private val trace: ElmTrace = ElmTrace.NONE,
    private val clock: () -> Long = System::currentTimeMillis,
    private val readTimeoutMs: Long = READ_TIMEOUT_MS,
    private val ecuRetryMs: Long = ECU_RETRY_MS,
) : BridgeConnection, BeforeClearPersistence {
    @Volatile override var saveBeforeClear: ((String) -> Unit)? = null
    private val elm = Elm327(link, trace, clock)
    private val closeLink: () -> Unit = { runCatching { link.close() } }
    private val output = LinkedBlockingQueue<String>()
    private val commands = LinkedBlockingQueue<Pair<String, String>>()
    private val worker = thread(start = false, isDaemon = true, name = "elm-bridge") { work() }
    private val bootedAt = clock()

    @Volatile private var periodMs = 200L
    @Volatile private var requested: List<Int> = emptyList()
    @Volatile private var streaming = false
    @Volatile private var closed = false
    @Volatile private var failure: String? = null

    private var supported: Set<Int>? = null
    private var adapter: String? = null
    private var multiPid: Boolean? = null
    private var slowCursor = 0
    private var seq = 0L
    private var emptyCycles = 0
    private var lastAnswerMs = 0L
    private var lastStatusMs = 0L
    private var rx = 0L
    private var tx = 0L
    private var errors = 0L
    private val slowCache = mutableMapOf<Int, Pair<IntArray, Long>>()
    private val window = Window()

    /** Counters for the periodic summary line, reset every [SUMMARY_EVERY_MS]. */
    private class Window {
        var startedMs = 0L
        var cycles = 0
        var requests = 0
        var unanswered = 0
        var totalMs = 0L
        var maxMs = 0L
    }

    fun start(): ElmBridge = apply { worker.start() }

    override fun readLine(): String? {
        val line = output.poll(readTimeoutMs, TimeUnit.MILLISECONDS) ?: throw IOException(failure ?: text(R.string.msg_o_adaptador_parou_de_responder))
        if (line == END) {
            output.offer(END)
            failure?.let { throw IOException(it) }
            return null
        }
        return line
    }

    override fun send(line: String) {
        val parts = line.trim().split(' ').filter { it.isNotEmpty() }
        val command = parts.firstOrNull() ?: return
        val argument = parts.getOrNull(1)
        when (command) {
            BridgeProtocol.PING -> output.offer("PONG")
            "RATE" -> argument?.toLongOrNull()?.let { periodMs = it.coerceIn(100, 5_000); ok(command) } ?: err(command, text(R.string.msg_valor_invalido))
            "PIDS" -> {
                requested = argument.orEmpty().split(',').mapNotNull { it.toIntOrNull(16) }.distinct()
                ok(command)
            }
            BridgeProtocol.START -> { streaming = true; ok(command) }
            BridgeProtocol.STOP -> { streaming = false; ok(command) }
            BridgeProtocol.DIAG -> commands.offer(command to (argument ?: "manual"))
            BridgeProtocol.DTC -> commands.offer((if (argument == "CLEAR") BridgeProtocol.CLEAR_DTCS else command) to "")
            else -> err(command, text(R.string.msg_comando_desconhecido))
        }
    }

    /** Unblocks [readLine] at once: the reader must not wait for the worker to notice. */
    override fun close() {
        closed = true
        output.offer(END)
        worker.interrupt()
        closeLink()
    }

    private fun work() {
        try {
            val identity = elm.initialize()
            adapter = identity
            trace.log("adapter: $identity")
            output.offer("HELLO ${BridgeProtocol.VERSION} fw=${identity.replace(' ', '-')} mode=can")
            while (!closed) loopOnce()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            if (!closed) {
                failure = e.message ?: e.javaClass.simpleName
                trace.log("! connection closed: $failure")
            }
        } finally {
            output.offer(END)
            closeLink()
        }
    }

    private fun loopOnce() {
        val started = clock()
        if (supported == null) {
            supported = elm.connectEcu()
            if (supported == null) {
                runCommands()
                status(force = true)
                Thread.sleep(ecuRetryMs)
                return
            }
            trace.log("engine ECU answered; supported PIDs: ${supported.orEmpty().sorted().joinToString(",") { "%02X".format(it) }}")
            readDiagnostics("connect")
            lastAnswerMs = clock()
            emptyCycles = 0
        }
        runCommands()
        if (streaming) cycle()
        status(force = false)
        summary()
        Thread.sleep((periodMs - (clock() - started)).coerceAtLeast(if (streaming) 0 else IDLE_SLEEP_MS))
    }

    private fun cycle() {
        val active = active()
        if (active.isEmpty()) return
        val now = clock()
        val fast = active.filter { it in FAST_PIDS }
        val slow = active.filterNot { it in FAST_PIDS }
        val read = mutableMapOf<Int, IntArray>()
        fast.chunked(Elm327.MAX_PIDS_PER_REQUEST).forEach { read += group(it) }
        if (slow.isNotEmpty()) {
            val count = if (multiPid == false) 1 else SLOW_PER_CYCLE
            val chunk = List(minOf(count, slow.size)) { slow[(slowCursor + it) % slow.size] }
            slowCursor = (slowCursor + chunk.size) % slow.size
            read += group(chunk)
        }
        read.forEach { (pid, data) -> if (pid !in FAST_PIDS) slowCache[pid] = data to now }
        val values = active.associateWith { pid ->
            read[pid] ?: slowCache[pid]?.takeIf { now - it.second <= SLOW_STALE_MS }?.first
        }
        output.offer(sampleLine(started = now, values))
        window.cycles++
        if (read.isEmpty()) {
            if (++emptyCycles >= EMPTY_CYCLES_LIMIT) {
                trace.log("! the ECU stopped answering; searching again")
                supported = null
                slowCache.clear()
                status(force = true)
            }
        } else {
            emptyCycles = 0
            lastAnswerMs = clock()
        }
    }

    /** Requested PIDs the ECU says it supports (all requested ones if it did not say). */
    private fun active(): List<Int> {
        val known = supported.orEmpty()
        return if (known.isEmpty()) requested else requested.filter { it in known }
    }

    /** Several PIDs per request when the ECU accepts it, otherwise one by one. */
    private fun group(pids: List<Int>): Map<Int, IntArray> {
        if (pids.size > 1 && multiPid != false) {
            val got = request(pids)
            if (multiPid == null) {
                multiPid = got.size > 1
                trace.log("several PIDs per request: ${if (multiPid == true) "yes" else "no"}")
            }
            if (multiPid == true) return got
        }
        return pids.flatMap { pid -> request(listOf(pid)).entries }.associate { it.key to it.value }
    }

    private fun request(pids: List<Int>): Map<Int, IntArray> {
        tx++
        val started = clock()
        val got = elm.readPids(pids)
        val took = clock() - started
        if (got.isEmpty()) errors++ else rx++
        window.requests++
        window.totalMs += took
        window.maxMs = maxOf(window.maxMs, took)
        if (got.isEmpty()) window.unanswered++
        return got
    }

    /** One line a minute with the polling rate and latency, so the trace stays useful without every request. */
    private fun summary() {
        val now = clock()
        if (window.startedMs == 0L) window.startedMs = now
        val elapsed = now - window.startedMs
        if (elapsed < SUMMARY_EVERY_MS) return
        if (window.requests > 0) {
            val perSecond = window.cycles * 1000.0 / elapsed
            trace.log(
                "summary: %d readings (%.1f/s), %d requests, %d unanswered, mean %d ms, max %d ms, PIDs %s".format(
                    window.cycles, perSecond, window.requests, window.unanswered, window.totalMs / window.requests, window.maxMs,
                    active().joinToString(",") { "%02X".format(it) },
                ),
            )
        }
        window.startedMs = now
        window.cycles = 0
        window.requests = 0
        window.unanswered = 0
        window.totalMs = 0
        window.maxMs = 0
    }

    /** Reads the ECU's state (lamp, codes, freeze frame, self-tests, identification) and records it. Only reads. */
    private fun readDiagnostics(reason: String): DiagnosticSnapshot? {
        if (supported == null) return null
        return runCatching {
            check(elm.obd("0100").ok) { text(R.string.msg_sem_comunicacao_com_a_central) }
            val snapshot = ElmDiagnostics.read(elm, supported.orEmpty(), adapter, reason, clock)
            // No status reply means that the ECU may have disappeared since connectEcu().
            check(snapshot.stored != null && snapshot.pending != null) { text(R.string.msg_a_central_nao_respondeu_ao_diagnostico) }
            val json = snapshot.toJson().toString()
            if (reason == "before_clear") saveBeforeClear?.invoke(json)
            output.offer("DIAG $json")
            trace.log("diagnostics: $json")
            snapshot
        }.onFailure { if (it is InterruptedException) throw it else trace.log("! incomplete diagnostics: ${it.message}") }.getOrNull()
    }

    private fun runCommands() {
        val request = commands.poll() ?: return
        when (request.first) {
            BridgeProtocol.DTC -> readDtcs()
            BridgeProtocol.DIAG -> if (readDiagnostics(request.second) != null) ok(request.first)
                else err(request.first, text(R.string.msg_sem_comunicacao_com_a_central))
            BridgeProtocol.CLEAR_DTCS -> clearCodes()
        }
    }

    private fun clearCodes() {
        if (readDiagnostics("before_clear") == null) {
            err(BridgeProtocol.CLEAR_DTCS, text(R.string.msg_nao_foi_possivel_guardar_o_diagnostico_antes_de_apagar))
            return
        }
        val refusal = runCatching { elm.clearDtcs() }.getOrElse { text(R.string.msg_a_central_nao_respondeu_ao_pedido_de_apagar) }
        slowCache.clear()
        val after = readDiagnostics("after_clear")
        when {
            refusal != null -> err(BridgeProtocol.CLEAR_DTCS, refusal)
            after == null -> err(BridgeProtocol.CLEAR_DTCS, text(R.string.msg_a_central_confirmou_mas_nao_respondeu_ao_diagnostico_apos_))
            else -> ok(BridgeProtocol.CLEAR_DTCS)
        }
    }

    private fun readDtcs() {
        if (supported == null) return err(BridgeProtocol.DTC, text(R.string.msg_sem_comunicacao_com_a_central))
        val stored = elm.readDtcs(3)
        val pending = elm.readDtcs(7)
        if (stored == null || pending == null) return err(BridgeProtocol.DTC, text(R.string.msg_a_central_nao_respondeu))
        output.offer("DTC stored=${stored.joinToString(",")} pending=${pending.joinToString(",")}")
        ok(BridgeProtocol.DTC)
    }

    private fun status(force: Boolean) {
        val now = clock()
        if (!force && now - lastStatusMs < STATUS_EVERY_MS) return
        lastStatusMs = now
        val bus = if (supported != null && now - lastAnswerMs <= STATUS_EVERY_MS) "ok" else "silent"
        output.offer("STATUS bus=$bus rx=$rx tx=$tx err=$errors")
    }

    private fun sampleLine(started: Long, values: Map<Int, IntArray?>): String = buildString {
        append("S ").append(++seq).append(' ').append(started - bootedAt)
        values.forEach { (pid, data) ->
            append(' ').append("%02X".format(pid)).append('=')
            append(data?.joinToString("") { "%02X".format(it) } ?: "NA")
        }
    }

    private fun ok(command: String) {
        output.offer("OK $command")
    }

    private fun err(command: String, reason: String) {
        output.offer("ERR $command $reason")
    }

    companion object {
        /** Read every cycle: what changes fast and matters at the moment of a stall. */
        val FAST_PIDS = setOf(0x0C, 0x0D, 0x11, 0x49, 0x4C, 0x0B)
        private const val SLOW_PER_CYCLE = 3
        private const val SLOW_STALE_MS = 5_000L
        private const val EMPTY_CYCLES_LIMIT = 5
        private const val STATUS_EVERY_MS = 5_000L
        private const val SUMMARY_EVERY_MS = 60_000L
        private const val IDLE_SLEEP_MS = 100L
        private const val READ_TIMEOUT_MS = 65_000L
        private const val ECU_RETRY_MS = 3_000L
        private const val END = "\u0000END"
    }
}
