package com.campusai.core.network

import com.campusai.core.ai.AiRequest
import com.campusai.core.ai.AiRoutingException
import org.json.JSONArray
import org.json.JSONObject

/** Protocol v1: server-owned schemas; App-owned permission, confirmation and execution. */
internal object CaesarCloudFreeTools {
    const val PROTOCOL = 1
    val allowedNames = setOf(
        "web.search", "time.list_records", "time.create_record", "time.edit_record",
        "time.delete_record", "time.undo_delete", "course.list", "memory.propose",
    )
    private val idPattern = Regex("[A-Za-z0-9_.:-]{1,200}")
    private const val MAX_ARGUMENTS = 4096
    private const val MAX_RESULT = 8000
    private const val MAX_REPLAY = 32000

    fun projectedNames(request: AiRequest): List<String> {
        if (request.imagePaths.isNotEmpty()) return emptyList()
        val schema = runCatching { JSONArray(request.caesarToolsJson) }.getOrNull() ?: return emptyList()
        return (0 until schema.length()).mapNotNull { schema.optJSONObject(it)?.optString("name") }
            .filter { it in allowedNames && (it != "web.search" || request.allowWebSearch) }.distinct()
    }

    fun currentTurnReplay(request: AiRequest): List<JSONObject> {
        val lastUser = request.messages.indexOfLast { it.role == "user" }
        if (lastUser < 0 || lastUser == request.messages.lastIndex) return emptyList()
        if (request.imagePaths.isNotEmpty()) throw invalid()
        val tail = request.messages.drop(lastUser + 1)
        if (tail.size % 2 != 0 || tail.size > 24) throw invalid()
        var size = 0
        val seen = mutableSetOf<String>()
        return tail.chunked(2).flatMap { (assistant, result) ->
            if (assistant.role != "assistant" || result.role != "tool" || assistant.cloudHealthSensitive || result.cloudHealthSensitive) throw invalid()
            val call = ProviderToolCallEnvelope.decode(assistant.content) ?: throw invalid()
            validateCall(call, allowedNames)
            if (!seen.add(call.id) || result.content.isBlank() || result.content.length > MAX_RESULT) throw invalid()
            // This provider never needs another provider's hidden reasoning or signatures.
            val wireAssistant = ProviderToolCallEnvelope.toAssistantMessage(call.copy(thoughtSignature = null, reasoningContent = null))
            size += wireAssistant.toString().length + result.content.length
            if (size > MAX_REPLAY) throw tooLarge()
            listOf(wireAssistant, JSONObject().put("role", "tool").put("name", call.wireName)
                .put("tool_call_id", call.id).put("content", result.content))
        }
    }

    fun decodeCall(event: JSONObject, names: Set<String>, assistantContent: String): OpenAiProviderToolCall {
        val call = OpenAiProviderToolCall(
            id = event.getString("id"), wireName = event.getString("wireName"), originalName = event.getString("name"),
            argumentsJson = event.getString("arguments"), thoughtSignature = null, assistantContent = assistantContent,
        )
        validateCall(call, names)
        return call
    }

    private fun validateCall(call: OpenAiProviderToolCall, names: Set<String>) {
        if (call.originalName !in allowedNames || call.originalName !in names || !idPattern.matches(call.id) ||
            call.wireName != call.originalName.replace('.', '_') || call.argumentsJson.length > MAX_ARGUMENTS ||
            runCatching { JSONObject(call.argumentsJson) }.isFailure) throw invalid()
    }

    private fun invalid() = AiRoutingException("invalid_tool_protocol", "工具调用不符合当前协议，本轮已安全停止。")
    private fun tooLarge() = AiRoutingException("request_too_large", "工具结果过长，请缩小查询范围后重试。")
}
