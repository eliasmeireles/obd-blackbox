package io.github.eliasmeireles.obdblackbox.data.video

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import io.github.eliasmeireles.obdblackbox.domain.video.CaptureSize
import java.io.File

/** One consent and one VirtualDisplay for the entire run, including on Android 14+. */
class ScreenCapture(
    private val context: Context,
    private val handler: Handler,
    private val onStopped: () -> Unit,
    private val onResize: (Int, Int) -> Unit,
    private val onError: (String) -> Unit,
) {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() = onStopped()
        override fun onCapturedContentResize(width: Int, height: Int) = onResize(width, height)
    }

    fun consent(resultCode: Int, data: Intent) {
        projection = checkNotNull(context.getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data))
            .also { it.registerCallback(callback, handler) }
    }

    @Suppress("DEPRECATION")
    fun start(file: File, size: CaptureSize, densityDpi: Int) {
        val next = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        recorder = next
        next.setAudioSource(MediaRecorder.AudioSource.MIC)
        next.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        next.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        next.setVideoSize(size.width, size.height)
        next.setVideoFrameRate(20)
        next.setVideoEncodingBitRate(2_000_000)
        next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        next.setAudioChannels(1)
        next.setAudioSamplingRate(44_100)
        next.setAudioEncodingBitRate(64_000)
        next.setOutputFile(file.absolutePath)
        next.setOnErrorListener { _, what, extra -> onError(text(R.string.msg_mediarecorder_value_value, (what).toString(), (extra).toString())) }
        next.prepare()
        val existing = display
        if (existing == null) {
            display = checkNotNull(projection).createVirtualDisplay(
                text(R.string.app_name), size.width, size.height, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, next.surface, null, handler,
            )
        } else {
            existing.resize(size.width, size.height, densityDpi)
            existing.surface = next.surface
        }
        next.start()
    }

    fun finish() {
        val current = recorder ?: return
        recorder = null
        try {
            display?.surface = null
            current.stop()
        } finally { current.release() }
    }

    fun close() {
        try { finish() } finally {
            display?.release()
            display = null
            projection?.unregisterCallback(callback)
            projection?.stop()
            projection = null
        }
    }
}
