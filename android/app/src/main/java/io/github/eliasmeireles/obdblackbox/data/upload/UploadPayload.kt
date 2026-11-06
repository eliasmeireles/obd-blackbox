package io.github.eliasmeireles.obdblackbox.data.upload

import io.github.eliasmeireles.obdblackbox.data.bridge.DiagnosticResult
import io.github.eliasmeireles.obdblackbox.data.recording.SessionInfo
import io.github.eliasmeireles.obdblackbox.data.recording.TelemetryRow
import io.github.eliasmeireles.obdblackbox.domain.Signal
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** JSON bodies of the monitorapi contract (docs/backend-spec.md). */
object UploadPayload {
    const val FUEL_RATE_KEY = "fuel_rate_lph_est"
    const val FUEL_LITERS_KEY = "fuel_liters_est"
    const val FUEL_RANGE_KEY = "fuel_range_km_est"

    fun session(info: SessionInfo, appVersion: String): String = sessionJson(info, appVersion).toString()

    /** WebSocket message with the same fields as the session upsert, plus its id. */
    fun sessionMessage(seq: Long, info: SessionInfo, appVersion: String): String = sessionJson(info, appVersion)
        .put("type", "session")
        .put("seq", seq)
        .put("id", info.id)
        .toString()

    /** WebSocket message carrying already-serialized samples, joined without parsing them again. */
    fun samplesMessage(seq: Long, sessionId: String, samples: List<String>): String =
        samples.joinToString(",", prefix = "{\"type\":\"samples\",\"seq\":$seq,\"session_id\":${JSONObject.quote(sessionId)},\"samples\":[", postfix = "]}")

    fun diagnosticsMessage(seq: Long, sessionId: String, json: String): String =
        "{\"type\":\"diagnostics\",\"seq\":$seq,\"session_id\":${JSONObject.quote(sessionId)},\"diagnostics\":$json}"

    fun commandResult(seq: Long, commandId: String, result: DiagnosticResult): String = JSONObject()
        .put("type", "command_result").put("seq", seq).put("command_id", commandId)
        .put("ok", result.ok).put("message", result.message).toString()

    private fun sessionJson(info: SessionInfo, appVersion: String): JSONObject = JSONObject()
        .put("source", info.kind.fileTag)
        .put("vehicle", info.vehicle)
        .put("started_at", Instant.ofEpochMilli(info.startedAtMs).toString())
        .put("app_version", appVersion)

    fun sample(row: TelemetryRow): String {
        val values = JSONObject()
        Signal.entries.forEach { signal -> row.snapshot[signal]?.takeIf { it.isFinite() }?.let { values.put(signal.csvColumn, it) } }
        row.fuelRateLph?.takeIf { it.isFinite() }?.let { values.put(FUEL_RATE_KEY, it) }
        row.fuelLitersEst?.takeIf { it.isFinite() }?.let { values.put(FUEL_LITERS_KEY, it) }
        row.fuelRangeKmEst?.takeIf { it.isFinite() }?.let { values.put(FUEL_RANGE_KEY, it) }
        val sample = JSONObject()
            .put("t", row.snapshot.timestampMs)
            .put("v", values)
            .put("alerts", JSONArray(row.alerts.map { it.id.name }))
        row.event?.let { sample.put("event", JSONObject().put("label", it.label).put("automatic", it.automatic)) }
        return sample.toString()
    }

    /** One app log entry; [id] makes resends idempotent on the server. */
    fun logEntry(id: String, timestampMs: Long, level: String, tag: String, message: String): String = JSONObject()
        .put("id", id)
        .put("t", timestampMs)
        .put("level", level)
        .put("tag", tag)
        .put("message", message)
        .toString()

    fun logs(entries: List<String>): String = entries.joinToString(",", prefix = "{\"logs\":[", postfix = "]}")

    fun logsMessage(seq: Long, entries: List<String>): String =
        entries.joinToString(",", prefix = "{\"type\":\"logs\",\"seq\":$seq,\"logs\":[", postfix = "]}")

    /** Joins already-serialized samples without parsing them again. */
    fun batch(samples: List<String>): String = samples.joinToString(",", prefix = "{\"samples\":[", postfix = "]}")
}
