package app.debunkax.ui.common

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ZONE: ZoneId = ZoneId.systemDefault()
private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.FRENCH).withZone(ZONE)
private val SESSION_DAY_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM", Locale.FRENCH).withZone(ZONE)
private val FULL_DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.FRENCH).withZone(ZONE)

fun formatTime(instant: Instant): String = TIME_FORMAT.format(instant)

fun formatFullDateTime(instant: Instant): String = FULL_DATE_TIME_FORMAT.format(instant)

/** e.g. "mar. 23 sept., 21:02 – 22:47", or "… – en cours" while the session hasn't ended. */
fun formatSessionHeader(startedAt: Instant, endedAt: Instant?): String {
    val day = SESSION_DAY_FORMAT.format(startedAt)
    val end = endedAt?.let(::formatTime) ?: "en cours"
    return "$day, ${formatTime(startedAt)} – $end"
}
