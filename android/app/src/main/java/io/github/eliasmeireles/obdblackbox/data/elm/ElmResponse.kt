package io.github.eliasmeireles.obdblackbox.data.elm

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import io.github.eliasmeireles.obdblackbox.domain.obd.Pid

/** What an ELM327 answered to one OBD request: the ECU payloads, or the adapter's error text. */
/** Positive ECU payloads only: a refusal (`7F <service> <code>`) becomes the [error]. */
data class ObdReply(val payloads: List<IntArray>, val error: String? = null) {
    val ok: Boolean get() = error == null && payloads.isNotEmpty()
}

/**
 * Parses ELM327 replies, assuming echo, spaces and headers off (`ATE0`, `ATS0`, `ATH0`) and CAN auto formatting on.
 * Single frames come as one hex line (`410C1AF8`); ISO-TP replies as a byte count followed by numbered lines
 * (`00E`, `0:410C1AF80D00`, `1:…`).
 */
object ElmResponse {
    private val ERRORS = listOf(
        "NO DATA", "UNABLE TO CONNECT", "CAN ERROR", "BUS ERROR", "BUS INIT", "BUS BUSY", "BUFFER FULL", "STOPPED",
        "FB ERROR", "DATA ERROR", "RX ERROR", "LV RESET", "ACT ALERT", "ERROR", "?",
    )
    private val HEX = Regex("^[0-9A-F]+$")
    private val FRAME = Regex("^[0-9A-F]:[0-9A-F]*$")
    private val NOISE = listOf("SEARCHING...", "OK")
    private const val NEGATIVE = 0x7F

    /** Reply lines without the prompt, blank lines, the echoed command and progress messages. */
    fun lines(raw: String, command: String): List<String> {
        val echo = command.replace(" ", "").uppercase()
        return raw.replace(">", "").split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.replace(" ", "").uppercase() != echo && !it.uppercase().startsWith("SEARCHING") }
    }

    /** The adapter's error message, when the reply is one. */
    fun error(lines: List<String>): String? {
        val upper = lines.map { it.uppercase() }
        return upper.firstOrNull { line -> ERRORS.any { line.contains(it) } }
    }

    fun obd(lines: List<String>): ObdReply {
        val all = payloads(lines)
        val positive = all.filter { it.firstOrNull() != NEGATIVE }
        if (positive.isNotEmpty()) return ObdReply(positive)
        val refusal = all.firstOrNull()?.let { text(R.string.msg_recusado_pela_central) + it.joinToString(" ") { byte -> "%02X".format(byte) } }
        return ObdReply(emptyList(), refusal ?: error(lines) ?: lines.firstOrNull() ?: text(R.string.msg_resposta_vazia))
    }

    /**
     * Supported-PID count per ECU, from a `0100` reply with headers on (`ATH1`): each line is the CAN ID
     * ([idLength] hex digits), the ISO-TP length byte and the payload (`18DAF110064100BE3EB813`).
     */
    fun responders(lines: List<String>, idLength: Int): Map<String, Int> {
        val result = linkedMapOf<String, Int>()
        for (raw in lines) {
            val line = raw.replace(" ", "").uppercase()
            if (!HEX.matches(line) || line.length < idLength + 2) continue
            val pci = line.substring(idLength, idLength + 2)
            if (pci[0] != '0') continue
            val data = bytes(line.substring(idLength + 2).take(pci[1].digitToInt(16) * 2)) ?: continue
            if (data.size < 6 || data[0] != 0x41 || data[1] != 0x00) continue
            result[line.take(idLength)] = supportedPids(0x00, data.copyOfRange(2, 6)).size
        }
        return result
    }

    /** One payload per ECU message, reassembling ISO-TP multi-frame replies. */
    fun payloads(lines: List<String>): List<IntArray> {
        val clean = lines.map { it.replace(" ", "").uppercase() }.filter { it !in NOISE }
        val result = mutableListOf<IntArray>()
        var i = 0
        while (i < clean.size) {
            val line = clean[i]
            val counted = line.length == 3 && HEX.matches(line) && clean.getOrNull(i + 1)?.let(FRAME::matches) == true
            when {
                counted || FRAME.matches(line) -> {
                    val length = if (counted) line.toInt(16) else null
                    if (counted) i++
                    val data = StringBuilder()
                    while (i < clean.size && FRAME.matches(clean[i])) data.append(clean[i++].substringAfter(':'))
                    val hex = if (length != null) data.take(length * 2) else data
                    bytes(hex.toString())?.let(result::add)
                }
                else -> {
                    bytes(line)?.let(result::add)
                    i++
                }
            }
        }
        return result
    }

    /** Mode 01 data per PID from a `41 <pid> <data> [<pid> <data>…]` payload (several PIDs in one request). */
    fun mode01(payload: IntArray): Map<Int, IntArray> {
        if (payload.firstOrNull() != 0x41) return emptyMap()
        val result = linkedMapOf<Int, IntArray>()
        var i = 1
        while (i < payload.size) {
            val pid = payload[i]
            val length = dataLength(pid) ?: break
            if (i + 1 + length > payload.size) break
            result[pid] = payload.copyOfRange(i + 1, i + 1 + length)
            i += 1 + length
        }
        return result
    }

    /** PIDs flagged in a "supported PIDs" reply (`41 00`, `41 20`, `41 40` + 4 bitmap bytes). */
    fun supportedPids(base: Int, bitmap: IntArray): Set<Int> = buildSet {
        for (bit in 0 until 32) {
            val byte = bitmap.getOrNull(bit / 8) ?: break
            if (byte and (0x80 shr (bit % 8)) != 0) add(base + bit + 1)
        }
    }

    /** Trouble codes from a mode 03 (stored) or 07 (pending) payload: `43 <count> <a b>…`. Never clears them. */
    fun dtcs(payload: IntArray, mode: Int): List<String> {
        if (payload.firstOrNull() != 0x40 + mode) return emptyList()
        val counted = payload.size >= 2 && (payload.size - 2) % 2 == 0 && payload[1] == (payload.size - 2) / 2
        val start = if (counted) 2 else 1
        return (start until payload.size - 1 step 2)
            .map { payload[it] to payload[it + 1] }
            .filter { (a, b) -> a != 0 || b != 0 }
            .map { (a, b) -> dtcCode(a, b) }
    }

    /** Bytes of the data after a mode 01 PID, for parsing replies that carry several PIDs. */
    fun dataLength(pid: Int): Int? = if (pid % 0x20 == 0) 4 else Pid.fromCode(pid)?.dataBytes

    /** Standard form of a trouble code from its two bytes (`01 30` → `P0130`). */
    fun dtcCode(a: Int, b: Int): String = "%c%d%X%02X".format("PCBU"[a shr 6], (a shr 4) and 0x3, a and 0xF, b)

    private fun bytes(hex: String): IntArray? {
        if (hex.isEmpty() || hex.length % 2 != 0 || !HEX.matches(hex)) return null
        return IntArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16) }
    }
}
