package com.campusai.features.community

import java.math.BigDecimal
import java.util.Locale

/** Blank is intentionally unpriced, distinct from an explicit zero-price wish. */
internal fun wishPriceCents(input: String): Int? {
    val value = input.trim()
    if (!Regex("\\d+(\\.\\d{1,2})?").matches(value)) return null
    return runCatching { BigDecimal(value).movePointRight(2).intValueExact() }
        .getOrNull()?.takeIf { it >= 0 }
}

internal fun wishPriceValid(input: String): Boolean = input.isBlank() || wishPriceCents(input) != null

internal fun wishPriceLabel(cents: Int?): String =
    cents?.let { String.format(Locale.CHINA, "¥%.2f", it / 100.0) } ?: "未设价格"
