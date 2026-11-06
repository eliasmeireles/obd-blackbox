package io.github.eliasmeireles.obdblackbox.data.upload

import io.github.eliasmeireles.obdblackbox.data.recording.SessionInfo

/** Prefers [primary] (WebSocket); when it cannot carry a request at all, the same request goes over [fallback]. */
class FallbackMonitorApiClient(private val primary: MonitorApiClient, private val fallback: MonitorApiClient) : MonitorApiClient {
    override val live: Boolean get() = primary.live

    override fun maintainConnection(config: ServerConfig) = primary.maintainConnection(config)
    override fun disconnect() { primary.disconnect(); fallback.disconnect() }
    override fun postDiagnostics(config: ServerConfig, sessionId: String, json: String): ApiResult =
        attempt { it.postDiagnostics(config, sessionId, json) }

    override fun putSession(config: ServerConfig, session: SessionInfo): ApiResult = attempt { it.putSession(config, session) }

    override fun postSamples(config: ServerConfig, sessionId: String, samples: List<String>): ApiResult =
        attempt { it.postSamples(config, sessionId, samples) }

    override fun postLogs(config: ServerConfig, entries: List<String>): ApiResult = attempt { it.postLogs(config, entries) }

    private inline fun attempt(call: (MonitorApiClient) -> ApiResult): ApiResult {
        val result = call(primary)
        return if (result is ApiResult.Failed && result.code == null) call(fallback) else result
    }
}
