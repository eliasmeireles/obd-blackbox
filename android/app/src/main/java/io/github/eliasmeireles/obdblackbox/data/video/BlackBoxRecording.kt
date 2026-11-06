package io.github.eliasmeireles.obdblackbox.data.video

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import io.github.eliasmeireles.obdblackbox.data.logs.AppLog
import io.github.eliasmeireles.obdblackbox.data.logs.LogLevel
import io.github.eliasmeireles.obdblackbox.domain.events.RecordedEvent
import io.github.eliasmeireles.obdblackbox.domain.video.BlackBoxPolicy
import io.github.eliasmeireles.obdblackbox.domain.video.CaptureSize
import io.github.eliasmeireles.obdblackbox.domain.video.VideoSegment
import io.github.eliasmeireles.obdblackbox.service.VideoNotifications

/** Serialized on the recording service's worker, including event delivery and file operations. */
class BlackBoxRecording(
    context: Context,
    private val capture: ScreenCapture,
    private val controller: BlackBoxController,
    private val log: AppLog,
    private val notifications: VideoNotifications,
) {
    private val store = VideoSegmentStore(context)
    private val gallery = VideoGallery(context)
    private val policy = BlackBoxPolicy()
    private var active: VideoSegment? = null
    private lateinit var size: CaptureSize
    private var density = 1
    private var startedAt = 0L
    private var running = false
    private var lastExportAttempt = 0L

    fun start(code: Int, consent: Intent, captureSize: CaptureSize, densityDpi: Int) {
        store.load()
        size = captureSize
        density = densityDpi
        capture.consent(code, consent)
        startedAt = SystemClock.elapsedRealtime()
        startSegment()
        running = true
        controller.recording(0)
        log.log(LogLevel.INFO, "video", "recording started: ${size.width}×${size.height}, screen and microphone")
        maintain()
    }

    fun tick() {
        if (!running) return
        if (active?.let(policy::shouldRotate) == true) rotate()
        controller.recording(SystemClock.elapsedRealtime() - startedAt)
        maintain()
    }

    fun resize(width: Int, height: Int) {
        val next = CaptureSize.fromDisplay(width, height)
        if (next == size) return
        size = next
        if (running) rotate()
    }

    fun protect(event: RecordedEvent) {
        if (!running || !policy.protect(event)) return
        store.applyProtection(policy)
        log.log(LogLevel.INFO, "video", "protected: ${event.label}, 2 minutes before and after (${event.timestampMs})")
        exportPending()
    }

    private fun rotate() {
        finishSegment()
        startSegment()
        exportPending()
    }

    private fun startSegment() {
        val now = System.currentTimeMillis()
        val segment = policy.applyProtection(VideoSegment(now.toString(), now))
        store.save(segment)
        active = segment
        capture.start(store.file(segment), size, density)
        log.log(LogLevel.INFO, "video", "segment started: ${segment.id}")
    }

    private fun finishSegment() {
        val segment = active ?: return
        try { capture.finish() } finally {
            val latest = store.all().first { it.id == segment.id }
            store.save(policy.applyProtection(latest.copy(endMs = System.currentTimeMillis())))
            active = null
        }
        log.log(LogLevel.INFO, "video", "segment finished: ${segment.id}")
    }

    private fun maintain() {
        store.applyProtection(policy)
        policy.cleanup(store.all()).forEach {
            store.delete(it)
            log.log(LogLevel.INFO, "video", "segment dropped from history: ${it.id}")
        }
        if (SystemClock.elapsedRealtime() - lastExportAttempt >= 30_000) exportPending()
    }

    private fun exportPending() {
        lastExportAttempt = SystemClock.elapsedRealtime()
        store.pendingExports().forEach { segment ->
            try {
                val uri = gallery.save(segment, store.file(segment))
                store.exported(segment)
                log.log(LogLevel.INFO, "video", "clip saved to gallery: ${segment.id}, ${segment.labels.joinToString()}")
                notifications.saved(uri)
            } catch (error: Exception) {
                log.log(LogLevel.ERROR, "video", "failed to save clip ${segment.id}: ${error.message}")
            }
        }
    }

    fun stop() {
        running = false
        try { finishSegment() } finally { capture.close() }
        exportPending()
    }
}
