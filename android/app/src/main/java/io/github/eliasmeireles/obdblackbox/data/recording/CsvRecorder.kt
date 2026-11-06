package io.github.eliasmeireles.obdblackbox.data.recording

import java.io.Writer

class CsvRecorder(
    override val name: String,
    private val writer: Writer,
    private val format: CsvFormat = CsvFormat(),
) : SessionRecorder {
    private var rowsSinceFlush = 0

    init {
        writer.write(format.header)
        writer.write("\n")
        writer.flush()
    }

    override fun record(row: TelemetryRow) {
        writer.write(format.line(row))
        writer.write("\n")
        rowsSinceFlush++
        // Events are flushed right away: they matter most if the phone dies right after.
        if (row.event != null || rowsSinceFlush >= FLUSH_EVERY_ROWS) {
            writer.flush()
            rowsSinceFlush = 0
        }
    }

    override fun close() = writer.close()

    private companion object {
        const val FLUSH_EVERY_ROWS = 25
    }
}
