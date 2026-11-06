package io.github.eliasmeireles.obdblackbox.session

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.domain.vehicle.VehicleProfile
import io.github.eliasmeireles.obdblackbox.data.SourceKind
import io.github.eliasmeireles.obdblackbox.data.VehicleDataSource
import io.github.eliasmeireles.obdblackbox.data.currentKind
import io.github.eliasmeireles.obdblackbox.data.recording.RecorderFactory
import io.github.eliasmeireles.obdblackbox.data.recording.SessionInfo
import io.github.eliasmeireles.obdblackbox.data.recording.SessionRecorder
import io.github.eliasmeireles.obdblackbox.data.recording.TelemetryRow
import io.github.eliasmeireles.obdblackbox.domain.Signal
import io.github.eliasmeireles.obdblackbox.domain.VehicleSnapshot
import io.github.eliasmeireles.obdblackbox.domain.alerts.AlertRules
import io.github.eliasmeireles.obdblackbox.domain.events.RecordedEvent
import io.github.eliasmeireles.obdblackbox.domain.events.StallDetector
import io.github.eliasmeireles.obdblackbox.domain.fuel.FuelEstimator
import io.github.eliasmeireles.obdblackbox.domain.fuel.FuelHistoryStore
import io.github.eliasmeireles.obdblackbox.domain.fuel.FuelRange
import io.github.eliasmeireles.obdblackbox.domain.fuel.FuelRangeEstimator
import io.github.eliasmeireles.obdblackbox.R
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Reads the source, derives alerts/consumption/events, records every sample and exposes the live state. */
class MonitorSession(
    private val source: VehicleDataSource,
    private val recorders: RecorderFactory,
    private val scope: CoroutineScope,
    private val alertRules: AlertRules = AlertRules(),
    private val vehicle: VehicleProfile = VehicleProfile(),
    private val fuel: FuelEstimator = vehicle.fuelEstimator(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    fuelHistoryStore: FuelHistoryStore = FuelHistoryStore.NONE,
    private val onEvent: (RecordedEvent) -> Unit = {},
) {
    private val realFuelRange = FuelRangeEstimator(fuelHistoryStore, vehicle.tankCapacityL)
    private val simulatedFuelRange = FuelRangeEstimator(tankCapacityL = vehicle.tankCapacityL)

    /** Starts with the remembered average, so the range shows before the first reading arrives. */
    private val _state = MutableStateFlow(
        DashboardState(sourceKind = source.kind, fuelRange = if (source.kind == SourceKind.OBD) realFuelRange.current() else FuelRange()),
    )
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    private val pendingMarks = AtomicInteger(0)

    /** When the last reading was processed (app clock), to know whether readings are flowing. */
    @Volatile private var lastReadingAt: Long? = null
    private val stallDetector = StallDetector()
    private var job: Job? = null
    private var recorder: SessionRecorder? = null
    private var recorderKind: SourceKind? = null
    private var recording: SessionInfo? = null

    @Synchronized
    fun recordingSession(): SessionInfo {
        val kind = source.currentKind()
        if (recorder == null || recorderKind != kind) {
            recorder?.close()
            recording = SessionInfo(newId(), kind, clock(), vehicle.name.ifBlank { text(R.string.my_car) })
            recorder = recorders.create(recording!!).also { opened ->
                _state.update { it.copy(recordingName = opened.name, sourceKind = kind) }
            }
            recorderKind = kind
        }
        return recording!!
    }

    @Synchronized
    private fun closeRecording() {
        recorder?.close()
        recorder = null
        recording = null
        recorderKind = null
    }

    /**
     * Collects the source. A recording session (CSV + upload) opens at the first sample or diagnostic, and a new
     * one is opened whenever the data kind changes, so simulated and real samples never share a session.
     */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(ioDispatcher) {
            try {
                source.snapshots().collect { snapshot ->
                    recordingSession()
                    process(snapshot, recorder!!)
                }
            } finally {
                realFuelRange.flush()
                closeRecording()
            }
        }
    }

    /** The mark is written on the next sample, so it carries the values of that moment. */
    /**
     * Marks an event. While readings flow it is written with the next sample (~0.25 s later); when no reading arrived
     * recently (car off, adapter lost right at a failure) it is announced at once, so the black box still protects
     * the video around the tap.
     */
    fun markEvent() {
        val now = clock()
        val lastReading = lastReadingAt
        if (lastReading != null && now - lastReading <= LIVE_READING_MS) {
            pendingMarks.incrementAndGet()
            return
        }
        val event = RecordedEvent("marca_${_state.value.eventCount + 1}", now, automatic = false)
        _state.update { it.copy(eventCount = it.eventCount + 1, lastEvent = event) }
        onEvent(event)
    }

    fun stop() {
        job?.cancel()
        realFuelRange.flush()
        job = null
    }

    private fun process(snapshot: VehicleSnapshot, recorder: SessionRecorder) {
        lastReadingAt = clock()
        val lph = fuel.litersPerHour(snapshot)
        val range = (if (source.currentKind() == SourceKind.OBD) realFuelRange else simulatedFuelRange).advance(snapshot, lph)
        val alerts = alertRules.evaluate(snapshot)
        val event = nextEvent(snapshot)
        recorder.record(TelemetryRow(snapshot, lph, alerts, event, range.litersLeft, range.rangeKm))
        event?.let(onEvent)
        _state.update { current ->
            current.copy(
                snapshot = snapshot,
                fuelRateLph = lph,
                fuelRange = range,
                instantKmPerLiter = fuel.kmPerLiter(snapshot[Signal.SPEED], lph),
                trip = current.trip.advance(snapshot.timestampMs, snapshot[Signal.SPEED], lph),
                alerts = alerts,
                eventCount = current.eventCount + if (event != null) 1 else 0,
                lastEvent = event ?: current.lastEvent,
            )
        }
    }

    private fun nextEvent(snapshot: VehicleSnapshot): RecordedEvent? {
        val stopped = stallDetector.onSnapshot(snapshot)
        val manual = pendingMarks.getAndSet(0) > 0
        val number = _state.value.eventCount + 1
        return when {
            stopped != null -> RecordedEvent("${stopped.eventLabel}_$number", snapshot.timestampMs, automatic = true)
            manual -> RecordedEvent("marca_$number", snapshot.timestampMs, automatic = false)
            else -> null
        }
    }

    private companion object {
        /** A reading this recent means samples are flowing and a mark can wait for the next one. */
        const val LIVE_READING_MS = 3_000L
    }
}
