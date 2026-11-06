package io.github.eliasmeireles.obdblackbox.data.logs

import android.util.Log
import io.github.eliasmeireles.obdblackbox.data.elm.ElmTrace
import io.github.eliasmeireles.obdblackbox.data.upload.UploadPayload
import io.github.eliasmeireles.obdblackbox.data.upload.UploadQueue
import io.github.eliasmeireles.obdblackbox.data.upload.UploadSignal
import java.util.UUID

enum class LogLevel(val wire: String, val priority: Int) {
    DEBUG("debug", Log.DEBUG),
    INFO("info", Log.INFO),
    WARN("warn", Log.WARN),
    ERROR("error", Log.ERROR),
}

/** Where app log entries go: the upload queue (to monitorapi, `GET /api/v1/logs`) and logcat. */
fun interface AppLog {
    fun log(level: LogLevel, tag: String, message: String)

    companion object {
        val NONE = AppLog { _, _, _ -> }
    }
}

/**
 * Queues entries durably, so logs written offline or right before a crash still reach the server.
 *
 * An info or warning identical to one sent in the last [repeatWindowMs] is only counted (the car parked overnight
 * fails to connect thousands of times); it goes out again after the window, saying how often it repeated.
 */
class RemoteLog(
    private val queue: UploadQueue,
    private val signal: UploadSignal? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val repeatWindowMs: Long = REPEAT_WINDOW_MS,
    private val enabled: Boolean = true,
) : AppLog {
    private class Seen(val sentAt: Long, var repeats: Int = 0)

    private val seen = LinkedHashMap<String, Seen>()

    override fun log(level: LogLevel, tag: String, message: String) {
        runCatching { Log.println(level.priority, "OBD/$tag", message) }
        if (!enabled) return
        val text = collapse(level, tag, message) ?: return
        runCatching {
            queue.enqueueLog(UploadPayload.logEntry(UUID.randomUUID().toString(), clock(), level.wire, tag, text.take(MAX_MESSAGE)))
            signal?.notifyQueued()
        }
    }

    /** The text to send, or null when it is a recent repeat. */
    @Synchronized
    private fun collapse(level: LogLevel, tag: String, message: String): String? {
        if (level == LogLevel.DEBUG || level == LogLevel.ERROR) return message
        val key = "$tag|$message"
        val now = clock()
        val previous = seen[key]
        if (previous != null && now - previous.sentAt < repeatWindowMs) {
            previous.repeats++
            return null
        }
        seen.remove(key)
        seen[key] = Seen(now)
        while (seen.size > MAX_TRACKED) seen.remove(seen.keys.first())
        return if (previous != null && previous.repeats > 0) "$message (repeated ${previous.repeats}× since last sent)" else message
    }

    private companion object {
        const val MAX_MESSAGE = 4_000
        const val REPEAT_WINDOW_MS = 10 * 60_000L
        const val MAX_TRACKED = 200
    }
}

/**
 * Sends the adapter conversation to [log] without spending the phone's data plan: milestones and summaries
 * always; the request-by-request lines only at the start of each connection ([debugBudget] lines) and, when
 * something goes wrong, the [context] lines that came right before the problem.
 *
 * Lines follow [ElmTrace] conventions: `> ` sent, `< ` received, `~ ` discarded, `! ` problem, anything else a milestone.
 */
class RemoteElmTrace(
    private val log: AppLog,
    private val debugBudget: Int = DEBUG_BUDGET,
    private val context: Int = CONTEXT_LINES,
) : ElmTrace {
    private val recent = ArrayDeque<String>()
    private var budget = debugBudget

    @Synchronized
    override fun log(text: String) {
        when {
            text.startsWith("! ") -> {
                recent.forEach { log.log(LogLevel.DEBUG, TAG, it) }
                recent.clear()
                log.log(LogLevel.WARN, TAG, text.removePrefix("! "))
            }
            text.startsWith("> ") || text.startsWith("< ") || text.startsWith("~ ") -> debug(text)
            else -> {
                if (text.startsWith(CONNECTING)) {
                    budget = debugBudget
                    recent.clear()
                }
                log.log(LogLevel.INFO, TAG, text)
            }
        }
    }

    private fun debug(text: String) {
        if (budget > 0) {
            budget--
            log.log(LogLevel.DEBUG, TAG, text)
            return
        }
        recent.addLast(text)
        while (recent.size > context) recent.removeFirst()
    }

    companion object {
        const val TAG = "elm"
        const val CONNECTING = "connecting"
        private const val DEBUG_BUDGET = 300
        private const val CONTEXT_LINES = 40
    }
}

/** Every trace goes to all of [traces] (the local file and the server). */
class CompositeElmTrace(private vararg val traces: ElmTrace) : ElmTrace {
    override fun log(text: String) = traces.forEach { it.log(text) }
}
