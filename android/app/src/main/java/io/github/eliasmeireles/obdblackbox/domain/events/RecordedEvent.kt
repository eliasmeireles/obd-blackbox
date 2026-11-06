package io.github.eliasmeireles.obdblackbox.domain.events

/** An event written into the telemetry: a manual mark or an automatically classified engine stop. */
data class RecordedEvent(val label: String, val timestampMs: Long, val automatic: Boolean)
