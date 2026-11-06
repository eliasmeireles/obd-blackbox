package io.github.eliasmeireles.obdblackbox.data

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import io.github.eliasmeireles.obdblackbox.domain.VehicleSnapshot
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/** No bridge configured and simulation off: nothing is read, recorded or uploaded. */
class IdleSource(reason: String = text(R.string.msg_ponte_ainda_nao_configurada)) : VehicleDataSource {
    override val kind = SourceKind.OBD
    override val status: StateFlow<SourceStatus> = MutableStateFlow<SourceStatus>(SourceStatus.Disconnected(reason)).asStateFlow()

    override fun snapshots(): Flow<VehicleSnapshot> = flow { awaitCancellation() }
}

/** Data counts as simulation when it comes from the simulator or from a bridge that says its car is simulated. */
fun VehicleDataSource.currentKind(): SourceKind {
    val simulatedBridge = (status.value as? SourceStatus.Connected)?.simulatedCar == true
    return if (kind == SourceKind.SIMULATION || simulatedBridge) SourceKind.SIMULATION else SourceKind.OBD
}
