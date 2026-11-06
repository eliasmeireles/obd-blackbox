package io.github.eliasmeireles.obdblackbox.data.elm

import io.github.eliasmeireles.obdblackbox.domain.obd.Pid
import org.json.JSONArray
import org.json.JSONObject

/** One readiness monitor (OBD-II mode 01 PID 01): whether the car has it and whether its self-test finished. */
data class Monitor(val name: String, val available: Boolean, val complete: Boolean)

/** Freeze frame (mode 02, frame 0): the code that stored it and the values at that moment. */
data class FreezeFrame(val dtc: String?, val values: Map<String, Double>)

/**
 * Everything the engine ECU tells about its state, beyond the live values: check-engine lamp, codes, freeze
 * frame, self-tests, identification. Serialised as the server diagnostics snapshot (docs/backend-spec.md).
 */
data class DiagnosticSnapshot(
    val t: Long,
    val reason: String,
    val adapter: String?,
    val protocol: String?,
    val ecuId: String?,
    val vin: String?,
    val calibrationId: String?,
    val ecuName: String?,
    val milOn: Boolean?,
    val dtcCount: Int?,
    val monitors: List<Monitor>,
    val fuelSystem: String?,
    val supportedPids: List<Int>,
    val values: Map<String, Double>,
    val stored: List<String>?,
    val pending: List<String>?,
    val permanent: List<String>?,
    val freezeFrame: FreezeFrame?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("t", t)
        .put("reason", reason)
        .putOpt("adapter", adapter)
        .putOpt("protocol", protocol)
        .putOpt("ecu_id", ecuId)
        .putOpt("vin", vin)
        .putOpt("calibration_id", calibrationId)
        .putOpt("ecu_name", ecuName)
        .putOpt("mil_on", milOn)
        .putOpt("dtc_count", dtcCount)
        .put("monitors", JSONArray(monitors.map { JSONObject().put("name", it.name).put("available", it.available).put("complete", it.complete) }))
        .putOpt("fuel_system", fuelSystem)
        .put("supported_pids", JSONArray(supportedPids.sorted().map { "%02X".format(it) }))
        .put("values", JSONObject(values))
        .put(
            "dtcs",
            JSONObject()
                .put("stored", JSONArray(stored.orEmpty()))
                .put("pending", JSONArray(pending.orEmpty()))
                .put("permanent", JSONArray(permanent.orEmpty())),
        )
        .putOpt("freeze_frame", freezeFrame?.let { JSONObject().putOpt("dtc", it.dtc).put("values", JSONObject(it.values)) })
}

/** Reads a [DiagnosticSnapshot] through [Elm327]. Only reads: nothing here clears or changes the ECU. */
object ElmDiagnostics {
    /** Mode 01 PIDs that describe the car's state rather than the engine's moment, with their snapshot names. */
    private val EXTRA: Map<Int, Pair<String, (IntArray) -> Double>> = mapOf(
        0x1C to ("obd_standard" to { d -> d[0].toDouble() }),
        0x1F to ("run_time_s" to { d -> word(d) }),
        0x21 to ("distance_mil_km" to { d -> word(d) }),
        0x30 to ("warmups_since_clear" to { d -> d[0].toDouble() }),
        0x31 to ("distance_since_clear_km" to { d -> word(d) }),
        0x33 to ("baro_kpa" to { d -> d[0].toDouble() }),
        0x15 to ("o2_b1s2_v" to { d -> d[0] / 200.0 }),
        0x45 to ("relative_throttle_pct" to { d -> d[0] * 100.0 / 255 }),
        0x47 to ("throttle_b_pct" to { d -> d[0] * 100.0 / 255 }),
        0x4A to ("pedal_e_pct" to { d -> d[0] * 100.0 / 255 }),
    )
    private val EXTRA_LENGTH = mapOf(0x1C to 1, 0x1F to 2, 0x21 to 2, 0x30 to 1, 0x31 to 2, 0x33 to 1, 0x15 to 2, 0x45 to 1, 0x47 to 1, 0x4A to 1)

    private val SPARK_MONITORS = listOf("catalyst", "heated_catalyst", "evap", "secondary_air", "ac_refrigerant", "o2_sensor", "o2_heater", "egr")
    private val FUEL_SYSTEM = mapOf(1 to "open_loop_cold", 2 to "closed_loop", 4 to "open_loop_load", 8 to "open_loop_fault", 16 to "closed_loop_fault")

    fun read(elm: Elm327, supported: Set<Int>, adapter: String?, reason: String, clock: () -> Long): DiagnosticSnapshot {
        val status = if (0x01 in supported) elm.readPid(0x01, 4) else null
        val fuel = if (0x03 in supported) elm.readPid(0x03, 2) else null
        val values = buildMap {
            for ((pid, entry) in EXTRA) {
                if (pid !in supported) continue
                val data = elm.readPid(pid, EXTRA_LENGTH.getValue(pid)) ?: continue
                put(entry.first, entry.second(data))
            }
        }
        val frameTrigger = elm.readFreezeFrameTrigger()
        val frame = frameTrigger?.let { dtc ->
            val frameValues = buildMap {
                for (pid in Pid.entries) {
                    if (pid.code !in supported) continue
                    val data = elm.readFreezeFrame(pid.code, pid.dataBytes) ?: continue
                    put(pid.signal.csvColumn, pid.decode(data))
                }
            }
            FreezeFrame(dtc, frameValues)
        }
        return DiagnosticSnapshot(
            t = clock(),
            reason = reason,
            adapter = adapter,
            protocol = elm.protocol,
            ecuId = elm.engineEcu,
            vin = elm.readVehicleInfo(0x02),
            calibrationId = elm.readVehicleInfo(0x04),
            ecuName = elm.readVehicleInfo(0x0A),
            milOn = status?.let { it[0] and 0x80 != 0 },
            dtcCount = status?.let { it[0] and 0x7F },
            monitors = status?.let(::monitors).orEmpty(),
            fuelSystem = fuel?.let { FUEL_SYSTEM[it[0]] },
            supportedPids = supported.toList(),
            values = values,
            stored = elm.readDtcs(3),
            pending = elm.readDtcs(7),
            permanent = elm.readDtcs(0x0A),
            freezeFrame = frame,
        )
    }

    /** PID 01 bytes B–D: availability and incompleteness bits of each self-test (SAE J1979). */
    fun monitors(status: IntArray): List<Monitor> {
        if (status.size < 4) return emptyList()
        val (b, c, d) = Triple(status[1], status[2], status[3])
        val common = listOf("misfire", "fuel_system", "components").mapIndexed { bit, name ->
            Monitor(name, available = b and (1 shl bit) != 0, complete = b and (1 shl (bit + 4)) == 0)
        }
        val compression = b and 0x08 != 0
        if (compression) return common
        val spark = SPARK_MONITORS.mapIndexed { bit, name -> Monitor(name, available = c and (1 shl bit) != 0, complete = d and (1 shl bit) == 0) }
        return common + spark
    }

    private fun word(data: IntArray) = (data[0] shl 8 or data[1]).toDouble()
}
