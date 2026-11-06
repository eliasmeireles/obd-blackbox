package io.github.eliasmeireles.obdblackbox.data.sim

import io.github.eliasmeireles.obdblackbox.data.SourceKind
import io.github.eliasmeireles.obdblackbox.data.SourceStatus
import io.github.eliasmeireles.obdblackbox.data.StallSimulator
import io.github.eliasmeireles.obdblackbox.data.VehicleDataSource
import io.github.eliasmeireles.obdblackbox.domain.VehicleSnapshot
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

class SimulatedSource(
    private val engine: SimulatedEngine = SimulatedEngine(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val periodMs: Long = DEFAULT_PERIOD_MS,
) : VehicleDataSource, StallSimulator {

    override val kind = SourceKind.SIMULATION
    override val status: StateFlow<SourceStatus> = MutableStateFlow<SourceStatus>(SourceStatus.Simulated).asStateFlow()

    override fun snapshots(): Flow<VehicleSnapshot> = flow {
        while (currentCoroutineContext().isActive) {
            emit(engine.step(periodMs / 1_000.0, clock()))
            delay(periodMs)
        }
    }

    override fun simulateStall() = engine.requestStall()

    companion object {
        const val DEFAULT_PERIOD_MS = 200L
    }
}
