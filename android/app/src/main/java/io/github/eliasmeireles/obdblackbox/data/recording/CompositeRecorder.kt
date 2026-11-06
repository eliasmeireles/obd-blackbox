package io.github.eliasmeireles.obdblackbox.data.recording

/** Sends every row to all recorders (CSV on the phone and the upload queue). */
class CompositeRecorder(private val recorders: List<SessionRecorder>) : SessionRecorder {
    init {
        require(recorders.isNotEmpty()) { "at least one recorder is required" }
    }

    override val name: String = recorders.first().name

    override fun record(row: TelemetryRow) = recorders.forEach { it.record(row) }

    override fun close() {
        val failures = recorders.mapNotNull { runCatching { it.close() }.exceptionOrNull() }
        failures.firstOrNull()?.let { throw it }
    }

    companion object {
        fun factory(vararg factories: RecorderFactory) = RecorderFactory { session ->
            CompositeRecorder(factories.map { it.create(session) })
        }
    }
}
