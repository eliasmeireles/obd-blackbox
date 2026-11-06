package io.github.eliasmeireles.obdblackbox.data.upload

import android.content.Context
import io.github.eliasmeireles.obdblackbox.R

/** The build chooses the server; only its API key is editable on the device. */
data class ServerConfig(val baseUrl: String, val apiKey: String) {
    val isComplete: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank()
}

interface ServerConfigStore {
    fun load(): ServerConfig
    fun save(config: ServerConfig)
}

class PreferencesServerConfigStore(context: Context) : ServerConfigStore {
    private val prefs = context.getSharedPreferences("server", Context.MODE_PRIVATE)
    private val baseUrl = context.getString(R.string.server_api_url).trim().trimEnd('/')

    override fun load() = ServerConfig(baseUrl, prefs.getString(KEY_API, null).orEmpty())

    override fun save(config: ServerConfig) {
        prefs.edit().remove("base_url").putString(KEY_API, config.apiKey.trim()).apply()
    }

    private companion object { const val KEY_API = "api_key" }
}
