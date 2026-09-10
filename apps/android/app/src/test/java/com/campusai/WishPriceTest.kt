package com.campusai

import com.campusai.features.community.wishPriceCents
import com.campusai.features.community.wishPriceValid
import com.campusai.features.community.wishPriceLabel
import org.junit.Assert.*
import org.junit.Test

class WishPriceTest {
    @Test fun `blank means no price while zero means free`() {
        assertTrue(wishPriceValid(""))
        assertTrue(wishPriceValid("  "))
        assertNull(wishPriceCents(""))
        assertEquals(0, wishPriceCents("0"))
        assertEquals("未设价格", wishPriceLabel(null))
        assertEquals("¥0.00", wishPriceLabel(0))
    }

    @Test fun `money is exact and rejects overflow and fractional cents`() {
        assertEquals(1299, wishPriceCents("12.99"))
        assertEquals(Int.MAX_VALUE, wishPriceCents("21474836.47"))
        listOf("21474836.48", "9999999999", "1.001", "-1", ".", "1..2", "1e3").forEach {
            assertFalse(it, wishPriceValid(it))
        }
    }
}
