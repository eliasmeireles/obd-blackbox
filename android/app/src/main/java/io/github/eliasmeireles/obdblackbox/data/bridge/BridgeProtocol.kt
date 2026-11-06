package io.github.eliasmeireles.obdblackbox.data.bridge

import io.github.eliasmeireles.obdblackbox.data.elm.ReceivedDiagnostic

/** Parses bridge lines and formats app commands for TMB/1. Unknown or malformed lines become null. */
object BridgeProtocol {
    const val VERSION = "tmb/1"
    private val HEX_BYTES = Regex("^([0-9A-Fa-f]{2})+$")

    fun parse(rawLine: String): BridgeMessage? {
        val line = rawLine.trimEnd('\r', '\n').trim()
        if (line.startsWith("DIAG ")) return runCatching {
            require(line.toByteArray(Charsets.UTF_8).size <= 8192)
            BridgeMessage.Diagnostics(ReceivedDiagnostic.parse(line.drop(5)))
        }.getOrNull()
        val parts = line.split(' ').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        return when (parts[0]) {
            "HELLO" -> hello(parts)
            "S" -> sample(parts)
            "DTC" -> dtcs(parts)
            "STATUS" -> status(parts)
            "OK" -> parts.getOrNull(1)?.let { BridgeMessage.Ok(parts.drop(1).joinToString(" ")) }
            "ERR" -> parts.getOrNull(1)?.let {
                val clear = parts.getOrNull(1) == "DTC" && parts.getOrNull(2) == "CLEAR"
                BridgeMessage.Err(if (clear) "DTC CLEAR" else it, parts.drop(if (clear) 3 else 2).joinToString(" "))
            }
            "PONG" -> BridgeMessage.Pong
            "EV" -> parts.getOrNull(1)?.let { BridgeMessage.Event(it, parts.drop(2).joinToString(" ")) }
            else -> null
        }
    }

    fun pidsCommand(pids: List<Int>): String = "PIDS " + pids.joinToString(",") { "%02X".format(it) }

    fun rateCommand(periodMs: Long): String = "RATE $periodMs"

    const val START = "START"
    const val STOP = "STOP"
    const val DTC = "DTC"
    const val DIAG = "DIAG"
    const val CLEAR_DTCS = "DTC CLEAR"
    const val PING = "PING"

    private fun hello(parts: List<String>): BridgeMessage? {
        val fields = keyValues(parts.drop(2))
        return BridgeMessage.Hello(parts.getOrNull(1) ?: return null, fields["fw"].orEmpty(), fields["mode"].orEmpty())
    }

    private fun sample(parts: List<String>): BridgeMessage? {
        val seq = parts.getOrNull(1)?.toLongOrNull() ?: return null
        val uptime = parts.getOrNull(2)?.toLongOrNull() ?: return null
        val data = mutableMapOf<Int, IntArray?>()
        for (token in parts.drop(3)) {
            val (pidHex, value) = token.split('=', limit = 2).takeIf { it.size == 2 } ?: continue
            val pid = pidHex.toIntOrNull(16)?.takeIf { pidHex.length == 2 } ?: continue
            data[pid] = bytesOrNull(value)
        }
        return BridgeMessage.Sample(seq, uptime, data)
    }

    private fun dtcs(parts: List<String>): BridgeMessage {
        val fields = keyValues(parts.drop(1))
        return BridgeMessage.Dtcs(codes(fields["stored"]), codes(fields["pending"]))
    }

    private fun status(parts: List<String>): BridgeMessage {
        val fields = keyValues(parts.drop(1))
        return BridgeMessage.Status(
            bus = fields["bus"].orEmpty(),
            rx = fields["rx"]?.toLongOrNull() ?: 0,
            tx = fields["tx"]?.toLongOrNull() ?: 0,
            errors = fields["err"]?.toLongOrNull() ?: 0,
        )
    }

    private fun bytesOrNull(value: String): IntArray? {
        if (!HEX_BYTES.matches(value)) return null
        return IntArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16) }
    }

    private fun codes(value: String?): List<String> = value.orEmpty().split(',').filter { it.isNotBlank() }

    private fun keyValues(tokens: List<String>): Map<String, String> =
        tokens.mapNotNull { token -> token.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
}
