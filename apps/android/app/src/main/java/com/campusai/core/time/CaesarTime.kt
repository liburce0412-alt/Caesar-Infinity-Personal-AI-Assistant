package com.campusai.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Business dates and displayed wall time use Beijing; stored instants remain unchanged. */
object CaesarTime {
    val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    fun today(now: Instant = Instant.now()): LocalDate = now.atZone(zone).toLocalDate()

    fun format(epochMillis: Long, pattern: String = "MM-dd HH:mm"): String =
        DateTimeFormatter.ofPattern(pattern, Locale.CHINA).withZone(zone)
            .format(Instant.ofEpochMilli(epochMillis))

    /** A timestamp without an offset cannot reliably identify an instant. Do not invent one. */
    fun parseRemoteInstant(value: String): Instant? = runCatching {
        OffsetDateTime.parse(value.trim().replace(' ', 'T')).toInstant()
    }.getOrNull()

    fun formatRemote(value: String): String = parseRemoteInstant(value)
        ?.let { format(it.toEpochMilli(), "yyyy-MM-dd HH:mm") } ?: "时间未知"

    /** Existing date keys are calendar dates, never instants to be shifted to a new zone. */
    fun parseDateKey(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()

    /** Material DatePicker encodes a date as UTC midnight, independent of the business zone. */
    fun datePickerMillis(dateKey: String): Long? = parseDateKey(dateKey)
        ?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()

    fun dateKeyFromPickerMillis(value: Long): String =
        Instant.ofEpochMilli(value).atOffset(ZoneOffset.UTC).toLocalDate().toString()
}
