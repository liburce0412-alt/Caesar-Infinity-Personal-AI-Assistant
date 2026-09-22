package com.campusai.features.ai

import com.campusai.core.model.CourseSchedule
import com.campusai.core.model.TimeRecord
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

enum class CampusAiTask { CHAT, HOME_INSIGHT, TODAY_SUMMARY, WEEK_SUMMARY, MONTH_SUMMARY, STRUCTURED_ADVICE, SCHEDULE_CLEANUP, TIME_PARSE }

data class CampusAiPayload(
    val prompt: String,
    val structuredContext: JSONObject,
    val displayPrompt: String = prompt,
    val presentation: AiTaskPresentation? = null,
)

object CampusAiTaskFactory {
    fun create(
        task: CampusAiTask,
        records: List<TimeRecord>,
        courses: List<CourseSchedule>,
        userInput: String = "",
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): CampusAiPayload {
        val range = when (task) {
            CampusAiTask.MONTH_SUMMARY -> LearningRange.MONTH
            CampusAiTask.WEEK_SUMMARY -> LearningRange.WEEK
            else -> LearningRange.TODAY
        }
        val facts = learningFacts(records, range, nowMillis, zone)
        val prompt = when (task) {
            CampusAiTask.CHAT -> userInput
            CampusAiTask.HOME_INSIGHT -> "只把 analysisStatements 的事实合成一句今日洞察，再用一句话复述 suggestedActionPlan.blocks 的第一项行动。不得加入 statements 之外的判断；对象与时长必须逐字照用计划字段。"
            CampusAiTask.TODAY_SUMMARY -> "只使用 analysisStatements 写今日分析，然后按顺序复述 suggestedActionPlan.blocks，并说明 remainingAfterPlanMinutes。不得写本周、本月、效率、休息习惯或 statements 中没有的判断；对象、时长、休息和剩余差距必须逐字照用计划字段。"
            CampusAiTask.WEEK_SUMMARY -> "只使用 analysisStatements 写本周分析，然后把 suggestedActionPlan 作为下一步启动计划逐项复述。不得加入 statements 之外的原因、成绩或效率判断；对象、时长、休息和剩余差距必须照用计划字段。"
            CampusAiTask.MONTH_SUMMARY -> "只使用 analysisStatements 写本月分析，然后把 suggestedActionPlan 作为下一步启动计划逐项复述。不得加入 statements 之外的原因、成绩或效率判断；对象、时长、休息和剩余差距必须照用计划字段。"
            CampusAiTask.STRUCTURED_ADVICE -> "只使用 analysisStatements 说明依据，再逐项复述 suggestedActionPlan。不得增加 statements 或计划之外的判断、对象或数字。"
            CampusAiTask.SCHEDULE_CLEANUP -> "整理课程表字段并指出时间重叠；周次、授课日期和排除日期未核实时只能标记待确认，不能断言实际上课冲突。"
            CampusAiTask.TIME_PARSE -> "把自然语言记录整理成标题、分类、开始时间、结束时间和时长；只使用解析器已确定的字段，未知项标记待确认。"
        }
        val context = JSONObject().put("task", task.name.lowercase())
        if (task != CampusAiTask.CHAT) context.put("learningFacts", facts)
        if (task == CampusAiTask.SCHEDULE_CLEANUP) context.put("scheduleFacts", scheduleFacts(courses))
        if (task == CampusAiTask.TIME_PARSE) context.put("timeParseFacts", parseTimeFacts(userInput, nowMillis, zone))
        val displayPrompt = when (task) {
            CampusAiTask.CHAT -> userInput
            CampusAiTask.HOME_INSIGHT -> "今日洞察"
            CampusAiTask.TODAY_SUMMARY -> "今日总结"
            CampusAiTask.WEEK_SUMMARY -> "本周总结"
            CampusAiTask.MONTH_SUMMARY -> "本月总结"
            CampusAiTask.STRUCTURED_ADVICE -> "生成学习建议"
            CampusAiTask.SCHEDULE_CLEANUP -> "整理课程表"
            CampusAiTask.TIME_PARSE -> userInput.takeIf(String::isNotBlank)?.let { "解析记录：${it.take(80)}" } ?: "解析时间记录"
        }
        return CampusAiPayload(
            prompt = prompt,
            structuredContext = context,
            displayPrompt = displayPrompt,
            presentation = facts.toPresentation(task),
        )
    }

    private enum class LearningRange { TODAY, WEEK, MONTH }

    private fun learningFacts(records: List<TimeRecord>, range: LearningRange, nowMillis: Long, zone: ZoneId): JSONObject {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val startDate = when (range) {
            LearningRange.TODAY -> today
            LearningRange.WEEK -> today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            LearningRange.MONTH -> today.withDayOfMonth(1)
        }
        val start = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val selected = com.campusai.core.model.TimeRecordCalendar.inRange(records, when (range) { LearningRange.TODAY -> "日"; LearningRange.WEEK -> "周"; LearningRange.MONTH -> "月" }, Instant.ofEpochMilli(nowMillis), zone)
        val total = selected.sumOf(TimeRecord::durationMinutes)
        val days = generateSequence(startDate) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.toList()
        val perDay = days.map { day ->
            val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            day to selected.filter { it.durationMinutes > 0 && it.endTime > it.startTime && it.endTime in dayStart until dayEnd }.sumOf(TimeRecord::durationMinutes)
        }
        val daily = JSONArray(perDay.map { (day, minutes) ->
            JSONObject().put("date", day.toString()).put("minutes", minutes)
        })
        val categories = JSONObject()
        val categoryTotals = selected.groupBy(TimeRecord::category).toSortedMap().mapValues { (_, values) ->
            values.sumOf(TimeRecord::durationMinutes)
        }
        categoryTotals.forEach(categories::put)
        val categoryShares = JSONObject()
        categoryTotals.forEach { (category, minutes) ->
            categoryShares.put(category, if (total == 0L) 0L else minutes * 10_000L / total)
        }
        val target = when (range) { LearningRange.TODAY -> 240L; LearningRange.WEEK -> 1_680L; LearningRange.MONTH -> 7_200L }
        val goalRateBasisPoints = if (target == 0L) 0L else total * 10_000L / target
        val activeDays = perDay.count { (_, minutes) -> minutes > 0L }
        val currentStreakDays = com.campusai.core.model.TimeRecordCalendar.streak(records, Instant.ofEpochMilli(nowMillis), zone)
        val peak = perDay.maxByOrNull { (_, minutes) -> minutes }?.takeIf { (_, minutes) -> minutes > 0L }
        val trend = computeTrend(perDay.map { (_, minutes) -> minutes })
        val recentRecords = selected.sortedWith(compareByDescending<TimeRecord> { it.endTime }.thenByDescending { it.startTime }).take(6)
        val subjectNames = recentRecords.map(TimeRecord::title).filter(String::isNotBlank).distinct()
        val allowedActionSubjects = JSONArray(subjectNames)
        val recentEntries = JSONArray(recentRecords.map { record ->
            JSONObject()
                .put("title", record.title.take(80))
                .put("category", record.category.take(40))
                .put("durationMinutes", record.durationMinutes)
                .put("date", Instant.ofEpochMilli(record.endTime).atZone(zone).toLocalDate().toString())
        })
        val remainingTarget = (target - total).coerceAtLeast(0L)
        val fallbackSubject = when (range) {
            LearningRange.TODAY -> "今天最重要的任务"
            LearningRange.WEEK -> "下周最重要的任务"
            LearningRange.MONTH -> "下月最重要的任务"
        }
        val actionDurations = when {
            remainingTarget == 0L -> listOf(25L)
            remainingTarget <= 65L -> listOf(remainingTarget.coerceAtMost(50L))
            else -> listOf(50L, 50L)
        }
        val actionBlocks = JSONArray(actionDurations.mapIndexed { index, duration ->
            JSONObject()
                .put("sequence", index + 1)
                .put("subject", subjectNames.getOrNull(index) ?: subjectNames.firstOrNull() ?: fallbackSubject)
                .put("durationMinutes", duration)
        })
        val coveredMinutes = actionDurations.sum()
        val suggestedActionPlan = JSONObject()
            .put("purpose", "next_step_not_full_schedule")
            .put("blocks", actionBlocks)
            .put("breakMinutesBetweenBlocks", if (actionDurations.size > 1) 10L else 0L)
            .put("coveredMinutes", coveredMinutes)
            .put("remainingAfterPlanMinutes", (remainingTarget - coveredMinutes).coerceAtLeast(0L))
        val rangeLabel = when (range) {
            LearningRange.TODAY -> "今日"
            LearningRange.WEEK -> "本周"
            LearningRange.MONTH -> "本月"
        }
        val topCategory = categoryTotals.maxByOrNull { (_, minutes) -> minutes }
        val analysisStatements = JSONArray().apply {
            put("${rangeLabel}已记录${total}分钟，目标${target}分钟，剩余${remainingTarget}分钟。")
            put("${rangeLabel}共有${selected.size}条时间记录，活跃${activeDays}天，当前连续记录${currentStreakDays}天。")
            if (topCategory != null && total > 0L) {
                val (category, minutes) = topCategory
                put("投入最多的分类是${category}，共${minutes}分钟，占比基点为${minutes * 10_000L / total}。")
            } else {
                put("${rangeLabel}暂无可分析的分类记录。")
            }
            when (trend.direction) {
                "up" -> put("后半段日均比前半段增加${trend.deltaAverageMinutes}分钟。")
                "down" -> put("后半段日均比前半段减少${-trend.deltaAverageMinutes}分钟。")
                "flat" -> put("前后半段日均分钟相同。")
                else -> if (range != LearningRange.TODAY) put("当前自然日数量不足，不能判断投入趋势。")
            }
        }
        return JSONObject()
            .put("range", range.name.lowercase())
            .put("rangeLabel", rangeLabel)
            .put("startDate", startDate.toString())
            .put("endDate", today.toString())
            .put("recordCount", selected.size)
            .put("totalMinutes", total)
            .put("targetMinutes", target)
            .put("remainingTargetMinutes", remainingTarget)
            .put("goalRateBasisPoints", goalRateBasisPoints)
            .put("goalStatus", if (total >= target) "met" else "in_progress")
            .put("activeDays", activeDays)
            .put("currentStreakDays", currentStreakDays)
            .put("longestSessionMinutes", selected.maxOfOrNull(TimeRecord::durationMinutes) ?: 0L)
            .put("peakDate", peak?.first?.toString() ?: JSONObject.NULL)
            .put("peakMinutes", peak?.second ?: 0L)
            .put("trendDirection", trend.direction)
            .put("trendDeltaAverageMinutes", trend.deltaAverageMinutes)
            .put("earlierAverageMinutes", trend.earlierAverageMinutes)
            .put("laterAverageMinutes", trend.laterAverageMinutes)
            .put("allowedActionSubjects", allowedActionSubjects)
            .put("recentEntries", recentEntries)
            .put("suggestedActionPlan", suggestedActionPlan)
            .put("analysisStatements", analysisStatements)
            .put("categoryMinutes", categories)
            .put("categoryShareBasisPoints", categoryShares)
            .put("dailyMinutes", daily)
    }

    private data class TrendFacts(
        val direction: String,
        val deltaAverageMinutes: Long,
        val earlierAverageMinutes: Long,
        val laterAverageMinutes: Long,
    )

    private fun computeTrend(minutes: List<Long>): TrendFacts {
        if (minutes.size < 2) return TrendFacts("insufficient_data", 0L, 0L, 0L)
        val split = minutes.size / 2
        val earlier = minutes.take(split)
        val later = minutes.drop(split)
        if (earlier.isEmpty() || later.isEmpty()) return TrendFacts("insufficient_data", 0L, 0L, 0L)
        val earlierAverage = earlier.sum() / earlier.size
        val laterAverage = later.sum() / later.size
        val delta = laterAverage - earlierAverage
        return TrendFacts(
            direction = when {
                delta > 0L -> "up"
                delta < 0L -> "down"
                else -> "flat"
            },
            deltaAverageMinutes = delta,
            earlierAverageMinutes = earlierAverage,
            laterAverageMinutes = laterAverage,
        )
    }

    private fun scheduleFacts(courses: List<CourseSchedule>): JSONObject {
        val rows = JSONArray(courses.map { course -> JSONObject()
            .put("name", course.name).put("weekday", course.weekday)
            .put("startMinute", course.startMinute).put("endMinute", course.endMinute)
            .put("location", course.location).put("teacher", course.teacher).put("weeks", course.weeks)
        })
        val conflicts = JSONArray(com.campusai.features.schedule.potentialCourseOverlaps(courses).map { (first, second) ->
            JSONObject().put("first", first.name).put("second", second.name).put("weekday", first.weekday)
                .put("requiresDateReview", true).put("basis", "time_overlap_only")
        })
        return JSONObject().put("courses", rows).put("computedConflicts", conflicts)
    }

    private fun parseTimeFacts(input: String, nowMillis: Long, zone: ZoneId): JSONObject {
        val number = "[零一二两三四五六七八九十百\\d]{1,4}"
        val hours = Regex("($number)\\s*(?:小时|h)").find(input)?.groupValues?.get(1)?.let(::parseChineseNumber) ?: 0L
        val minutes = Regex("($number)\\s*(?:分钟|分|min)").find(input)?.groupValues?.get(1)?.let(::parseChineseNumber) ?: 0L
        val duration = (hours * 60 + minutes).takeIf { it > 0 }
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        return JSONObject()
            .put("sourceText", input.take(500))
            .put("anchorTime", now.toOffsetDateTime().toString())
            .put("durationMinutes", duration ?: JSONObject.NULL)
            .put("startsNow", Regex("(?:从)?现在开始|刚才").containsMatchIn(input))
            .put("requiresConfirmation", duration == null)
    }

    private fun parseChineseNumber(raw: String): Long? {
        raw.toLongOrNull()?.let { return it }
        val digits = mapOf('零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        var total = 0L
        var current = 0L
        raw.forEach { char -> when (char) {
            '十' -> { total += (if (current == 0L) 1L else current) * 10L; current = 0 }
            '百' -> { total += (if (current == 0L) 1L else current) * 100L; current = 0 }
            else -> current = digits[char]?.toLong() ?: return null
        } }
        return total + current
    }

    private fun JSONObject.toPresentation(task: CampusAiTask): AiTaskPresentation? {
        if (task !in setOf(
                CampusAiTask.HOME_INSIGHT,
                CampusAiTask.TODAY_SUMMARY,
                CampusAiTask.WEEK_SUMMARY,
                CampusAiTask.MONTH_SUMMARY,
                CampusAiTask.STRUCTURED_ADVICE,
            )
        ) return null
        val plan = optJSONObject("suggestedActionPlan") ?: return null
        val blocks = plan.optJSONArray("blocks") ?: JSONArray()
        return AiTaskPresentation(
            headline = "${optString("rangeLabel", "当前")}目标还差 ${optLong("remainingTargetMinutes")} 分钟",
            actionBlocks = buildList {
                repeat(blocks.length()) { index ->
                    blocks.optJSONObject(index)?.let { block ->
                        add(
                            AiActionBlock(
                                subject = block.optString("subject", "下一项任务"),
                                durationMinutes = block.optLong("durationMinutes"),
                            ),
                        )
                    }
                }
            },
            breakMinutes = plan.optLong("breakMinutesBetweenBlocks"),
            remainingAfterPlanMinutes = plan.optLong("remainingAfterPlanMinutes"),
        )
    }
}
