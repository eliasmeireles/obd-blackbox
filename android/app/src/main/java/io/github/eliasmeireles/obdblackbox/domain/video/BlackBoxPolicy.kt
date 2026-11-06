package io.github.eliasmeireles.obdblackbox.domain.video

import io.github.eliasmeireles.obdblackbox.domain.events.RecordedEvent

data class VideoSegment(
    val id: String,
    val startMs: Long,
    val endMs: Long? = null,
    val bytes: Long = 0,
    val labels: Set<String> = emptySet(),
) {
    val protected: Boolean get() = labels.isNotEmpty()
}

data class ProtectionWindow(val startMs: Long, val endMs: Long, val label: String)

/** Decisions only: callers supply timestamps and sizes, and perform the selected file operations. */
class BlackBoxPolicy(
    private val clock: () -> Long = System::currentTimeMillis,
    val segmentMs: Long = 5 * 60_000L,
    private val historyMs: Long = 2 * 60 * 60_000L,
    private val maxBytes: Long = 2_000_000_000L,
    private val eventRadiusMs: Long = 2 * 60_000L,
) {
    private val windows = mutableListOf<ProtectionWindow>()

    fun shouldRotate(segment: VideoSegment): Boolean = clock() - segment.startMs >= segmentMs

    fun protect(event: RecordedEvent): Boolean {
        if (!protects(event)) return false
        windows += ProtectionWindow(event.timestampMs - eventRadiusMs, event.timestampMs + eventRadiusMs, event.label)
        return true
    }

    fun applyProtection(segment: VideoSegment): VideoSegment {
        val labels = windows.filter { it.startMs <= (segment.endMs ?: clock()) && it.endMs >= segment.startMs }
            .map { it.label }
        return segment.copy(labels = segment.labels + labels)
    }

    /** Call only after applying windows to all existing segments; future segments need only live windows. */
    fun discardExpiredWindows() {
        windows.removeAll { it.endMs < clock() }
    }

    fun cleanup(segments: List<VideoSegment>): List<VideoSegment> {
        val unprotected = segments.filter { !it.protected }.sortedBy { it.startMs }
        var bytes = unprotected.sumOf { it.bytes }
        return unprotected.filter { segment ->
            val delete = segment.endMs != null &&
                (segment.startMs < clock() - historyMs || bytes > maxBytes)
            if (delete) bytes -= segment.bytes
            delete
        }
    }

    companion object {
        fun protects(event: RecordedEvent): Boolean =
            (!event.automatic && event.label.startsWith("marca_")) ||
                (event.automatic && event.label.startsWith("desligou_sozinho_"))
    }
}

data class CaptureSize(val width: Int, val height: Int) {
    companion object {
        private const val MAX_SHORT_SIDE = 720.0
        private const val MAX_LONG_SIDE = 1920.0

        fun fromDisplay(width: Int, height: Int): CaptureSize {
            require(width > 0 && height > 0)
            // Short side at most 720 and long side at most 1920, keeping the aspect: native on a 720×1600 phone in
            // both orientations, instead of squeezing landscape into 720 px of width.
            val scale = minOf(1.0, MAX_SHORT_SIDE / minOf(width, height), MAX_LONG_SIDE / maxOf(width, height))
            fun even(value: Double) = value.toInt().let { it - it % 2 }.coerceAtLeast(2)
            return CaptureSize(even(width * scale), even(height * scale))
        }
    }
}
