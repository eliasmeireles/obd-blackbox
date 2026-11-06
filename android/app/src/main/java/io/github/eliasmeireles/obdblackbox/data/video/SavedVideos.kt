package io.github.eliasmeireles.obdblackbox.data.video

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.util.ArrayList

/** A protected black-box clip saved in the gallery album "OBD Black Box". */
data class SavedVideo(val uri: Uri, val name: String, val takenAtMs: Long, val durationMs: Long, val sizeBytes: Long) {
    val reason: String get() = VideoNames.reason(name)

    /** When the clip starts (from its name; the gallery's own date can be the end of the recording). */
    val startedAtMs: Long get() = VideoNames.startedAt(name) ?: takenAtMs
}

/** Reads the clip names written by [VideoGallery]: `obd-YYYYMMDD-HHMMSS-<label>.mp4`. */
object VideoNames {
    private val START = Regex("""^obd-(\d{8})-(\d{6})""")

    /** Start time encoded in the name (phone time zone), or null. */
    fun startedAt(name: String, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Long? {
        val match = START.find(name) ?: return null
        val format = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US).apply { timeZone = zone; isLenient = false }
        return runCatching { format.parse(match.groupValues[1] + match.groupValues[2])?.time }.getOrNull()
    }

    /** What made the app keep the clip, in the app language. File names keep the stable event tags (marca_1, desligou_sozinho). */
    fun reason(name: String): String {
        val label = name.removeSuffix(".mp4").split('-', limit = 4).getOrNull(3).orEmpty()
        return when {
            label.startsWith("marca_") -> text(R.string.msg_marcacao_value, (label.removePrefix("marca_")).toString())
            label.startsWith("desligou_sozinho") -> text(R.string.msg_motor_apagou_sozinho)
            label.startsWith("desligado_pela_chave") -> text(R.string.engine_key_off)
            label == "salvo" -> text(R.string.saved_manually)
            label.isBlank() -> text(R.string.msg_gravacao)
            else -> label.replace('_', ' ')
        }
    }
}

/**
 * Lists, shares and deletes the clips in the album. They were created by this app through MediaStore, so the app
 * sees and deletes them without storage permissions. Sharing goes through the Android share sheet (Google Drive,
 * WhatsApp, e-mail…): nothing is uploaded by the app itself.
 */
class SavedVideos(private val context: Context) {
    fun list(): List<SavedVideo> {
        val columns = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE,
        )
        val (selection, args) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?" to arrayOf("Movies/${text(R.string.app_name)}%")
        } else {
            @Suppress("DEPRECATION")
            "${MediaStore.Video.Media.DATA} LIKE ?" to arrayOf("%/Movies/${text(R.string.app_name)}/%")
        }
        val order = "${MediaStore.Video.Media.DATE_TAKEN} DESC"
        return runCatching {
            context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, columns, selection, args, order)?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        add(
                            SavedVideo(
                                uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                                name = c.getString(1).orEmpty(),
                                takenAtMs = c.getLong(2),
                                durationMs = c.getLong(3),
                                sizeBytes = c.getLong(4),
                            ),
                        )
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    fun delete(video: SavedVideo): Boolean = runCatching { context.contentResolver.delete(video.uri, null, null) > 0 }.getOrDefault(false)

    fun viewIntent(video: SavedVideo): Intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(video.uri, "video/mp4")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Share sheet for one or several clips. */
    fun shareIntent(videos: List<SavedVideo>): Intent {
        val send = if (videos.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, videos.first().uri)
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(videos.map { it.uri }))
        }
        send.setType("video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(Intent.EXTRA_SUBJECT, text(R.string.msg_obd_black_box_gravacao))
        return Intent.createChooser(send, text(R.string.msg_compartilhar_gravacao)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
