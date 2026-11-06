package io.github.eliasmeireles.obdblackbox.data.sim

/** Driving loop played by the simulator. STALLING reproduces the fuel-starvation signature. */
internal enum class SimPhase(val durationS: Double) {
    IDLE(20.0),
    ACCELERATE(12.0),
    CRUISE(30.0),
    DECELERATE(14.0),
    STALLING(12.0),
    ENGINE_OFF(6.0);

    fun nextInLoop(): SimPhase = when (this) {
        IDLE -> ACCELERATE
        ACCELERATE -> CRUISE
        CRUISE -> DECELERATE
        DECELERATE -> IDLE
        STALLING -> ENGINE_OFF
        ENGINE_OFF -> IDLE
    }
}

/** Instant targets for one simulated sample. */
internal data class SimTarget(
    val speedKmh: Double,
    val rpm: Double,
    val mapKpa: Double,
    val pedalPct: Double,
    val loadPct: Double,
    val timingDeg: Double,
    val shortTrimPct: Double? = null,
    val o2Volts: Double? = null,
    val voltage: Double? = null,
)
