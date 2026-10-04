package com.campusai.features.ai

import com.campusai.core.model.CourseSchedule
import com.campusai.core.model.TimeRecord
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class AiPostContext(
    val id: String,
    val authorId: String,
    val body: String,
    val topic: String,
    val likes: Int,
    val comments: Int,
    val createdAt: String,
)

data class AiContextSnapshot(
    val userId: String = "",
    val displayName: String = "Caesar 用户",
    val records: List<TimeRecord> = emptyList(),
    val courses: List<CourseSchedule> = emptyList(),
    val visiblePosts: List<AiPostContext> = emptyList(),
)

data class AiContextSelection(
    val timeRecords: Boolean = true,
    val courses: Boolean = true,
    val ownPosts: Boolean = true,
    val publicPosts: Boolean = false,
    /** One-shot consent. AiViewModel resets this immediately after constructing a request. */
    val healthSummary: Boolean = false,
)

data class AiActionBlock(
    val subject: String,
    val durationMinutes: Long,
)

data class AiTaskPresentation(
    val headline: String,
    val actionBlocks: List<AiActionBlock>,
    val breakMinutes: Long,
    val remainingAfterPlanMinutes: Long,
) {
    fun toJson(): String = JSONObject()
        .put("headline", headline)
        .put("breakMinutes", breakMinutes)
        .put("remainingAfterPlanMinutes", remainingAfterPlanMinutes)
        .put("actionBlocks", JSONArray(actionBlocks.map { block ->
            JSONObject().put("subject", block.subject).put("durationMinutes", block.durationMinutes)
        }))
        .toString()

    companion object {
        fun fromJson(value: String?): AiTaskPresentation? = value?.takeIf(String::isNotBlank)?.let { raw ->
            runCatching {
                val json = JSONObject(raw)
                val rows = json.optJSONArray("actionBlocks") ?: JSONArray()
                AiTaskPresentation(
                    headline = json.optString("headline"),
                    actionBlocks = buildList {
                        repeat(rows.length()) { index ->
                            rows.optJSONObject(index)?.let { row ->
                                add(AiActionBlock(row.optString("subject"), row.optLong("durationMinutes")))
                            }
                        }
                    },
                    breakMinutes = json.optLong("breakMinutes"),
                    remainingAfterPlanMinutes = json.optLong("remainingAfterPlanMinutes"),
                )
            }.getOrNull()
        }
    }
}

object DailyGreetingPolicy {
    fun fallback(displayName: String, date: LocalDate): String {
        val options = listOf(
            "先完成一件最重要的小事",
            "给今天留一段不被打扰的时间",
            "从下一段专注开始建立节奏",
            "把注意力交给眼前这一件事",
            "记录会让今天的投入更清晰",
        )
        return options[Math.floorMod((date.toEpochDay() + displayName.hashCode()).toInt(), options.size)]
    }

    fun isGrounded(
        text: String,
        snapshot: AiContextSnapshot,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        if (text.isBlank() || text.length > 24) return false
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val today = now.toLocalDate()
        val currentMinute = now.hour * 60 + now.minute
        val hasFutureCourse = snapshot.courses.any {
            it.weekday == today.dayOfWeek.value && it.endMinute > currentMinute
        }
        if (!hasFutureCourse && listOf("还有课", "下一节课", "去上课", "准备上课", "课程马上").any(text::contains)) return false
        if (Regex("(?:凌晨|早上|上午|中午|下午|晚上|今晚)?\\s*[0-2]?\\d\\s*[点时]").containsMatchIn(text)) return false
        if (Regex("校园.*(?:安静|静悄悄|热闹|空荡|人多)").containsMatchIn(text)) return false
        if (listOf("晴天", "下雨", "气温", "天气", "刮风", "下雪").any(text::contains)) return false
        val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val hasTodayRecord = snapshot.records.any { it.durationMinutes > 0 && it.endTime > it.startTime && it.endTime <= nowMillis && it.endTime in dayStart until dayEnd }
        if (!hasTodayRecord && Regex("(?:已经|已|完成了|投入了|记录了).*(?:学习|分钟|记录|专注)").containsMatchIn(text)) return false
        return true
    }
}

object AiContextAssembler {
    private const val MAX_POSTS = 10
    private const val MAX_PUBLIC_POSTS = 6
    private const val MAX_RECENT_RECORDS = 12

    fun enrich(
        base: JSONObject,
        task: CampusAiTask,
        prompt: String,
        snapshot: AiContextSnapshot,
        selection: AiContextSelection,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): JSONObject {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val personal = JSONObject()
            .put("displayName", snapshot.displayName.take(48))
            .put("localDate", today.toString())
            .put("timezone", zone.id)
            .put("locale", "zh-CN")

        val learningQuestion = task != CampusAiTask.CHAT || LEARNING_TERMS.any { prompt.contains(it, ignoreCase = true) }
        val courseQuestion = task == CampusAiTask.SCHEDULE_CLEANUP || COURSE_TERMS.any { prompt.contains(it, ignoreCase = true) }
        if (selection.timeRecords && learningQuestion) personal.put("learning", learningContext(snapshot.records.filter { it.endTime <= nowMillis }, prompt, today, zone))
        if (selection.courses && courseQuestion) {
            val currentMinute = Instant.ofEpochMilli(nowMillis).atZone(zone).let { it.hour * 60 + it.minute }
            personal.put("courses", courseContext(snapshot.courses, today, currentMinute))
        }

        val postQuestion = task == CampusAiTask.CHAT && POST_TERMS.any { prompt.contains(it, ignoreCase = true) }
        if (selection.ownPosts) {
            val own = snapshot.visiblePosts.filter { it.authorId == snapshot.userId }
            personal.put("ownPostCount", own.size)
            if (postQuestion) personal.put("ownPosts", postsJson(own.take(MAX_POSTS)))
        }
        if (selection.publicPosts && postQuestion) {
            personal.put(
                "selectedPublicPosts",
                postsJson(snapshot.visiblePosts.filter { it.authorId != snapshot.userId }.take(MAX_PUBLIC_POSTS)),
            )
        }
        return JSONObject(base.toString()).put("personalContext", personal)
    }

    fun greetingContext(
        snapshot: AiContextSnapshot,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): JSONObject {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val todayRecords = com.campusai.core.model.TimeRecordCalendar.inRange(snapshot.records, "日", Instant.ofEpochMilli(nowMillis), zone)
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val currentMinute = now.hour * 60 + now.minute
        val nextCourse = snapshot.courses
            .filter { it.weekday == today.dayOfWeek.value && it.startMinute >= currentMinute }
            .minByOrNull(CourseSchedule::startMinute)
        return JSONObject()
            .put("task", "daily_greeting")
            .put("displayName", snapshot.displayName.take(48))
            .put("localDate", today.toString())
            .put("timeOfDay", timeOfDay(now.hour))
            .put("todayRecordCount", todayRecords.size)
            .put("todayMinutes", todayRecords.sumOf(TimeRecord::durationMinutes))
            .put("scheduleDateReviewRequired", true)
            .put("nextCourse", nextCourse?.name ?: JSONObject.NULL)
            .put("locale", "zh-CN")
    }

    private fun learningContext(records: List<TimeRecord>, prompt: String, today: LocalDate, zone: ZoneId): JSONObject {
        val weekStart = today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        val monthStart = today.withDayOfMonth(1)
        val todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val tomorrowStart = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val weekStartMs = weekStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val monthStartMs = monthStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val selectedStart = when {
            prompt.contains("今天") || prompt.contains("今日") -> todayStart
            prompt.contains("本周") || prompt.contains("这周") -> weekStartMs
            prompt.contains("本月") || prompt.contains("这个月") -> monthStartMs
            else -> Long.MIN_VALUE
        }
        val selected = records.filter { it.durationMinutes > 0 && it.endTime > it.startTime && it.endTime in selectedStart until tomorrowStart }
            .sortedWith(compareByDescending<TimeRecord> { it.endTime }.thenByDescending { it.startTime })
            .take(MAX_RECENT_RECORDS)
        return JSONObject()
            .put("today", summary(records, todayStart, tomorrowStart, 240L, zone, today))
            .put("week", summary(records, weekStartMs, tomorrowStart, 1_680L, zone, today))
            .put("month", summary(records, monthStartMs, tomorrowStart, 7_200L, zone, today))
            .put("allTime", summary(records, Long.MIN_VALUE, Long.MAX_VALUE, null, zone, today))
            .put("recentRelevantEntries", JSONArray(selected.map(::recordJson)))
    }

    private fun summary(
        records: List<TimeRecord>,
        start: Long,
        end: Long,
        target: Long?,
        zone: ZoneId,
        today: LocalDate,
    ): JSONObject {
        val selected = records.filter { it.durationMinutes > 0 && it.endTime > it.startTime && it.endTime in start until end }
        val total = selected.sumOf(TimeRecord::durationMinutes)
        val categories = JSONObject()
        selected.groupBy(TimeRecord::category).toSortedMap().forEach { (name, rows) ->
            categories.put(name.take(40), rows.sumOf(TimeRecord::durationMinutes))
        }
        val daily = selected.groupBy { Instant.ofEpochMilli(it.endTime).atZone(zone).toLocalDate() }
            .mapValues { (_, rows) -> rows.sumOf(TimeRecord::durationMinutes) }
            .toSortedMap()
        val activeDates = daily.keys
        var cursor = if (today in activeDates) today else today.minusDays(1)
        var streak = 0
        while (cursor in activeDates) { streak++; cursor = cursor.minusDays(1) }
        val values = daily.values.toList()
        val split = values.size / 2
        val earlier = values.take(split)
        val later = values.drop(split)
        val delta = if (earlier.isEmpty() || later.isEmpty()) 0L else later.sum() / later.size - earlier.sum() / earlier.size
        return JSONObject()
            .put("recordCount", selected.size)
            .put("totalMinutes", total)
            .put("targetMinutes", target ?: JSONObject.NULL)
            .put("remainingMinutes", target?.let { (it - total).coerceAtLeast(0L) } ?: JSONObject.NULL)
            .put("goalRateBasisPoints", target?.takeIf { it > 0 }?.let { total * 10_000L / it } ?: JSONObject.NULL)
            .put("activeDays", daily.size)
            .put("currentStreakDays", streak)
            .put("trendDirection", when { values.size < 2 -> "insufficient_data"; delta > 0 -> "up"; delta < 0 -> "down"; else -> "flat" })
            .put("trendDeltaAverageMinutes", delta)
            .put("categoryMinutes", categories)
    }

    private fun courseContext(courses: List<CourseSchedule>, today: LocalDate, currentMinute: Int): JSONObject {
        val conflicts = JSONArray(com.campusai.features.schedule.potentialCourseOverlaps(courses).map { (first, second) ->
            JSONObject().put("first", first.name).put("second", second.name).put("weekday", first.weekday)
                .put("requiresDateReview", true).put("basis", "time_overlap_only")
        })
        val rows = courses.sortedWith(compareBy(CourseSchedule::weekday, CourseSchedule::startMinute)).take(20)
        val next = courses
            .filter { it.weekday == today.dayOfWeek.value && it.startMinute >= currentMinute }
            .minByOrNull(CourseSchedule::startMinute)
        return JSONObject()
            .put("todayWeekday", today.dayOfWeek.value)
            .put("scheduleDateReviewRequired", true)
            .put("nextCourse", next?.let { course -> JSONObject()
                .put("name", course.name.take(80))
                .put("startMinute", course.startMinute)
                .put("endMinute", course.endMinute)
                .put("periodStart", course.periodStart).put("periodEnd", course.periodEnd)
                .put("location", course.location.take(80))
            } ?: JSONObject.NULL)
            .put("items", JSONArray(rows.map { course -> JSONObject()
                .put("name", course.name.take(80))
                .put("weekday", course.weekday)
                .put("startMinute", course.startMinute)
                .put("endMinute", course.endMinute)
                .put("periodStart", course.periodStart).put("periodEnd", course.periodEnd)
                .put("location", course.location.take(80))
                .put("teacher", course.teacher.take(48))
                .put("weeks", course.weeks.take(80))
            }))
            .put("computedConflicts", conflicts)
    }

    private fun postsJson(posts: List<AiPostContext>) = JSONArray(posts.map { post -> JSONObject()
        .put("id", post.id)
        .put("body", post.body.take(240))
        .put("topic", post.topic.take(48))
        .put("likes", post.likes)
        .put("comments", post.comments)
        .put("createdAt", post.createdAt)
    })

    private fun recordJson(record: TimeRecord) = JSONObject()
        .put("title", record.title.take(80))
        .put("category", record.category.take(40))
        .put("durationMinutes", record.durationMinutes)
        .put("startTime", record.startTime)
        .put("endTime", record.endTime)
        .put("dateBasis", "completion")

    private fun timeOfDay(hour: Int) = when (hour) {
        in 5..10 -> "morning"
        in 11..13 -> "noon"
        in 14..17 -> "afternoon"
        else -> "evening"
    }

    private val POST_TERMS = listOf("帖子", "动态", "发布", "校园", "点赞", "评论")
    private val LEARNING_TERMS = listOf("学习", "时间", "记录", "专注", "目标", "总结", "复盘", "今天", "今日", "本周", "这周", "本月", "这个月", "投入", "趋势", "连续", "分类")
    private val COURSE_TERMS = listOf("课", "课程", "课程表", "上课", "教室", "老师", "冲突", "空课")
}
