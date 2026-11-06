package io.github.eliasmeireles.obdblackbox.data.elm

import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Records the conversation with the adapter, so problems in the car can be analysed afterwards. */
fun interface ElmTrace {
    fun log(text: String)

    companion object {
        val NONE = ElmTrace { }
    }
}

/**
 * Appends to `elm-<date>.log` in [directory] (next to the CSV recordings) and to logcat (tag `ObdElm`).
 * Stops writing a day's file at [maxBytes] so a long trip cannot fill the phone.
 */
class FileElmTrace(
    private val directory: File,
    private val maxBytes: Long = MAX_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) : ElmTrace {
    private val day = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    override fun log(text: String) {
        val now = Date(clock())
        runCatching { Log.d(TAG, text) }
        runCatching {
            directory.mkdirs()
            val file = File(directory, "elm-${day.format(now)}.log")
            if (file.length() >= maxBytes) return
            FileWriter(file, true).use { it.write("${time.format(now)} $text\n") }
        }
    }

    private companion object {
        const val TAG = "ObdElm"
        const val MAX_BYTES = 20L * 1024 * 1024
    }
}
