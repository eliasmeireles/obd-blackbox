package io.github.eliasmeireles.obdblackbox.session

import io.github.eliasmeireles.obdblackbox.data.SourceKind
import io.github.eliasmeireles.obdblackbox.data.SourceStatus
import io.github.eliasmeireles.obdblackbox.data.VehicleDataSource
import io.github.eliasmeireles.obdblackbox.data.recording.RecorderFactory
import io.github.eliasmeireles.obdblackbox.data.recording.SessionRecorder
import io.github.eliasmeireles.obdblackbox.data.recording.TelemetryRow
import io.github.eliasmeireles.obdblackbox.domain.Signal
import io.github.eliasmeireles.obdblackbox.domain.VehicleSnapshot
import io.github.eliasmeireles.obdblackbox.domain.events.RecordedEvent
import io.github.eliasmeireles.obdblackbox.domain.snapshot
import io.github.eliasmeireles.obdblackbox.domain.fuel.FuelHistoryStore
import io.github.eliasmeireles.obdblackbox.domain.fuel.FuelUsage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MonitorSessionTest : io.github.eliasmeireles.obdblackbox.ResourceTest() {
    @Test fun `stop flushes the latest learned sample before another session loads it`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var saved = emptyList<FuelUsage>()
        var saves = 0
        val store = object : FuelHistoryStore {
            override fun load() = saved
            override fun save(usage: List<FuelUsage>) { saved = usage; saves++ }
        }
        val source = FakeSource(SourceKind.OBD)
        val session = MonitorSession(source, RecorderFactory { MemoryRecorder() }, TestScope(dispatcher),
            ioDispatcher = dispatcher, fuelHistoryStore = store)
        session.start()
        for (at in listOf(0L, 1000L, 2000L)) {
            source.channel.send(snapshot(Signal.RPM to 2000.0, Signal.MAP to 50.0, Signal.SPEED to 60.0, at = at))
        }
        advanceUntilIdle()
        assertEquals(60.0 / 3600.0, saved.sumOf { it.distanceKm }, 0.0001)
        session.stop()
        assertEquals(120.0 / 3600.0, saved.sumOf { it.distanceKm }, 0.0001)
        assertEquals(2, saves)
        advanceUntilIdle()
        assertEquals(2, saves)
    }

    @Test fun `key off is recorded automatically without a stall alert`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = FakeSource()
        val recorder = MemoryRecorder()
        val events = mutableListOf<RecordedEvent>()
        val session = MonitorSession(source, RecorderFactory { recorder }, TestScope(dispatcher), ioDispatcher = dispatcher, onEvent = { events += it })
        session.start()
        listOf(
            Triple(892.0, 13.62, 16.1), Triple(901.0, 13.62, 16.1),
            Triple(918.0, 12.62, 11.0), Triple(0.0, 12.62, 17.3),
        ).zip(listOf(0L, 600L, 1200L, 1650L)).forEach { (values, at) ->
            source.channel.send(snapshot(Signal.RPM to values.first, Signal.MODULE_VOLTAGE to values.second,
                Signal.THROTTLE to values.third, at = at))
        }
        advanceUntilIdle()
        assertEquals("desligado_pela_chave_1", recorder.rows.last().event?.label)
        assertEquals(true, recorder.rows.last().event?.automatic)
        assertEquals(emptyList<Any>(), recorder.rows.last().alerts)
        assertEquals(listOf(recorder.rows.last().event), events)
        session.stop()
    }

    @Test fun `real samples use persisted range while simulation never learns from real history`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = object : FuelHistoryStore {
            override fun load() = listOf(FuelUsage(20.0, 2.0))
            override fun save(usage: List<FuelUsage>) = Unit
        }
        for ((kind, expected) in listOf(SourceKind.OBD to 250.0, SourceKind.SIMULATION to null)) {
            val source = FakeSource(kind)
            val recorder = MemoryRecorder()
            val session = MonitorSession(source, RecorderFactory { recorder }, TestScope(dispatcher),
                ioDispatcher = dispatcher, fuelHistoryStore = store)
            session.start()
            source.channel.send(snapshot(Signal.FUEL_LEVEL to 50.0, Signal.RPM to 850.0, at = 0))
            source.channel.send(snapshot(at = 1000))
            advanceUntilIdle()
            assertEquals(expected, session.state.value.fuelRange.rangeKm)
            recorder.rows.forEach {
                assertEquals(25.0, it.fuelLitersEst!!, 0.001)
                assertEquals(expected, it.fuelRangeKmEst)
            }
            session.stop()
        }
    }

    @Test fun `a mark with no readings flowing protects the video at once`() = runTest {
        val events = mutableListOf<RecordedEvent>()
        val session = MonitorSession(FakeSource(SourceKind.OBD), RecorderFactory { MemoryRecorder() }, TestScope(StandardTestDispatcher(testScheduler)),
            clock = { 10_000L }, onEvent = { events += it })
        session.markEvent()
        assertEquals(listOf(RecordedEvent("marca_1", 10_000L, automatic = false)), events)
        assertEquals(1, session.state.value.eventCount)
    }

    @Test fun `the remembered average shows before the first reading`() = runTest {
        val store = object : FuelHistoryStore {
            override fun load() = listOf(FuelUsage(4.55, 1.126))
            override fun save(usage: List<FuelUsage>) = Unit
        }
        val session = MonitorSession(FakeSource(SourceKind.OBD), RecorderFactory { MemoryRecorder() }, TestScope(StandardTestDispatcher(testScheduler)),
            fuelHistoryStore = store)
        assertEquals(4.55 / 1.126, session.state.value.fuelRange.averageKmPerLiter!!, 0.001)
    }

    private class FakeSource(override val kind: SourceKind = SourceKind.SIMULATION) : VehicleDataSource {
        val channel = Channel<VehicleSnapshot>(Channel.UNLIMITED)
        override val status = MutableStateFlow<SourceStatus>(SourceStatus.Simulated)
        override fun snapshots(): Flow<VehicleSnapshot> = channel.receiveAsFlow()
    }

    private class MemoryRecorder : SessionRecorder {
        val rows = mutableListOf<TelemetryRow>()
        override val name = "memory.csv"
        override fun record(row: TelemetryRow) { rows += row }
        override fun close() = Unit
    }

    private fun running(at: Long) = snapshot(Signal.RPM to 800.0, Signal.SPEED to 0.0, Signal.MAP to 32.0, at = at)
    private fun dead(at: Long) = snapshot(Signal.RPM to 0.0, Signal.SPEED to 0.0, Signal.MAP to 96.0, at = at)

    @Test fun `records samples, manual marks and automatic stall events`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = FakeSource()
        val recorder = MemoryRecorder()
        val events = mutableListOf<RecordedEvent>()
        val session = MonitorSession(source, RecorderFactory { recorder }, TestScope(dispatcher), ioDispatcher = dispatcher, onEvent = { events += it })

        session.start()
        source.channel.send(running(0))
        advanceUntilIdle()
        session.markEvent()
        source.channel.send(running(200))
        source.channel.send(dead(400))
        advanceUntilIdle()

        assertEquals(listOf(null, "marca_1", "desligou_sozinho_2"), recorder.rows.map { it.event?.label })
        assertEquals(recorder.rows.mapNotNull { it.event }, events)
        val state = session.state.value
        assertEquals(2, state.eventCount)
        assertEquals("memory.csv", state.recordingName)
        assertEquals(true, state.lastEvent?.automatic)
        session.stop()
    }

    @Test fun `profile reaches the recording identity fuel estimate and tank calculation`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val profile = io.github.eliasmeireles.obdblackbox.domain.vehicle.VehicleProfile("Example car", 80.0, 3.2)
        val source = FakeSource()
        val sample = snapshot(Signal.RPM to 800.0, Signal.MAP to 32.0, Signal.FUEL_LEVEL to 50.0)
        val session = MonitorSession(source, RecorderFactory { MemoryRecorder() }, TestScope(dispatcher),
            vehicle = profile, ioDispatcher = dispatcher)
        session.start()
        source.channel.send(sample)
        advanceUntilIdle()
        assertEquals("Example car", session.recordingSession().vehicle)
        assertEquals(profile.fuelEstimator().litersPerHour(sample), session.state.value.fuelRateLph)
        assertEquals(40.0, session.state.value.fuelRange.litersLeft!!, 0.0)
        session.stop()
    }

    @Test fun `state starts empty`() {
        val session = MonitorSession(FakeSource(), RecorderFactory { MemoryRecorder() }, TestScope())
        assertNull(session.state.value.snapshot)
    }

    @Test fun `nothing is recorded until the first sample arrives`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var created = 0
        val session = MonitorSession(FakeSource(), RecorderFactory { created++; MemoryRecorder() }, TestScope(dispatcher), ioDispatcher = dispatcher)
        session.start()
        advanceUntilIdle()
        assertEquals(0, created)
        assertNull(session.state.value.recordingName)
        session.stop()
    }

    @Test fun `a bridge that turns out to be simulated opens a separate simulation session`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = FakeSource(SourceKind.OBD).apply { status.value = SourceStatus.Connected("1.0", simulatedCar = false) }
        val kinds = mutableListOf<SourceKind>()
        val session = MonitorSession(source, RecorderFactory { kinds += it.kind; MemoryRecorder() }, TestScope(dispatcher), ioDispatcher = dispatcher)
        session.start()
        source.channel.send(running(0))
        advanceUntilIdle()
        source.status.value = SourceStatus.Connected("fake", simulatedCar = true)
        source.channel.send(running(200))
        source.channel.send(running(400))
        advanceUntilIdle()
        assertEquals(listOf(SourceKind.OBD, SourceKind.SIMULATION), kinds)
        assertEquals(SourceKind.SIMULATION, session.state.value.sourceKind)
        session.stop()
    }

    @Test fun `a diagnostic before samples opens the same recording session used by later samples`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = FakeSource()
        val created = mutableListOf<String>()
        val recorder = MemoryRecorder()
        val session = MonitorSession(source, RecorderFactory { created += it.id; recorder }, TestScope(dispatcher), ioDispatcher = dispatcher)
        session.start()
        val diagnosticSession = session.recordingSession()
        source.channel.send(running(200))
        advanceUntilIdle()
        assertEquals(listOf(diagnosticSession.id), created)
        assertEquals(diagnosticSession.id, session.recordingSession().id)
        assertEquals(1, recorder.rows.size)
        session.stop()
    }
}
