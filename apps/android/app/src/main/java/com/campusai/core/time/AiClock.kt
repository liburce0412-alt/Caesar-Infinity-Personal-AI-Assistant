package com.campusai.core.time

import java.time.Instant
import java.time.format.DateTimeFormatter
import org.json.JSONObject

/** Refreshed from the clock for each turn, never inferred from conversation or image dates. */
object AiClock {
    fun context(now: Instant = Instant.now()): JSONObject = JSONObject()
        .put("instant", now.toString())
        .put("timezone", CaesarTime.zone.id)
        .put("localDateTime", now.atZone(CaesarTime.zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        .put("source", "device_clock")

    fun reply(prompt: String, now: Instant = Instant.now()): String? {
        val question = prompt.trim().replace(Regex("\\s+"), "")
        if (!clockQuestion.matches(question)) return null
        return "现在是北京时间 ${now.atZone(CaesarTime.zone).format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm:ss"))}。"
    }

    private val clockQuestion = Regex(
        "^(?:(?:请|请问|告诉我|帮我看看)[，,]?)?(?:(?:现在|此刻|当前|今天)(?:的)?(?:北京)?(?:时间)?(?:是)?(?:几点(?:了|钟)?|几月几[日号]|几号|什么时间|什么日期)|北京时间(?:是)?(?:几点|多少)|现在(?:的)?(?:北京)?时间|今天(?:的)?日期)[呢吗啊呀了?？。.!！]*$",
    )
}
