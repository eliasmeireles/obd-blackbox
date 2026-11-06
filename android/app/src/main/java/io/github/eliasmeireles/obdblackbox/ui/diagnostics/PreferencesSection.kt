package io.github.eliasmeireles.obdblackbox.ui.diagnostics

import androidx.compose.foundation.lazy.LazyListScope
import io.github.eliasmeireles.obdblackbox.R
import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.ui.AppLanguage
import io.github.eliasmeireles.obdblackbox.ui.AppTheme

/** Activity owns locale recreation; the display store owns theme changes. */
data class PreferenceSettings(
    val language: AppLanguage,
    val onLanguage: (AppLanguage) -> Unit,
    val theme: AppTheme,
    val onTheme: (AppTheme) -> Unit,
)

internal fun LazyListScope.preferencesSection(settings: PreferenceSettings) {
    item {
        SettingCard(text(R.string.msg_idioma_language)) {
            ChoiceChips(AppLanguage.entries, settings.language, { option ->
                when (option) {
                    AppLanguage.AUTO -> text(R.string.msg_automatico)
                    AppLanguage.PORTUGUESE -> text(R.string.msg_portugues)
                    AppLanguage.ENGLISH -> text(R.string.msg_english)
                }
            }, settings.onLanguage)
        }
    }
    item {
        SettingCard(text(R.string.msg_tema)) {
            ChoiceChips(AppTheme.entries, settings.theme, { option ->
                when (option) {
                    AppTheme.AUTO -> text(R.string.msg_automatico)
                    AppTheme.DARK -> text(R.string.msg_escuro)
                    AppTheme.LIGHT -> text(R.string.msg_claro)
                }
            }, settings.onTheme)
        }
    }
}
