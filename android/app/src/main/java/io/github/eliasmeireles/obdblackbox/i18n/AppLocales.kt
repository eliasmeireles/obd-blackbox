package io.github.eliasmeireles.obdblackbox.i18n

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import io.github.eliasmeireles.obdblackbox.ui.AppLanguage
import java.util.Locale

object AppLocales {
    private const val STORE = "display"
    private const val KEY = "language"
    @Volatile private var cached: Pair<Configuration, android.content.res.Resources>? = null

    @Synchronized
    private fun resources(context: Context): android.content.res.Resources {
        val configuration = context.resources.configuration
        cached?.takeIf { it.first == configuration }?.let { return it.second }
        return wrap(context).resources.also { cached = Configuration(configuration) to it }
    }

    fun current(context: Context): AppLanguage {
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            return AppLanguage.parse(if (locales.isEmpty) "" else locales[0].toLanguageTag())
        }
        return AppLanguage.parse(context.getSharedPreferences(STORE, Context.MODE_PRIVATE).getString(KEY, null))
    }

    fun apply(context: Context, language: AppLanguage) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().putString(KEY, language.name).apply()
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(language.tag)
        }
        cached = null
    }

    fun wrap(context: Context): Context {
        val language = current(context)
        val locale = if (language == AppLanguage.AUTO) {
            if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java).systemLocales[0]
            else android.content.res.Resources.getSystem().configuration.locales[0]
        } else Locale.forLanguageTag(language.tag)
        val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
        return context.createConfigurationContext(config)
    }

    fun install(context: Context) {
        val app = context.applicationContext
        TextResources.resolve = { id, args ->
            val resources = resources(app)
            if (args.isEmpty()) resources.getString(id) else resources.getString(id, *args)
        }
        TextResources.quantity = { id, count, args -> resources(app).getQuantityString(id, count, *args) }
        TextResources.locale = { resources(app).configuration.locales[0] }
    }
}
