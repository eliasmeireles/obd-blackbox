package io.github.eliasmeireles.obdblackbox.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val clock = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

fun clockTime(epochMs: Long): String = clock.format(Instant.ofEpochMilli(epochMs))

fun elapsed(ms: Long): String {
    val total = ms / 1_000
    return "%02d:%02d:%02d".format(total / 3_600, total / 60 % 60, total % 60)
}
