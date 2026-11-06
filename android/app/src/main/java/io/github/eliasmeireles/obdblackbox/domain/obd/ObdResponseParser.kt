package io.github.eliasmeireles.obdblackbox.domain.obd

/** A decoded mode 01 reading. */
data class PidReading(val pid: Pid, val value: Double)

/**
 * Parses ELM327-style replies such as "41 0C 1A F8" or "410C1AF8".
 * Returns null for errors ("NO DATA", "?") and unknown PIDs.
 */
object ObdResponseParser {
    private const val MODE_01_REPLY = 0x41
    private val HEX_ONLY = Regex("^[0-9A-F]+$")

    fun parse(line: String): PidReading? {
        val bytes = toBytes(line) ?: return null
        if (bytes.size < 3 || bytes[0] != MODE_01_REPLY) return null
        val pid = Pid.fromCode(bytes[1]) ?: return null
        val data = bytes.copyOfRange(2, bytes.size)
        if (data.size < pid.dataBytes) return null
        return PidReading(pid, pid.decode(data))
    }

    private fun toBytes(line: String): IntArray? {
        val hex = line.uppercase().replace(" ", "").trim()
        if (hex.isEmpty() || hex.length % 2 != 0 || !HEX_ONLY.matches(hex)) return null
        return IntArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16) }
    }
}
