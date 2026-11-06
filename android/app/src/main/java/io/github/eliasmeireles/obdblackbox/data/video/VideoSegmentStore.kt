package io.github.eliasmeireles.obdblackbox.data.video

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import android.content.Context
import android.os.Environment
import android.util.AtomicFile
import io.github.eliasmeireles.obdblackbox.domain.video.BlackBoxPolicy
import io.github.eliasmeireles.obdblackbox.domain.video.VideoSegment
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Each sidecar is committed atomically before cleanup or gallery export can touch its video. */
class VideoSegmentStore(context: Context) {
    private val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir, "blackbox")
    private val segments = linkedMapOf<String, VideoSegment>()
    private val exported = mutableSetOf<String>()

    fun load() {
        check(directory.isDirectory || directory.mkdirs()) { text(R.string.msg_nao_foi_possivel_criar_a_pasta_de_videos) }
        directory.listFiles { file -> file.extension == "json" }?.forEach { metadata ->
            val json = JSONObject(AtomicFile(metadata).readFully().toString(Charsets.UTF_8))
            val labels = json.getJSONArray("labels")
            val segment = VideoSegment(json.getString("id"), json.getLong("start"),
                json.optLong("end").takeIf { json.has("end") }, labels = (0 until labels.length()).map { labels.getString(it) }.toSet())
            if (file(segment).exists()) {
                segments[segment.id] = segment
                if (json.optBoolean("exported")) exported += segment.id
            } else AtomicFile(metadata).delete()
        }
        // A killed process cannot finalize its active MP4. It remains private and is eligible for ring cleanup.
        segments.values.filter { it.endMs == null }.forEach { save(it.copy(endMs = file(it).lastModified().coerceAtLeast(it.startMs))) }
    }

    fun file(segment: VideoSegment): File = File(directory, "${segment.id}.mp4")
    fun all(): List<VideoSegment> = segments.values.map { it.copy(bytes = file(it).length()) }

    fun save(segment: VideoSegment) {
        val json = JSONObject().put("id", segment.id).put("start", segment.startMs)
            .put("labels", JSONArray(segment.labels.toList())).put("exported", segment.id in exported)
        segment.endMs?.let { json.put("end", it) }
        val atomic = AtomicFile(File(directory, "${segment.id}.json"))
        val output = atomic.startWrite()
        try {
            output.write(json.toString().toByteArray())
            atomic.finishWrite(output)
            segments[segment.id] = segment
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    fun applyProtection(policy: BlackBoxPolicy) {
        all().forEach { segment ->
            val protected = policy.applyProtection(segment)
            if (protected.labels != segment.labels) save(protected)
        }
        policy.discardExpiredWindows()
    }

    fun pendingExports(): List<VideoSegment> = all().filter { it.protected && it.endMs != null && it.id !in exported }

    fun exported(segment: VideoSegment) {
        exported += segment.id
        try { save(segment) } catch (error: Exception) {
            exported -= segment.id
            throw error
        }
    }

    fun delete(segment: VideoSegment) {
        check(!segment.protected)
        check(!file(segment).exists() || file(segment).delete()) { text(R.string.msg_nao_foi_possivel_apagar_value, (segment.id).toString()) }
        AtomicFile(File(directory, "${segment.id}.json")).delete()
        segments.remove(segment.id)
        exported.remove(segment.id)
    }
}
