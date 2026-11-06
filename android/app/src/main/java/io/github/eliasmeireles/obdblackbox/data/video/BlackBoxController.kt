package io.github.eliasmeireles.obdblackbox.data.video

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import io.github.eliasmeireles.obdblackbox.domain.events.RecordedEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BlackBoxState(
    val recording: Boolean = false,
    val elapsedMs: Long = 0,
    val messageId: Int = R.string.msg_gravacao_parada,
) {
    val message: String get() = text(messageId)
}

/** Process-local only: a new process always needs a fresh screen capture consent. */
class BlackBoxController {
    private val mutableState = MutableStateFlow(BlackBoxState())
    val state = mutableState.asStateFlow()
    @Volatile var onEvent: ((RecordedEvent) -> Unit)? = null

    fun protect(event: RecordedEvent) { onEvent?.invoke(event) }

    fun recording(elapsedMs: Long) {
        mutableState.value = BlackBoxState(recording = true, elapsedMs = elapsedMs, messageId = R.string.msg_gravando_a_tela)
    }

    fun stopped(message: Int = R.string.msg_gravacao_parada) {
        mutableState.value = BlackBoxState(messageId = message)
    }
}
