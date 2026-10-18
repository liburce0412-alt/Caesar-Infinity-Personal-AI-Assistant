package com.campusai.features.ai

import com.campusai.core.time.AiClock
import java.time.Instant
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AiClockTest {
    @Test fun `clock refreshes across Beijing midnight regardless of device timezone`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val before = Instant.parse("2026-10-17T15:59:59Z")
            val after = before.plusSeconds(2)
            assertEquals("现在是北京时间 2026年10月17日 23:59:59。", CaesarDeterministicReply.forPrompt("现在几点？", false, before))
            assertEquals("现在是北京时间 2026年10月18日 00:00:01。", CaesarDeterministicReply.forPrompt("请问现在几点了", false, after))
            assertEquals("Asia/Shanghai", AiClock.context(after).getString("timezone"))
            assertTrue(AiClock.context(after).getString("localDateTime").endsWith("+08:00"))
            assertNull(CaesarDeterministicReply.forPrompt("图片里的时钟几点？", true, after))
            assertNull(CaesarDeterministicReply.forPrompt("现在几点开始的课程要准备什么", false, after))
        } finally { TimeZone.setDefault(original) }
    }
}
