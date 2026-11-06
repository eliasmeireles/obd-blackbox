package io.github.eliasmeireles.obdblackbox.service

import io.github.eliasmeireles.obdblackbox.i18n.text
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.eliasmeireles.obdblackbox.R
import io.github.eliasmeireles.obdblackbox.ui.MainActivity

class VideoNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(NotificationChannel(RECORDING_CHANNEL, text(R.string.msg_gravacao_de_tela), NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(SAVED_CHANNEL, text(R.string.msg_videos_protegidos), NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun refreshRecording() { manager.notify(RECORDING_ID, recording()) }

    fun recording(): Notification {
        val open = PendingIntent.getActivity(context, 20, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(context, 21, Intent(context, BlackBoxService::class.java).setAction(BlackBoxService.STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(context, RECORDING_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text(R.string.msg_gravando_a_tela))
            .setContentText(text(R.string.msg_tela_e_microfone_ultimos_trechos_no_celular))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, text(R.string.msg_parar_gravacao), stop).build())
            .build()
    }

    fun saved(uri: Uri) {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val send = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
            .apply { clipData = ClipData.newRawUri(text(R.string.msg_video), uri) }
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(send, text(R.string.msg_compartilhar_trecho))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(context, 22, view, flags)
        // Unique data/request code keeps previous notifications bound to their own clips.
        val share = PendingIntent.getActivity(context, uri.hashCode(), chooser, flags)
        val notification = Notification.Builder(context, SAVED_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text(R.string.msg_trecho_salvo_na_galeria))
            .setContentText(text(R.string.msg_toque_para_assistir_album_obd_black_box))
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, text(R.string.msg_compartilhar), share).build())
            .build()
        manager.notify(uri.toString(), 3, notification)
    }

    companion object {
        const val RECORDING_ID = 2
        private const val RECORDING_CHANNEL = "video_recording"
        private const val SAVED_CHANNEL = "video_saved"
    }
}
