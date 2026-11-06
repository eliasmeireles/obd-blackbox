package io.github.eliasmeireles.obdblackbox.data.elm

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import java.io.IOException

/**
 * Talks ELM327 over a [ElmLink]: AT setup and OBD-II requests. Not thread-safe: one caller at a time.
 */
class Elm327(
    private val link: ElmLink,
    private val trace: ElmTrace = ElmTrace.NONE,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Whether the adapter accepts the "expected replies" suffix (`010C1`), which skips its reply timeout. */
    private var replyCount = false

    /**
     * CAN reply ID of the engine ECU while the adapter shows only its replies (`ATCRA`); null shows every ECU.
     * Requests stay functional (every ECU hears them): some engines refuses physical requests
     * (`7F 01 11`), and the gearbox also answers rpm and speed.
     */
    private var engineId: String? = null

    /** CAN reply ID of the engine ECU, when the adapter could be told to keep only its replies. */
    val engineEcu: String? get() = engineId

    /** OBD protocol number the adapter found (`ATDPN`, e.g. "7" = ISO 15765-4 CAN 29-bit 500 kbit/s). */
    var protocol: String? = null
        private set

    /** Resets and configures the adapter; returns its identification (e.g. "ELM327 v1.5"). */
    fun initialize(): String {
        val reset = runCatching { send("ATZ", RESET_TIMEOUT_MS) }.getOrDefault(emptyList())
        Thread.sleep(RESET_SETTLE_MS)
        drain()
        listOf("ATE0", "ATL0", "ATS0", "ATH0").forEach { send(it) }
        if (ElmResponse.error(send("ATAT2")) != null) send("ATAT1")
        send("ATSPA6")
        engineId = null
        val identity = send("ATI").firstOrNull { it.contains("ELM", ignoreCase = true) }
            ?: reset.firstOrNull { it.contains("ELM", ignoreCase = true) }
        return identity ?: "ELM327"
    }

    /**
     * Opens communication with the engine ECU (the adapter searches the protocol on the first request).
     * Returns the supported mode 01 PIDs, or null when no ECU answers (ignition off, wrong wiring).
     */
    fun connectEcu(): Set<Int>? {
        if (engineId != null) {
            send("ATAR")
            engineId = null
        }
        var first = obd("0100", SEARCH_TIMEOUT_MS)
        if (!first.ok) return null
        val protocol = send("ATDPN").firstOrNull().orEmpty().removePrefix("A")
        this.protocol = protocol
        val engine = findEngine(protocol)
        if (engine != null && ElmResponse.error(send("ATCRA$engine")) == null) {
            val only = obd("0100")
            if (only.ok && only.payloads.size == 1) {
                first = only
                engineId = engine
            } else {
                send("ATAR")
            }
        }
        replyCount = obd("01001").ok
        val target = engineId?.let { "only from ECU $it" } ?: "from every ECU"
        trace.log("protocol $protocol, replies $target, reply-count suffix ${if (replyCount) "yes" else "no"}")
        return supported(first)
    }

    /** Asks every ECU for its supported PIDs with headers on; the engine is the one that reports the most. */
    private fun findEngine(protocol: String): String? {
        val idLength = when (protocol) {
            "6", "8" -> 3
            "7", "9" -> 8
            else -> return null
        }
        send("ATH1")
        val lines = send("0100", OBD_TIMEOUT_MS)
        send("ATH0")
        val responders = ElmResponse.responders(lines, idLength)
        trace.log("ECUs that answered: " + responders.entries.joinToString { "${it.key} (${it.value} PIDs)" })
        return responders.maxByOrNull { it.value }?.key
    }

    /** Mode 01 data for up to 6 PIDs in one request; PIDs the ECU did not answer are missing. */
    fun readPids(pids: List<Int>): Map<Int, IntArray> {
        require(pids.size in 1..MAX_PIDS_PER_REQUEST)
        val suffix = if (replyCount && singleFrame(pids)) "1" else ""
        val reply = obd("01" + pids.joinToString("") { "%02X".format(it) } + suffix)
        val result = linkedMapOf<Int, IntArray>()
        reply.payloads.forEach { payload -> ElmResponse.mode01(payload).forEach { (pid, data) -> result.putIfAbsent(pid, data) } }
        return result.filterKeys { it in pids }
    }

    /** Mode 01 data of one PID; [length] is needed for PIDs the monitor does not poll (e.g. 01 = 4, 03 = 2). */
    fun readPid(pid: Int, length: Int? = null): IntArray? {
        val size = length ?: ElmResponse.dataLength(pid) ?: return null
        val reply = obd("01%02X".format(pid) + if (replyCount) "1" else "")
        val payload = reply.payloads.firstOrNull { it.size >= 2 + size && it[0] == 0x41 && it[1] == pid } ?: return null
        return payload.copyOfRange(2, 2 + size)
    }

    /** The code that stored the freeze frame (mode 02 PID 02), or null when there is no freeze frame. */
    fun readFreezeFrameTrigger(): String? {
        val payload = obd("020200").payloads.firstOrNull { it.size >= 5 && it[0] == 0x42 && it[1] == 0x02 } ?: return null
        if (payload[3] == 0 && payload[4] == 0) return null
        return ElmResponse.dtcCode(payload[3], payload[4])
    }

    /** One PID of the freeze frame (mode 02, frame 0). */
    fun readFreezeFrame(pid: Int, length: Int): IntArray? {
        val payload = obd("02%02X00".format(pid)).payloads.firstOrNull { it.size >= 3 + length && it[0] == 0x42 && it[1] == pid } ?: return null
        return payload.copyOfRange(3, 3 + length)
    }

    /** Mode 09 text (0x02 VIN, 0x04 calibration ID, 0x0A ECU name); null when the ECU does not answer it. */
    fun readVehicleInfo(type: Int): String? {
        val payload = obd("09%02X".format(type)).payloads.firstOrNull { it.size > 3 && it[0] == 0x49 && it[1] == type } ?: return null
        return payload.drop(3).filter { it in 0x20..0x7E }.map { it.toChar() }.joinToString("").trim().ifEmpty { null }
    }

    /**
     * Mode 04: clears the trouble codes, the freeze frame and the self-test results (the check-engine lamp goes off).
     * Returns null when the ECU confirmed, otherwise why it did not. Read and save everything before calling it.
     */
    fun clearDtcs(): String? {
        val reply = obd("04", CLEAR_TIMEOUT_MS)
        if (reply.payloads.any { it.firstOrNull() == 0x44 }) return null
        val error = reply.error.orEmpty()
        return when {
            error.contains("7F 04 22") -> text(R.string.msg_a_central_so_apaga_com_a_ignicao_ligada_e_o_motor_desligad)
            else -> text(R.string.msg_a_central_nao_confirmou)
        }
    }

    /** Stored (mode 3), pending (mode 7) or permanent (mode 0A) trouble codes; null when the ECU did not answer. */
    fun readDtcs(mode: Int): List<String>? {
        val reply = obd("%02X".format(mode))
        if (reply.error?.contains("NO DATA") == true) return emptyList()
        if (!reply.ok) return null
        return reply.payloads.flatMap { ElmResponse.dtcs(it, mode) }.distinct()
    }

    fun obd(request: String, timeoutMs: Long = OBD_TIMEOUT_MS): ObdReply {
        val reply = ElmResponse.obd(send(request, timeoutMs))
        if (reply.ok || reply.error?.startsWith(text(R.string.msg_recusado_pela_central).trimEnd()) == true) link.onEcuAnswer()
        return reply
    }

    /** Sends one command and waits for the `>` prompt; returns the reply lines. */
    fun send(command: String, timeoutMs: Long = AT_TIMEOUT_MS): List<String> {
        drain()
        val started = clock()
        trace.log("> $command")
        link.write("$command\r".toByteArray(Charsets.US_ASCII))
        val reply = StringBuilder()
        while (true) {
            val remaining = timeoutMs - (clock() - started)
            if (remaining <= 0) {
                trace.log("! no answer to $command in $timeoutMs ms: ${printable(reply)}")
                throw IOException(text(R.string.msg_o_adaptador_nao_respondeu_a_value, (command).toString()))
            }
            val chunk = link.read(remaining) ?: continue
            reply.append(String(chunk, Charsets.US_ASCII))
            val prompt = reply.indexOf(">")
            if (prompt >= 0) {
                reply.setLength(prompt)
                break
            }
        }
        trace.log("< ${printable(reply)} (${clock() - started} ms)")
        return ElmResponse.lines(reply.toString(), command)
    }

    private fun supported(first: ObdReply): Set<Int> {
        val result = mutableSetOf<Int>()
        var reply = first
        var base = 0x00
        while (true) {
            reply.payloads.forEach { payload ->
                if (payload.size >= 6 && payload[0] == 0x41 && payload[1] == base) {
                    result += ElmResponse.supportedPids(base, payload.copyOfRange(2, 6))
                }
            }
            val next = base + 0x20
            if (next > LAST_RANGE || next !in result) return result
            reply = obd("01%02X".format(next))
            base = next
        }
    }

    /** One CAN frame carries at most 7 payload bytes: `41` plus each PID and its data. */
    private fun singleFrame(pids: List<Int>): Boolean =
        1 + pids.sumOf { 1 + (ElmResponse.dataLength(it) ?: return false) } <= SINGLE_FRAME_BYTES

    /** Drops bytes left over from an earlier reply (late answers after a timeout). */
    private fun drain() {
        while (true) {
            val stale = link.read(0) ?: return
            trace.log("~ discarded: ${printable(String(stale, Charsets.US_ASCII))}")
        }
    }

    private fun printable(text: CharSequence) = text.toString().replace("\r", "↵").replace("\n", "")

    companion object {
        const val MAX_PIDS_PER_REQUEST = 6
        private const val LAST_RANGE = 0x40
        private const val SINGLE_FRAME_BYTES = 7
        private const val RESET_TIMEOUT_MS = 5_000L
        private const val RESET_SETTLE_MS = 300L
        private const val AT_TIMEOUT_MS = 3_000L
        private const val OBD_TIMEOUT_MS = 5_000L
        private const val SEARCH_TIMEOUT_MS = 20_000L
        private const val CLEAR_TIMEOUT_MS = 10_000L
    }
}
