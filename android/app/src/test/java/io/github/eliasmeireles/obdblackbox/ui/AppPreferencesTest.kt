package io.github.eliasmeireles.obdblackbox.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AppPreferencesTest {
    @Test fun `language parses persisted names and Android locale tags`() {
        assertEquals(AppLanguage.PORTUGUESE, AppLanguage.parse("pt-BR"))
        assertEquals(AppLanguage.PORTUGUESE, AppLanguage.parse("PORTUGUESE"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.parse("en"))
        for (unknown in listOf(null, "", "unknown")) assertEquals(AppLanguage.AUTO, AppLanguage.parse(unknown))
    }

    @Test fun `theme parsing tolerates missing and future preferences`() {
        assertEquals(AppTheme.DARK, AppTheme.parse("DARK"))
        assertEquals(AppTheme.LIGHT, AppTheme.parse("LIGHT"))
        for (unknown in listOf(null, "", "unknown")) assertEquals(AppTheme.AUTO, AppTheme.parse(unknown))
    }
}
