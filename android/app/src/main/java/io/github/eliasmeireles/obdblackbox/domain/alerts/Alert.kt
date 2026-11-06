package io.github.eliasmeireles.obdblackbox.domain.alerts

enum class Severity { WARNING, CRITICAL }

enum class AlertId { LOW_VOLTAGE, OVERHEAT, IDLE_DROP, NO_THROTTLE_RESPONSE, LEAN_MIXTURE, RICH_MIXTURE }

data class Alert(val id: AlertId, val severity: Severity, val message: String)

data class AlertThresholds(
    val minRunningVoltage: Double = 13.0,
    val maxCoolantC: Double = 105.0,
    val minIdleRpm: Double = 600.0,
    val maxFuelTrimPct: Double = 20.0,
    val pressedPedalPct: Double = 15.0,
    val stoppedSpeedKmh: Double = 3.0,
)
