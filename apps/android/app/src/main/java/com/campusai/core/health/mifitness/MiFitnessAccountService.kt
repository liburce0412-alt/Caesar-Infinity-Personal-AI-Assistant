package com.campusai.core.health.mifitness

import android.content.Context
import kotlinx.coroutines.CancellationException

class MiFitnessAccountException(
    val code: String,
    message: String,
) : IllegalStateException(message) {
    override fun toString(): String = "MiFitnessAccountException(code=$code)"
}

class MiFitnessAccountService internal constructor(
    private val credentialStore: MiFitnessCredentialStore,
    private val cache: MiFitnessStepsCache,
    private val syncService: MiFitnessStepsSyncService,
) {
    constructor(context: Context) : this(
        credentialStore = MiFitnessCredentialStore(context),
        cache = MiFitnessStepsCache(context),
        syncService = MiFitnessStepsSyncService(context),
    )

    suspend fun validateAndSave(
        userId: String,
        passToken: String,
    ): Result<MiFitnessStepsSummary> {
        val normalizedUserId = userId.trim()
        val normalizedPassToken = passToken.trim()
        MiFitnessCredentialStore.validationError(normalizedUserId, normalizedPassToken)?.let { message ->
            return Result.failure(MiFitnessAccountException("invalid_credentials", message))
        }
        val candidate = MiFitnessCredential(normalizedUserId, normalizedPassToken)

        return MiFitnessStepsSyncService.serialized {
            try {
                val window = syncService.todayWindow()
                val oldCredential = credentialStore.read()
                val oldSummary = oldCredential?.let { credential ->
                    cache.read(window.period, window.localDate, credential.accountScope)
                }
                val outcome = syncService.syncTodayOutcomeLocked(candidate, window).getOrElse { error ->
                    return@serialized Result.failure(safeSyncError(error))
                }
                val passTokenToSave = outcome.refreshedPassToken ?: normalizedPassToken

                val credentialSaved = try {
                    credentialStore.save(normalizedUserId, passTokenToSave).isSuccess
                } catch (_: Exception) {
                    false
                }
                if (!credentialSaved) {
                    if (outcome.summary != null) restoreCache(oldSummary)
                    return@serialized Result.failure(
                        MiFitnessAccountException("credential_write_failed", "系统安全存储不可用，小米运动健康凭据未保存。"),
                    )
                }
                val summary = outcome.summary
                    ?: return@serialized Result.failure(
                        MiFitnessStepsSyncException(
                            "no_cloud_data",
                            "Mi Fitness 云端尚未返回今天的步数记录。",
                        ),
                    )
                MiFitnessStepsSyncService.notifyCacheUpdated()
                Result.success(summary)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Result.failure(MiFitnessAccountException("account_save_failed", "小米运动健康账号未保存。"))
            }
        }
    }

    suspend fun delete(): Result<Unit> = MiFitnessStepsSyncService.serialized {
        try {
            val window = syncService.todayWindow()
            val oldCredential = credentialStore.read()
            val oldSummary = oldCredential?.let { credential ->
                cache.read(window.period, window.localDate, credential.accountScope)
            }
            if (!cache.delete()) {
                return@serialized Result.failure(
                    MiFitnessAccountException("cache_delete_failed", "小米运动健康本地摘要未删除。"),
                )
            }
            if (!credentialStore.delete()) {
                restoreCache(oldSummary)
                return@serialized Result.failure(
                    MiFitnessAccountException("credential_delete_failed", "小米运动健康凭据未删除。"),
                )
            }
            MiFitnessStepsSyncService.notifyCacheUpdated()
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.failure(MiFitnessAccountException("account_delete_failed", "小米运动健康账号未删除。"))
        }
    }

    private fun restoreCache(summary: MiFitnessStepsSummary?) {
        try {
            if (summary == null) cache.delete() else cache.save(summary)
        } catch (_: Exception) {
            // The returned account error stays generic and never includes stored payloads.
        }
    }

    private fun safeSyncError(error: Throwable): Throwable = when (error) {
        is MiFitnessStepsSyncException -> error
        else -> MiFitnessAccountException("validation_failed", "小米运动健康账号验证失败。")
    }
}
