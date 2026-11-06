package io.github.eliasmeireles.obdblackbox.data.bridge

import io.github.eliasmeireles.obdblackbox.data.elm.ReceivedDiagnostic

/** Messages sent by the bridge, see docs/bridge-protocol.md (TMB/1). */
sealed interface BridgeMessage {
    data class Hello(val protocol: String, val firmware: String, val mode: String) : BridgeMessage

    /** [data] maps a mode 01 PID code to its data bytes; null when the ECU did not answer (NA). */
    data class Sample(val seq: Long, val uptimeMs: Long, val data: Map<Int, IntArray?>) : BridgeMessage

    data class Diagnostics(val reading: ReceivedDiagnostic) : BridgeMessage

    data class Dtcs(val stored: List<String>, val pending: List<String>) : BridgeMessage

    data class Status(val bus: String, val rx: Long, val tx: Long, val errors: Long) : BridgeMessage

    data class Ok(val command: String) : BridgeMessage

    data class Err(val command: String, val reason: String) : BridgeMessage

    data object Pong : BridgeMessage

    data class Event(val name: String, val detail: String) : BridgeMessage
}
