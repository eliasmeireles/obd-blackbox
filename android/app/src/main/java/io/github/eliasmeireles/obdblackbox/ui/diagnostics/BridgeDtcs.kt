package io.github.eliasmeireles.obdblackbox.ui.diagnostics

import io.github.eliasmeireles.obdblackbox.data.bridge.DtcReading

/** Trouble-code state and action for the Dados tab. */
data class BridgeDtcs(val reading: DtcReading?, val canRead: Boolean, val onRead: () -> Unit)
