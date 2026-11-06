package io.github.eliasmeireles.obdblackbox

import io.github.eliasmeireles.obdblackbox.i18n.AppLocales
import android.app.Application
import io.github.eliasmeireles.obdblackbox.data.logs.LogLevel

class ObdBlackBoxApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        AppLocales.install(this)
        container = AppContainer(this)
        reportCrashes()
    }

    /** A crash is written to the upload queue before the process dies; it reaches the server on the next start. */
    private fun reportCrashes() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { container.log.log(LogLevel.ERROR, "crash", "${thread.name}: ${error.stackTraceToString()}") }
            previous?.uncaughtException(thread, error)
        }
    }
}
