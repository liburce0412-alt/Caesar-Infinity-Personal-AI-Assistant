package com.campusai.core.network

import com.campusai.core.agent.CaesarAgentEngine
import com.campusai.core.agent.CaesarTool
import com.campusai.core.agent.CaesarToolRegistry
import com.campusai.core.agent.ToolDefinition
import com.campusai.core.agent.ToolRiskLevel
import com.campusai.core.ai.AiEvent
import com.campusai.core.ai.AiRequest
import com.campusai.core.ai.AiRoutingException
import com.campusai.core.ai.CloudDailyHealthSummary
import com.campusai.core.ai.CloudHealthDisclosure
import com.campusai.core.model.AiMode
import com.campusai.core.model.CourseSchedule
import com.campusai.core.model.TimeRecord
import com.campusai.core.time.CaesarTime
import com.campusai.features.ai.AiContextAssembler
import com.campusai.features.ai.AiContextSelection
import com.campusai.features.ai.AiContextSnapshot
import com.campusai.features.ai.AiPostContext
import com.campusai.features.ai.CampusAiTask
import com.campusai.features.ai.CampusAiTaskFactory
import com.campusai.features.ai.assembleCaesarTurnRequest
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CaesarCloudContextBudgetTest {
    private val testTime = ZonedDateTime.now(CaesarTime.zone).withHour(12).withMinute(0).withSecond(0).withNano(0)
    private val now = testTime.toInstant().toEpochMilli()

    @Test fun `ordinary eleven course query reaches free engine with tools and no unrelated records`() = runTest {
        val courses = (1..11).map { course(it).copy(weekday = it % 7 + 1, startMinute = 480 + it * 30) }
        val input = request("查询课程", courses, records(4))
        val payload = sendThroughAgent(input)
        val personal = payload.getJSONObject("context").getJSONObject("personalContext")
        val projected = personal.getJSONObject("courses")
        assertEquals(11, projected.getJSONArray("items").length())
        assertEquals(11, projected.getInt("itemsTotalCount"))
        assertEquals(0, projected.getInt("itemsOmittedCount"))
        assertFalse(personal.has("learning"))
        assertEquals("course.list", payload.getJSONArray("tools").getString(0))
        assertFalse(payload.getJSONObject("context").has("caesarToolsJson"))
    }

    @Test fun `many overlapping raw courses keep exact coverage counts and complete bounded entries`() = runTest {
        val courses = (1..80).map { course(it).copy(name = "课程名\\\"".repeat(24) + it) }
        val input = request("查询课程", courses, records(4))
        val before = JSONObject(input.structuredContextJson).getJSONObject("personalContext").getJSONObject("courses")
        val payload = sendThroughAgent(input)
        val context = payload.getJSONObject("context")
        val projected = context.getJSONObject("personalContext").getJSONObject("courses")
        assertTrue(context.toString().length <= 10_000)
        assertEquals(80, projected.getInt("itemsTotalCount"))
        assertEquals(80 * 79 / 2, projected.getInt("computedConflictsTotalCount"))
        for (key in listOf("items", "computedConflicts")) {
            val included = projected.getJSONArray(key).length()
            assertTrue(included > 0)
            assertEquals(included, projected.getInt("${key}IncludedCount"))
            assertEquals(projected.getInt("${key}TotalCount") - included, projected.getInt("${key}OmittedCount"))
            assertTrue(projected.getInt("${key}OmittedCount") > 0)
        }
        assertTrue(projected.getBoolean("scheduleDateReviewRequired"))
        val overlap = projected.getJSONArray("computedConflicts").getJSONObject(0)
        assertTrue(overlap.getBoolean("requiresDateReview"))
        assertEquals("time_overlap_only", overlap.getString("basis"))
        assertEquals(before.getJSONArray("items").getJSONObject(0).toString(), projected.getJSONArray("items").getJSONObject(0).toString())
        assertEquals(80, courses.size)
    }

    @Test fun `combined optional details fit while summaries clock consent and unknown facts stay intact`() = runTest {
        val courses = (1..40).map { course(it).copy(name = "课程".repeat(40), location = "教室".repeat(40), teacher = "老师".repeat(24), weeks = "周".repeat(80)) }
        val posts = (1..10).map { AiPostContext("post-$it", "owner", "帖".repeat(240), "话题", 0, 0, testTime.toLocalDate().toString()) }
        val input = request("分析本周学习时间与课程，还有我的帖子", courses, records(12), posts)
        val original = JSONObject(input.structuredContextJson)
            .put("requiredTaskFacts", JSONObject().put("confirmedMinutes", 720).put("explanation", "f".repeat(1_500)))
            .put("confirmationParameters", JSONObject().put("id", 19).put("operation", "keep"))
            .put("health", JSONObject().put("deviceId", "private-device"))
            .put("confirmedMemories", "private-memory")
        val factsBefore = original.getJSONObject("personalContext").getJSONObject("learning")
        assertTrue(original.toString().length > 10_000)
        val payload = sendThroughAgent(input.copy(structuredContextJson = original.toString(),
            cloudHealthDisclosure = CloudHealthDisclosure.Included(CloudDailyHealthSummary(testTime.toLocalDate().toString(), steps = 1234))))
        val context = payload.getJSONObject("context")
        assertTrue(context.toString().length <= 12_000)
        assertTrue(context.has("clock"))
        assertEquals("Asia/Shanghai", context.getJSONObject("clock").getString("timezone"))
        val personal = context.getJSONObject("personalContext")
        for (range in listOf("today", "week", "month", "allTime")) {
            assertEquals(factsBefore.getJSONObject(range).toString(), personal.getJSONObject("learning").getJSONObject(range).toString())
        }
        assertEquals(original.getJSONObject("requiredTaskFacts").toString(), context.getJSONObject("requiredTaskFacts").toString())
        assertEquals(original.getJSONObject("confirmationParameters").toString(), context.getJSONObject("confirmationParameters").toString())
        assertEquals(1234, context.getJSONObject("consentedDailyHealthSummary").getInt("steps"))
        assertFalse(context.toString().contains("private-device"))
        assertFalse(context.toString().contains("private-memory"))
        val overlaps = personal.getJSONObject("courses")
        val originalOverlaps = original.getJSONObject("personalContext").getJSONObject("courses")
        assertEquals(40 * 39 / 2, overlaps.getInt("computedConflictsTotalCount"))
        assertEquals(overlaps.getInt("computedConflictsTotalCount"),
            overlaps.getInt("computedConflictsIncludedCount") + overlaps.getInt("computedConflictsOmittedCount"))
        assertTrue(overlaps.getInt("computedConflictsOmittedCount") > originalOverlaps.getInt("computedConflictsOmittedCount"))
        assertTrue(personal.getInt("ownPostsOmittedCount") > 0)
    }

    @Test fun `irreducible task facts or unknown fields still fail before token or network access`() = runTest {
        for (key in listOf("learningFacts", "confirmationParameters", "unknownEvidence")) {
            val input = request("查询课程", (1..40).map(::course))
            val original = JSONObject(input.structuredContextJson).put(key, JSONObject().put("value", "x".repeat(12_001)))
            var tokenReads = 0
            var networkCalls = 0
            val client = OkHttpClient.Builder().addInterceptor { networkCalls++; error("must not send") }.build()
            val engine = CaesarCloudFreeEngine({ "https://backend.example" }, { tokenReads++; "token" }, { true }, client)
            val events = engine.stream(input.copy(structuredContextJson = original.toString())).toList()
            assertEquals("request_too_large", (events.single() as AiEvent.Error).code)
            assertEquals(0, tokenReads)
            assertEquals(0, networkCalls)
            assertEquals(12_001, original.getJSONObject(key).getString("value").length)
        }
    }

    @Test fun `optional projection never changes local only boundary`() {
        try {
            cloudFreePayload(request("查询课程", (1..40).map(::course)).copy(requiresLocal = true))
            fail("Local requests must remain local")
        } catch (failure: AiRoutingException) { assertEquals("local_required", failure.code) }
    }

    private suspend fun sendThroughAgent(input: AiRequest): JSONObject {
        var sent: JSONObject? = null
        var owner: String? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            sent = JSONObject(buffer.readUtf8())
            val events = listOf(
                """{"type":"meta","model":"glm-4-flash-250414","requestId":"context-test","toolProtocol":1}""",
                """{"type":"delta","text":"已读取课程依据"}""",
                """{"type":"done","elapsedMs":1}""",
            )
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "text/event-stream")
                .body(events.joinToString("") { "data: $it\n\n" }.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val free = CaesarCloudFreeEngine({ "https://backend.example" }, { owner = it; "test-token" }, { true }, client)
        val registry = CaesarToolRegistry(listOf(CaesarTool(ToolDefinition("course.list", "工具说明".repeat(4_000),
            emptyList(), ToolRiskLevel.READ_ONLY, keywords = setOf("课程"))) { _, _ -> error("No tool call in this response") }))
        val events = CaesarAgentEngine(free, registry).stream(input).toList()
        assertTrue(events.toString(), events.first() is AiEvent.Meta)
        assertTrue(events.toString(), events.last() is AiEvent.Done)
        assertEquals("owner", owner)
        return requireNotNull(sent)
    }

    private fun request(prompt: String, courses: List<CourseSchedule>, records: List<TimeRecord> = emptyList(), posts: List<AiPostContext> = emptyList()): AiRequest {
        val base = CampusAiTaskFactory.create(CampusAiTask.CHAT, records, courses, prompt, now)
        val context = AiContextAssembler.enrich(base.structuredContext, CampusAiTask.CHAT, prompt,
            AiContextSnapshot(userId = "owner", courses = courses, records = records, visiblePosts = posts), AiContextSelection(), now)
        return assembleCaesarTurnRequest(AiMode.FAST, emptyList(), prompt, prompt, context, emptyList(), "session", "owner", "")
            .copy(allowedToolNames = setOf("course.list"))
    }

    private fun course(id: Int) = CourseSchedule(id = id, name = "课程$id", weekday = 1, startMinute = 480, endMinute = 570,
        location = "第一教学楼", teacher = "老师", weeks = "第1至20周", sourceHash = "source-$id")

    private fun records(count: Int) = (1..count).map { index ->
        TimeRecord(title = "学习项目".repeat(20), category = "学习", startTime = now - index * 3_600_000L,
            endTime = now - (index - 1) * 3_600_000L, durationMinutes = 60, remark = "")
    }
}
