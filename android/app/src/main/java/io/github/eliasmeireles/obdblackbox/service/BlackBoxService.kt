package io.github.eliasmeireles.obdblackbox.service

import io.github.eliasmeireles.obdblackbox.i18n.AppLocales
import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import io.github.eliasmeireles.obdblackbox.ObdBlackBoxApp
import io.github.eliasmeireles.obdblackbox.data.logs.LogLevel
import io.github.eliasmeireles.obdblackbox.data.video.BlackBoxRecording
import io.github.eliasmeireles.obdblackbox.data.video.ScreenCapture
import io.github.eliasmeireles.obdblackbox.domain.video.CaptureSize

class BlackBoxService : Service() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLocales.wrap(newBase))
    }

    private val container get() = (application as ObdBlackBoxApp).container
    private val worker = HandlerThread("obd-video")
    private lateinit var handler: Handler
    private var recording: BlackBoxRecording? = null
    private var started = false
    private var stopped = false
    private val tick = object : Runnable {
        override fun run() {
            safely { recording?.tick() }
            if (!stopped) handler.postDelayed(this, 1_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        worker.start()
        handler = Handler(worker.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP || intent == null) {
            handler.post { stopRecording(STOPPED) }
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        started = true
        try {
            val notifications = VideoNotifications(this)
            if (Build.VERSION.SDK_INT >= 29) {
                val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
                startForeground(VideoNotifications.RECORDING_ID, notifications.recording(),
                    types)
            } else startForeground(VideoNotifications.RECORDING_ID, notifications.recording())
            val metrics = displayMetrics()
            @Suppress("DEPRECATION")
            val consent = intent.getParcelableExtra<Intent>(CONSENT)
            handler.post {
                safely {
                    val capture = ScreenCapture(this, handler,
                        onStopped = { stopRecording(STOPPED) },
                        onResize = { width, height -> safely { recording?.resize(width, height) } },
                        onError = { fail(IllegalStateException(it)) })
                    recording = BlackBoxRecording(this, capture, container.blackBox, container.log, notifications)
                    container.blackBox.onEvent = { event -> handler.post { safely { recording?.protect(event) } } }
                    recording!!.start(intent.getIntExtra(RESULT_CODE, 0), checkNotNull(consent),
                        CaptureSize.fromDisplay(metrics.widthPixels, metrics.heightPixels), metrics.densityDpi)
                    handler.post(tick)
                }
            }
        } catch (error: Exception) { handler.post { fail(error) } }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (Build.VERSION.SDK_INT >= 34) return // The projection reports its actual app/display bounds.
        val metrics = displayMetrics()
        handler.post { safely { recording?.resize(metrics.widthPixels, metrics.heightPixels) } }
    }

    private fun safely(action: () -> Unit) {
        if (stopped) return
        try { action() } catch (error: Exception) { fail(error) }
    }

    private fun fail(error: Exception) {
        container.log.log(LogLevel.ERROR, "video", "recording error: ${error.message}")
        stopRecording(R.string.msg_gravacao_parada_nao_foi_possivel_gravar_tela_e_microfone)
    }

    private fun stopRecording(message: Int) {
        if (stopped) return
        stopped = true
        container.blackBox.onEvent = null
        handler.removeCallbacks(tick)
        runCatching { recording?.stop() }.onFailure { container.log.log(LogLevel.ERROR, "video", "failed to finish: ${it.message}") }
        recording = null
        container.blackBox.stopped(message)
        container.log.log(LogLevel.INFO, "video", text(message))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.post {
            stopRecording(STOPPED)
            worker.quitSafely()
        }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun displayMetrics(): DisplayMetrics = DisplayMetrics().also {
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(it)
    }

    companion object {
        const val STOP = "io.github.eliasmeireles.obdblackbox.STOP_VIDEO"
        private val STOPPED = R.string.msg_gravacao_parada_esta_em_videos_gravacoes_recentes_por_2_h
        private const val CONSENT = "consent"
        private const val RESULT_CODE = "result_code"

        fun start(context: Context, resultCode: Int, consent: Intent) {
            context.startForegroundService(Intent(context, BlackBoxService::class.java)
                .putExtra(RESULT_CODE, resultCode).putExtra(CONSENT, consent))
        }

        fun stop(context: Context) { context.stopService(Intent(context, BlackBoxService::class.java)) }
    }
}
