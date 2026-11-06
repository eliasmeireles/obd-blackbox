package io.github.eliasmeireles.obdblackbox.data.video

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.AtomicFile
import androidx.core.content.FileProvider
import io.github.eliasmeireles.obdblackbox.domain.video.VideoSegment
import java.io.File
import org.json.JSONObject

/** A finished black-box segment still in the private ring buffer (kept ~2 h, then replaced). */
data class RecentRecording(val id: String, val file: File, val startMs: Long, val endMs: Long, val sizeBytes: Long, val labels: Set<String>) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0)

    /** The name it gets (or already has) in the gallery: event label, or "salvo" when saved by hand. */
    val galleryName: String get() = galleryName(startMs, labels.firstOrNull() ?: "salvo")
}

/**
 * The black-box ring buffer as seen by the Vídeos screen: every finished segment, with or without an event, so
 * nothing recorded is hidden. A segment can be watched, shared, copied to the gallery (where it stays after the ring
 * buffer replaces it) or deleted. The recording service keeps writing the segment in progress, which is not listed.
 */
class RecentRecordings(private val context: Context) {
    private val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir, "blackbox")
    private val authority = "${context.packageName}.files"

    fun list(): List<RecentRecording> = directory.listFiles { file -> file.extension == "json" }.orEmpty().mapNotNull { metadata ->
        runCatching {
            val json = JSONObject(AtomicFile(metadata).readFully().toString(Charsets.UTF_8))
            if (!json.has("end")) return@runCatching null
            val video = File(directory, "${json.getString("id")}.mp4")
            if (!video.isFile || video.length() == 0L) return@runCatching null
            val labels = json.getJSONArray("labels").let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
            RecentRecording(json.getString("id"), video, json.getLong("start"), json.getLong("end"), video.length(), labels)
        }.getOrNull()
    }.sortedByDescending { it.startMs }

    fun viewIntent(recording: RecentRecording): Intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri(recording), "video/mp4")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    fun shareIntent(recording: RecentRecording): Intent {
        val send = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri(recording))
            .putExtra(Intent.EXTRA_SUBJECT, text(R.string.msg_obd_black_box_gravacao)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, text(R.string.msg_compartilhar_gravacao)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Copies the segment into the gallery album, labelled with its event or "salvo". */
    fun saveToGallery(recording: RecentRecording): Uri {
        val label = recording.labels.firstOrNull() ?: "salvo"
        return VideoGallery(context).save(VideoSegment(recording.id, recording.startMs, recording.endMs, labels = setOf(label)), recording.file)
    }

    /**
     * Deletes a finished segment (video and sidecar). Only finished ones are listed, so the segment being recorded
     * is never touched; the recording service tolerates files that disappear.
     */
    fun delete(recording: RecentRecording): Boolean {
        val video = recording.file.delete() || !recording.file.exists()
        File(directory, "${recording.id}.json").delete()
        return video
    }

    private fun uri(recording: RecentRecording): Uri = FileProvider.getUriForFile(context, authority, recording.file)
}
