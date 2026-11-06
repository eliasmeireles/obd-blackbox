package io.github.eliasmeireles.obdblackbox.data.elm

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Answers like an ELM327 plugged into a car: echo until `ATE0`, replies split into BLE-sized chunks, the engine
 * ECU answering the PIDs in [values] (data bytes as hex), several PIDs per request when [multiPid], ISO-TP
 * formatting for replies longer than one CAN frame, and nothing while [ignition] is off. [protocol] is what
 * `ATDPN` reports. [gearbox] PIDs are answered by a second ECU, before the engine (as with some ECUs); `ATCRA`
 * keeps one ECU's replies and `ATH1` shows the CAN IDs. In this fixture, the engine refuses physical requests
 * (`7F 01 11`).
 */
class FakeElm(
    @Volatile var ignition: Boolean = true,
    private val multiPid: Boolean = true,
    private val values: Map<Int, String> = DEFAULT_VALUES,
    private val stored: String = "430201330171",
    private val pending: String = "4700",
    private val chunkBytes: Int = 20,
    private val protocol: String = "A6",
    private val gearbox: Map<Int, String> = emptyMap(),
    private val craSupported: Boolean = true,
    @Volatile var engineRunning: Boolean = false,
    private val freezeFrame: String? = null,
    private val vehicleInfo: Map<Int, String> = emptyMap(),
) : ElmLink {
    val received: MutableList<String> = CopyOnWriteArrayList()
    private val outgoing = LinkedBlockingQueue<ByteArray>()
    @Volatile private var cleared = false
    private var echo = true
    private var headers = false
    private var physical = false
    private var filter: String? = null
    private val twentyNineBit get() = protocol.removePrefix("A") in setOf("7", "9")
    private val engineId get() = if (twentyNineBit) "18DAF110" else "7E8"
    private val gearboxId get() = if (twentyNineBit) "18DAF118" else "7E9"

    @Volatile private var closed = false

    override fun write(data: ByteArray) {
        if (closed) throw IOException("fechado")
        val command = String(data, Charsets.US_ASCII).trim()
        received += command
        val reply = answer(command.uppercase())
        val text = (if (echo) "$command\r" else "") + reply + "\r\r>"
        text.toByteArray(Charsets.US_ASCII).toList().chunked(chunkBytes).forEach { outgoing.put(it.toByteArray()) }
    }

    override fun read(timeoutMs: Long): ByteArray? {
        if (closed) throw IOException("fechado")
        return outgoing.poll(timeoutMs, TimeUnit.MILLISECONDS)
    }

    override fun close() {
        closed = true
    }

    private fun answer(command: String): String = when {
        command == "ATZ" -> "\r\rELM327 v1.5".also { echo = true; headers = false; filter = null; physical = false }
        command == "ATE0" -> "OK".also { echo = false }
        command == "ATH1" -> "OK".also { headers = true }
        command == "ATH0" -> "OK".also { headers = false }
        command == "ATI" -> "ELM327 v1.5"
        command == "ATDPN" -> protocol
        command == "ATAR" -> "OK".also { filter = null }
        command.startsWith("ATCRA") -> if (craSupported) "OK".also { filter = command.removePrefix("ATCRA").ifEmpty { null } } else "?"
        command.startsWith("ATSH") -> "OK".also { physical = command != "ATSH7DF" && command != "ATSHDB33F1" }
        command.startsWith("AT") -> "OK"
        !ignition -> if (command == "0100") "SEARCHING...\rUNABLE TO CONNECT" else "NO DATA"
        physical -> "7F0111"
        command.startsWith("01") -> mode01(command.drop(2))
        command == "04" -> if (engineRunning) "7F0422" else "44".also { cleared = true }
        command == "03" -> if (cleared) "4300" else stored
        command == "07" -> if (cleared) "4700" else pending
        command == "0A" -> "4A00"
        command == "020200" -> if (cleared) "4202000000" else freezeFrame?.let { "420200$it" } ?: "NO DATA"
        command.startsWith("02") -> values[command.substring(2, 4).toInt(16)]?.let { "42${command.substring(2, 4)}00$it" } ?: "NO DATA"
        command.startsWith("09") -> vehicleInfo[command.drop(2).toInt(16)]?.let { text ->
            isoTp("49${command.drop(2)}01" + text.toByteArray(Charsets.US_ASCII).joinToString("") { "%02X".format(it) })
        } ?: "NO DATA"
        else -> "?"
    }

    private fun mode01(request: String): String {
        val hex = if (request.length % 2 == 1) request.dropLast(1) else request
        val asked = hex.chunked(2).map { it.toInt(16) }.let { if (multiPid) it else it.take(1) }
        val replies = listOfNotNull(
            reply(gearboxId, asked.mapNotNull { pid -> gearbox[pid]?.let { "%02X".format(pid) + it } }),
            reply(engineId, asked.mapNotNull { pid -> value(pid)?.let { "%02X".format(pid) + it } }),
        ).filter { (id, _) -> filter == null || filter == id }
        if (replies.isEmpty()) return "NO DATA"
        return replies.joinToString("\r") { (id, payload) -> format(id, payload) }
    }

    private fun reply(id: String, answered: List<String>) = if (answered.isEmpty()) null else id to "41" + answered.joinToString("")

    private fun format(id: String, payload: String): String {
        val bytes = payload.length / 2
        if (headers) return id + "%02X".format(bytes) + payload
        return if (bytes <= 7) payload else isoTp(payload)
    }

    private fun value(pid: Int): String? = if (pid % 0x20 == 0) bitmap(pid) else values[pid]

    /** "Supported PIDs" reply: the PIDs in [values] plus the next range when it has any. */
    private fun bitmap(base: Int): String? {
        val supported = values.keys + listOf(0x20, 0x40, 0x60).filter { range -> values.keys.any { it > range } }
        val inRange = supported.filter { it in base + 1..base + 0x20 }
        if (base != 0 && inRange.isEmpty()) return null
        var bits = 0L
        inRange.forEach { bits = bits or (1L shl (32 - (it - base))) }
        return "%08X".format(bits)
    }

    private fun isoTp(payload: String): String {
        val frames = mutableListOf(payload.take(12))
        var rest = payload.drop(12)
        while (rest.isNotEmpty()) {
            frames += rest.take(14).padEnd(14, '0')
            rest = rest.drop(14)
        }
        return "%03X".format(payload.length / 2) + frames.mapIndexed { i, frame -> "\r${i % 16}:$frame" }.joinToString("")
    }

    companion object {
        val DEFAULT_VALUES = mapOf(
            0x05 to "7B", // 83 °C
            0x0B to "21", // 33 kPa
            0x0C to "0C80", // 800 rpm
            0x0D to "00", // 0 km/h
            0x11 to "20", // 12.5 %
            0x42 to "3714", // 14.1 V
        )
    }
}
