package io.github.eliasmeireles.obdblackbox.data.video

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.github.eliasmeireles.obdblackbox.domain.video.VideoSegment
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val videoDate = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())
private val unsafeLabel = Regex("[^a-zA-Z0-9_-]")

/** Gallery file name of a segment: `obd-YYYYMMDD-HHMMSS-<label>.mp4` (phone time zone). */
fun galleryName(startMs: Long, label: String): String =
    "obd-${videoDate.format(Instant.ofEpochMilli(startMs))}-${label.replace(unsafeLabel, "_")}.mp4"

class VideoGallery(private val context: Context) {
    fun save(segment: VideoSegment, file: File): Uri {
        // Reject unfinished/corrupt MP4s after process death instead of publishing an unplayable clip.
        val metadata = MediaMetadataRetriever()
        try {
            metadata.setDataSource(file.absolutePath)
            check((metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) > 0)
        } finally { metadata.release() }
        val name = galleryName(segment.startMs, segment.labels.first())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_TAKEN, segment.startMs)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/${text(R.string.app_name)}")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            } else {
                val album = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), text(R.string.app_name))
                check(album.isDirectory || album.mkdirs())
                put(MediaStore.Video.Media.DATA, File(album, name).absolutePath)
            }
        }
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values))
        try {
            checkNotNull(resolver.openOutputStream(uri)).use { output -> file.inputStream().use { it.copyTo(output) } }
            if (Build.VERSION.SDK_INT >= 29) {
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) == 1)
            }
            return uri
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
}
