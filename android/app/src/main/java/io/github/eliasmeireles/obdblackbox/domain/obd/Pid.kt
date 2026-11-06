package io.github.eliasmeireles.obdblackbox.domain.obd

import io.github.eliasmeireles.obdblackbox.domain.Signal

/** Standard OBD-II mode 01 PIDs (SAE J1979) read by the monitor. */
enum class Pid(
    val code: Int,
    val signal: Signal,
    val dataBytes: Int,
    private val formula: (IntArray) -> Double,
) {
    ENGINE_LOAD(0x04, Signal.ENGINE_LOAD, 1, { percent(it[0]) }),
    COOLANT_TEMP(0x05, Signal.COOLANT_TEMP, 1, { celsius(it[0]) }),
    SHORT_FUEL_TRIM(0x06, Signal.SHORT_FUEL_TRIM, 1, { fuelTrim(it[0]) }),
    LONG_FUEL_TRIM(0x07, Signal.LONG_FUEL_TRIM, 1, { fuelTrim(it[0]) }),
    MAP(0x0B, Signal.MAP, 1, { it[0].toDouble() }),
    RPM(0x0C, Signal.RPM, 2, { word(it) / 4.0 }),
    SPEED(0x0D, Signal.SPEED, 1, { it[0].toDouble() }),
    TIMING_ADVANCE(0x0E, Signal.TIMING_ADVANCE, 1, { it[0] / 2.0 - 64.0 }),
    INTAKE_TEMP(0x0F, Signal.INTAKE_TEMP, 1, { celsius(it[0]) }),
    THROTTLE(0x11, Signal.THROTTLE, 1, { percent(it[0]) }),
    O2_B1S1(0x14, Signal.O2_B1S1, 2, { it[0] / 200.0 }),
    O2_B1S2(0x15, Signal.O2_B1S2, 2, { it[0] / 200.0 }),
    FUEL_LEVEL(0x2F, Signal.FUEL_LEVEL, 1, { percent(it[0]) }),
    MODULE_VOLTAGE(0x42, Signal.MODULE_VOLTAGE, 2, { word(it) / 1000.0 }),
    PEDAL(0x49, Signal.PEDAL, 1, { percent(it[0]) }),
    COMMANDED_THROTTLE(0x4C, Signal.COMMANDED_THROTTLE, 1, { percent(it[0]) });

    fun decode(data: IntArray): Double {
        require(data.size >= dataBytes) { "PID %02X needs $dataBytes bytes, got ${data.size}".format(code) }
        return formula(data)
    }

    companion object {
        private val byCode = entries.associateBy { it.code }

        fun fromCode(code: Int): Pid? = byCode[code]
    }
}

private fun percent(a: Int) = a * 100.0 / 255.0

private fun celsius(a: Int) = a - 40.0

private fun fuelTrim(a: Int) = (a - 128) * 100.0 / 128.0

private fun word(data: IntArray) = (data[0] shl 8 or data[1]).toDouble()
