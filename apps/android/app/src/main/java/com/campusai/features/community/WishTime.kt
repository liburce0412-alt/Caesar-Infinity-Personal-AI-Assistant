package com.campusai.features.community

import com.campusai.core.time.CaesarTime

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

fun wishRecordedTime(value: String, today: LocalDate = CaesarTime.today(), full: Boolean = false): String {
    val time = CaesarTime.parseRemoteInstant(value)?.atZone(CaesarTime.zone)
        ?: return "记录时间未知"
    if (full) return "记于 ${time.format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm"))}"
    return when (time.toLocalDate()) {
        today -> "今天记下"
        today.minusDays(1) -> "昨天记下"
        else -> "${time.format(DateTimeFormatter.ofPattern("M月d日"))}记下"
    }
}

fun wishDateMessage(value: String, today: LocalDate = CaesarTime.today()): String? {
    val date = CaesarTime.parseDateKey(value) ?: return null
    val days = ChronoUnit.DAYS.between(today, date)
    return when {
        days > 0 -> "还有 $days 天，慢慢靠近它"
        days == 0L -> "今天，给这个愿望一点时间"
        else -> "慢慢来，它还在这里"
    }
}

fun wishToRevisit(wishes: List<MarketplaceListing>, userId: String, today: LocalDate = CaesarTime.today()): MarketplaceListing? {
    val old = wishes.filter { wish ->
        wish.sellerId == userId && runCatching {
            CaesarTime.parseRemoteInstant(wish.createdAt)?.atZone(CaesarTime.zone)?.toLocalDate()
                ?.let { it <= today.minusDays(30) } ?: false
        }.getOrDefault(false)
    }.sortedBy { it.id }
    return old.takeIf { it.isNotEmpty() }?.let { it[Math.floorMod(today.toEpochDay(), it.size.toLong()).toInt()] }
}
