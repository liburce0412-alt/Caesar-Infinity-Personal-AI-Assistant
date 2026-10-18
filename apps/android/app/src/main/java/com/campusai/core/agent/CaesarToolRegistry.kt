package com.campusai.core.agent

import java.security.MessageDigest
import kotlin.math.floor
import org.json.JSONArray
import org.json.JSONObject

class CaesarTool(
    val definition: ToolDefinition,
    val execute: suspend (JSONObject, ToolExecutionContext) -> CaesarToolResult,
)

class CaesarToolRegistry(tools: List<CaesarTool>) {
    private val byName = tools.associateBy { it.definition.name }

    init {
        require(byName.size == tools.size) { "Duplicate Caesar tool name" }
        require(byName.keys.all { it.matches(Regex("[a-z][a-z0-9_.]{2,80}")) })
        tools.forEach { tool ->
            val parameters = tool.definition.parameters
            require(parameters.map(ToolParameter::name).distinct().size == parameters.size) { "Duplicate tool parameter" }
            require(parameters.all { it.name.matches(Regex("[a-zA-Z][a-zA-Z0-9_]{0,63}")) }) { "Invalid tool parameter name" }
            require(parameters.all { it.type in PARAMETER_TYPES }) { "Unsupported tool parameter type" }
            require(parameters.all { it.maxLength == null || it.maxLength > 0 }) { "Invalid tool parameter length" }
        }
    }

    val definitions: List<ToolDefinition> = tools.map(CaesarTool::definition)

    fun project(userPrompt: String, limit: Int = 12): List<ToolDefinition> {
        val normalized = userPrompt.lowercase()
        return definitions.filter { definition ->
            definition.keywords.any(normalized::contains) || normalized.contains(definition.name.substringAfterLast('.'))
        }
            .take(limit)
    }

    fun promptSchema(projected: List<ToolDefinition>): String = JSONArray(projected.map { definition ->
        JSONObject()
            .put("name", definition.name)
            .put("description", definition.description)
            .put("risk", definition.riskLevel.name.lowercase())
            .put("parameters", JSONArray(definition.parameters.map { parameter ->
                JSONObject()
                    .put("name", parameter.name)
                    .put("type", parameter.type)
                    .put("required", parameter.required)
                    .put("description", parameter.description)
            }))
    }).toString()

    suspend fun execute(name: String, arguments: JSONObject, context: ToolExecutionContext): CaesarToolResult {
        val tool = byName[name] ?: return CaesarToolResult.Denied("unknown_tool", "Caesar∞ 没有注册这个工具。")
        validate(tool.definition, arguments)?.let { return CaesarToolResult.Denied("invalid_arguments", it) }
        if (context.autonomyMode == AutonomyMode.READ_ONLY && tool.definition.riskLevel != ToolRiskLevel.READ_ONLY) {
            return CaesarToolResult.Denied("read_only_mode", "当前处于只读模式。")
        }
        val mustConfirm = !context.confirmationGranted && (tool.definition.riskLevel == ToolRiskLevel.IRREVERSIBLE ||
            (tool.definition.riskLevel == ToolRiskLevel.EXTERNAL_SIDE_EFFECT &&
                (context.autonomyMode == AutonomyMode.CONFIRM_EXTERNAL || !context.explicitUserIntent)))
        if (mustConfirm) {
            return CaesarToolResult.NeedsConfirmation(
                title = "确认执行 ${tool.definition.name}",
                description = "该操作会产生外部或不可逆影响。",
                actionId = "confirm:${context.idempotencyKey}",
            )
        }
        return tool.execute(arguments, context)
    }

    private fun validate(definition: ToolDefinition, arguments: JSONObject): String? {
        val allowed = definition.parameters.map(ToolParameter::name).toSet()
        val keys = arguments.keys().asSequence().toSet()
        if ((keys - allowed).isNotEmpty()) return "包含未声明参数：${(keys - allowed).joinToString()}"
        definition.parameters.forEach { parameter ->
            if (parameter.required && (!arguments.has(parameter.name) || arguments.isNull(parameter.name))) return "缺少参数 ${parameter.name}"
            if (!arguments.has(parameter.name) || arguments.isNull(parameter.name)) return@forEach
            val value = arguments.opt(parameter.name)
            val typeMatches = when (parameter.type) {
                "string" -> value is String
                "integer" -> value is Number && value.toDouble().isFinite() && floor(value.toDouble()) == value.toDouble()
                "number" -> value is Number && value.toDouble().isFinite()
                "boolean" -> value is Boolean
                "object" -> value is JSONObject
                "array" -> value is JSONArray
                else -> false
            }
            if (!typeMatches) return "参数 ${parameter.name} 应为 ${parameter.type}"
            if (value is String && parameter.maxLength != null && value.length > parameter.maxLength) return "参数 ${parameter.name} 过长"
            if ((value is JSONObject || value is JSONArray) && value.toString().length > MAX_STRUCTURED_PARAMETER_CHARS) {
                return "参数 ${parameter.name} 过大"
            }
        }
        return null
    }

    private companion object {
        val PARAMETER_TYPES = setOf("string", "integer", "number", "boolean", "object", "array")
        const val MAX_STRUCTURED_PARAMETER_CHARS = 16_384
    }
}

object CaesarIntentEvidence {
    private val actionWords = setOf("添加", "创建", "记录", "删除", "撤销", "编辑", "修改", "发布", "发送", "举报", "更新", "收藏", "点赞", "导入", "开始", "停止")

    fun isExplicit(prompt: String, toolName: String): Boolean {
        if (prompt.isBlank()) return false
        val domain = toolName.substringBefore('.')
        return actionWords.any(prompt::contains) && when (domain) {
            "community" -> listOf("帖子", "评论", "点赞", "收藏", "举报").any(prompt::contains)
            "market" -> listOf("心愿", "心愿墙", "收藏").any(prompt::contains)
            "message" -> listOf("消息", "发送", "回复").any(prompt::contains)
            "time" -> listOf("时间", "记录", "专注").any(prompt::contains)
            "profile" -> listOf("资料", "昵称", "简介").any(prompt::contains)
            "health" -> listOf("健康", "心率", "步数", "睡眠", "运动").any(prompt::contains)
            else -> true
        }
    }

    fun idempotencyKey(
        sessionId: String,
        toolName: String,
        argumentsJson: String,
        ownerUserId: String = "local_user",
        actionScope: String = "",
    ): String {
        val canonicalArguments = runCatching { canonicalArguments(JSONObject(argumentsJson)) }
            .getOrDefault(argumentsJson.trim())
        // Keep pre-turn-scope callers and historical rows intact. Agent turns use a separate
        // namespace, so a later explicit action cannot replay an earlier turn's cached success.
        val owner = ownerUserId.ifBlank { "local_user" }
        val identity = if (actionScope.isBlank()) {
            "caesar-action-v2\u0000$owner\u0000$sessionId\u0000$toolName\u0000$canonicalArguments"
        } else {
            "caesar-action-v3\u0000$owner\u0000$sessionId\u0000$actionScope\u0000$toolName\u0000$canonicalArguments"
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray())
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    fun canonicalArguments(arguments: JSONObject): String = canonicalValue(arguments)

    private fun canonicalValue(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { key ->
            "${JSONObject.quote(key)}:${canonicalValue(value.opt(key))}"
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { index -> canonicalValue(value.opt(index)) }
        is String -> JSONObject.quote(value)
        is Boolean -> value.toString()
        is Number -> runCatching { JSONObject.numberToString(value) }.getOrElse { JSONObject.quote(value.toString()) }
        else -> JSONObject.quote(value.toString())
    }
}
