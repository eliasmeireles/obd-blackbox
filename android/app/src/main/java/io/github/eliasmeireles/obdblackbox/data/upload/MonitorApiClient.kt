package io.github.eliasmeireles.obdblackbox.data.upload

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import io.github.eliasmeireles.obdblackbox.data.recording.SessionInfo
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed interface ApiResult {
    data object Ok : ApiResult

    /** [code] is null for network errors (no response). */
    data class Failed(val code: Int?, val message: String) : ApiResult
}

interface MonitorApiClient {
    /** True while samples go out as soon as they are read (open WebSocket) instead of in periodic HTTP requests. */
    val live: Boolean get() = false

    fun maintainConnection(config: ServerConfig) = Unit
    fun disconnect() = Unit
    fun postDiagnostics(config: ServerConfig, sessionId: String, json: String): ApiResult = ApiResult.Failed(501, text(R.string.msg_diagnosticos_indisponiveis))

    fun putSession(config: ServerConfig, session: SessionInfo): ApiResult

    /** [samples] are already-serialized sample objects of one session. */
    fun postSamples(config: ServerConfig, sessionId: String, samples: List<String>): ApiResult

    /** [entries] are already-serialized app log entries. */
    fun postLogs(config: ServerConfig, entries: List<String>): ApiResult
}

class HttpMonitorApiClient(private val appVersion: String) : MonitorApiClient {

    override fun postDiagnostics(config: ServerConfig, sessionId: String, json: String): ApiResult =
        send(config, "POST", "/api/v1/sessions/$sessionId/diagnostics", json)

    override fun putSession(config: ServerConfig, session: SessionInfo): ApiResult =
        send(config, "PUT", "/api/v1/sessions/${session.id}", UploadPayload.session(session, appVersion))

    override fun postSamples(config: ServerConfig, sessionId: String, samples: List<String>): ApiResult =
        send(config, "POST", "/api/v1/sessions/$sessionId/samples", UploadPayload.batch(samples))

    override fun postLogs(config: ServerConfig, entries: List<String>): ApiResult =
        send(config, "POST", "/api/v1/logs", UploadPayload.logs(entries))

    private fun send(config: ServerConfig, method: String, path: String, body: String): ApiResult {
        val connection = URL(config.baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
            val code = connection.responseCode
            if (code in 200..299) ApiResult.Ok else ApiResult.Failed(code, "HTTP $code")
        } catch (e: IOException) {
            ApiResult.Failed(null, e.message ?: e.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 20_000
    }
}
