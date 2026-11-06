package io.github.eliasmeireles.obdblackbox.data.recording

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Creates one CSV per session in [directory], e.g. obd-sim-20261105-205500.csv. */
class FileRecorderFactory(
    private val directory: File,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) : RecorderFactory {
    private val unsafeName = Regex("[^\\p{L}\\p{N}_-]")
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    override fun create(session: SessionInfo): SessionRecorder {
        directory.mkdirs()
        val file = File(directory, "obd-${session.vehicle.replace(unsafeName, "_").take(48)}-${session.kind.fileTag}-${now().format(stamp)}-${session.id.take(8)}.csv")
        return CsvRecorder(file.name, file.bufferedWriter())
    }
}
