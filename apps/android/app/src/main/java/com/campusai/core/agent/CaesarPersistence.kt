package com.campusai.core.agent

import com.campusai.core.database.AgentActionEntity
import com.campusai.core.database.AgentMemoryEntity
import com.campusai.core.database.AgentTraceEntity
import com.campusai.core.database.CampusDao
import java.security.MessageDigest
import java.util.UUID

class CaesarMemoryStore(private val dao: CampusDao) {
    suspend fun propose(
        type: String,
        content: String,
        source: String,
        confidence: Double,
        expiresAt: Long? = null,
        ownerUserId: String = "local_user",
    ): CaesarMemoryProposal {
        require(type in TYPES) { "Unsupported memory type" }
        require(content.isNotBlank() && content.length <= 1_000) { "Invalid memory content" }
        val proposal = CaesarMemoryProposal(
            id = UUID.randomUUID().toString(),
            type = type,
            content = content.trim(),
            source = source.take(120),
            confidence = confidence.coerceIn(0.0, 1.0),
            expiresAt = expiresAt,
        )
        dao.insertAgentMemory(
            AgentMemoryEntity(
                id = proposal.id,
                type = proposal.type,
                content = proposal.content,
                source = proposal.source,
                confidence = proposal.confidence,
                createdAt = System.currentTimeMillis(),
                confirmedAt = null,
                expiresAt = proposal.expiresAt,
                ownerUserId = ownerUserId.ifBlank { "local_user" },
            ),
        )
        return proposal
    }

    suspend fun confirm(id: String, ownerUserId: String = "local_user"): Boolean =
        dao.confirmAgentMemory(id, ownerUserId = ownerUserId.ifBlank { "local_user" }) == 1
    suspend fun updateContent(id: String, content: String, ownerUserId: String = "local_user"): Boolean {
        val normalized = content.trim()
        require(normalized.isNotBlank() && normalized.length <= 1_000) { "Invalid memory content" }
        return dao.updateAgentMemoryContent(id, normalized, ownerUserId.ifBlank { "local_user" }) == 1
    }
    suspend fun forget(id: String, ownerUserId: String = "local_user"): Boolean =
        dao.deleteAgentMemory(id, ownerUserId.ifBlank { "local_user" }) == 1
    suspend fun forgetAll(ownerUserId: String = "local_user") = dao.deleteAllAgentMemories(ownerUserId.ifBlank { "local_user" })

    suspend fun context(ownerUserId: String = "local_user", now: Long = System.currentTimeMillis()): List<CaesarMemoryProposal> =
        dao.getConfirmedAgentMemories(now, ownerUserId = ownerUserId.ifBlank { "local_user" }).map { row ->
            CaesarMemoryProposal(row.id, row.type, row.content, row.source, row.confidence, row.expiresAt)
        }

    private companion object { val TYPES = setOf("preference", "fact", "goal", "routine") }
}

class RoomCaesarTraceSink(private val dao: CampusDao) : CaesarTraceSink {
    override suspend fun record(event: CaesarTraceEvent) {
        dao.insertAgentTrace(
            AgentTraceEntity(
                id = UUID.randomUUID().toString(),
                sessionId = event.sessionId,
                kind = event.kind.take(40),
                name = event.name.take(120),
                durationMs = event.durationMs.coerceAtLeast(0),
                success = event.success,
                errorCode = event.errorCode?.take(80),
                createdAt = System.currentTimeMillis(),
                ownerUserId = event.ownerUserId.ifBlank { "local_user" },
            ),
        )
    }
}

class CaesarIdempotencyStore(private val dao: CampusDao) {
    suspend fun completed(key: String): String? = dao.getAgentAction(key)
        ?.takeIf { it.status == "completed" }
        ?.resultJson

    suspend fun begin(key: String, toolName: String, argumentsJson: String): Boolean {
        val now = System.currentTimeMillis()
        val argumentsHash = sha256(argumentsJson)
        val inserted = dao.insertAgentActionIfAbsent(
            AgentActionEntity(
                idempotencyKey = key,
                toolName = toolName,
                argumentsHash = argumentsHash,
                status = "running",
                resultJson = null,
                createdAt = now,
                updatedAt = now,
            ),
        )
        if (inserted != -1L) return true
        return dao.restartFailedAgentAction(key, toolName, argumentsHash, now) == 1
    }

    suspend fun complete(key: String, toolName: String, argumentsJson: String, resultJson: String) {
        dao.completeRunningAgentAction(key, resultJson, System.currentTimeMillis())
    }

    suspend fun fail(key: String, toolName: String, argumentsJson: String) {
        dao.failRunningAgentAction(key, System.currentTimeMillis())
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
