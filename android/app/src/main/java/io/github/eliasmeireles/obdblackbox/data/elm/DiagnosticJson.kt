package io.github.eliasmeireles.obdblackbox.data.elm

import org.json.JSONArray
import org.json.JSONObject

/** Keeps the original object for upload, including fields added by newer bridges. */
data class ReceivedDiagnostic(val json: String, val snapshot: DiagnosticSnapshot) {
    companion object {
        fun parse(raw: String): ReceivedDiagnostic {
            val obj = JSONObject(raw)
            require(obj.getLong("t") > 0)
            val dtcs = obj.optJSONObject("dtcs")
            val frame = obj.optJSONObject("freeze_frame")
            val monitors = obj.optJSONArray("monitors")
            return ReceivedDiagnostic(raw, DiagnosticSnapshot(
                t = obj.getLong("t"), reason = obj.getString("reason"),
                adapter = obj.text("adapter"), protocol = obj.text("protocol"), ecuId = obj.text("ecu_id"),
                vin = obj.text("vin"), calibrationId = obj.text("calibration_id"), ecuName = obj.text("ecu_name"),
                milOn = if (obj.has("mil_on") && !obj.isNull("mil_on")) obj.getBoolean("mil_on") else null,
                dtcCount = if (obj.has("dtc_count") && !obj.isNull("dtc_count")) obj.getInt("dtc_count") else null,
                monitors = List(monitors?.length() ?: 0) { i -> monitors!!.getJSONObject(i).let {
                    Monitor(it.getString("name"), it.getBoolean("available"), it.getBoolean("complete"))
                } },
                fuelSystem = obj.text("fuel_system"),
                supportedPids = obj.optJSONArray("supported_pids").strings().mapNotNull { it.toIntOrNull(16) },
                values = obj.optJSONObject("values").numbers(),
                stored = dtcs?.optJSONArray("stored")?.strings(), pending = dtcs?.optJSONArray("pending")?.strings(),
                permanent = dtcs?.optJSONArray("permanent")?.strings(),
                freezeFrame = frame?.let { FreezeFrame(it.text("dtc"), it.optJSONObject("values").numbers()) },
            ))
        }
    }
}

private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
private fun JSONArray?.strings(): List<String> = List(this?.length() ?: 0) { this!!.getString(it) }
private fun JSONObject?.numbers(): Map<String, Double> = this?.let { obj ->
    obj.keys().asSequence().mapNotNull { key -> obj.optDouble(key).takeIf { it.isFinite() }?.let { key to it } }.toMap()
}.orEmpty()
