package io.github.eliasmeireles.obdblackbox.domain.format

import io.github.eliasmeireles.obdblackbox.i18n.TextResources
import java.util.Locale

private fun displayLocale(): Locale = TextResources.locale()

const val MISSING_VALUE = "—"

fun Double.formatPt(decimals: Int): String = String.format(displayLocale(), "%.${decimals}f", this)

fun Double?.formatPtOrDash(decimals: Int): String = this?.formatPt(decimals) ?: MISSING_VALUE
