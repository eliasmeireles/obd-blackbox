package io.github.eliasmeireles.obdblackbox.ui

/** Unknown persisted values always follow the system. */
enum class AppLanguage(val tag: String) {
    AUTO(""), PORTUGUESE("pt-BR"), ENGLISH("en");
    companion object {
        fun parse(value: String?): AppLanguage = entries.firstOrNull { it.name == value || it.tag == value } ?: AUTO
    }
}

enum class AppTheme {
    AUTO, DARK, LIGHT;
    companion object {
        fun parse(value: String?): AppTheme = entries.firstOrNull { it.name == value } ?: AUTO
    }
}
