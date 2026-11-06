package io.github.eliasmeireles.obdblackbox.data.bridge

import io.github.eliasmeireles.obdblackbox.data.elm.ReceivedDiagnostic
import kotlinx.coroutines.flow.StateFlow

data class DiagnosticResult(val ok: Boolean, val message: String)

interface DiagnosticsReader {
    val diagnostics: StateFlow<ReceivedDiagnostic?>
    suspend fun requestDiagnostics(): DiagnosticResult
    suspend fun clearDtcs(): DiagnosticResult
}
