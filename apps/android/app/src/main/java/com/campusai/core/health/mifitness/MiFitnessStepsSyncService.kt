package com.campusai.core.health.mifitness

import com.campusai.core.time.CaesarTime

import android.content.Context
import com.campusai.core.health.HealthMetricKey
import com.campusai.core.health.HealthMetricStatus
import com.campusai.core.health.HealthMetricTimeSeries
import com.campusai.core.health.HealthMetricUnit
import com.campusai.core.health.HealthMetricValue
import com.campusai.core.health.HealthPeriod
import com.campusai.core.health.HealthTimeSeriesPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.security.MessageDigest

interface MiFitnessStepsTransport {
    suspend fun exchangePassToken(credential: MiFitnessCredential): MiFitnessSession

    /** Authoritative daily_report/steps aggregate; despite the legacy name this is not a time bucket API. */
    suspend fun fetchSteps(
        session: MiFitnessSession,
        startEpochSeconds: Long,
        endEpochSecondsExclusive: Long,
        nextKey: String,
    ): String

    suspend fun fetchDailyAggregate(
        session: MiFitnessSession,
        metric: String,
        startEpochSeconds: Long,
        endEpochSecondsExclusive: Long,
        nextKey: String,
    ): String = if (metric == "steps") {
        fetchSteps(session, startEpochSeconds, endEpochSecondsExclusive, nextKey)
    } else {
        EMPTY_AGGREGATE_PAGE
    }

    suspend fun fetchStepSeries(
        session: MiFitnessSession,
        startEpochSeconds: Long,
        endEpochSecondsExclusive: Long,
        nextKey: String,
    ): String = EMPTY_AGGREGATE_PAGE

    suspend fun fetchSportRecords(
        session: MiFitnessSession,
        startEpochSeconds: Long,
        endEpochSecondsExclusive: Long,
        nextKey: String,
    ): String = EMPTY_SPORT_PAGE

    private companion object {
        const val EMPTY_AGGREGATE_PAGE =
            "{\"code\":0,\"result\":{\"data_list\":[],\"has_more\":false,\"next_key\":\"\"}}"
        const val EMPTY_SPORT_PAGE =
            "{\"code\":0,\"result\":{\"sport_records\":[],\"has_more\":false,\"next_key\":\"\"}}"
    }
}

class MiFitnessReadOnlyTransportAdapter(
    private val client: MiFitnessReadOnlyClient = MiFitnessReadOnlyClient(),
) : MiFitnessStepsTransport {
    override suspend fun exchangePassToken(credential: MiFitnessCredential): MiFitnessSession =
        client.exchangePassToken(credential.userId, credential.passToken)

    override suspend fun fetchSteps(
        session: MiFitnessSession,
        startEpochSeconds: Long,
        endEpochSecondsExclusive: Long,
        nextKey: String,
    ): String = client.fetchSteps(session, startEpochSeconds, endEpochSecondsExclusive, nextKey)

    override suspend fun fetchDailyAggregate(
        session: MiFitnessSession,
        metric: String,
        startEpochSeconds: Long,
        endEpochSecondsExclusive: Long,
        nextKey: String,
    ): String = client.fetchDailyAggregate(
        session,
        metric,
        startEpochSeconds,
        endEpochSecondsExclusive,
        nextKey,
    )

    override suspend fun fetchStepSeries(
        session: MiFitnessSession,
        startEpochSeconds: Long,
        endEpochSecondsExclusive: Long,
        nextKey: String,
    ): String = client.fetchStepSeries(session, startEpochSeconds, endEpochSecondsExclusive, nextKey)

    override suspend fun fetchSportRecords(
        session: MiFitnessSession,
        startEpochSeconds: Long,
        endEpochSecondsExclusive: Long,
        nextKey: String,
    ): String = client.fetchSportRecords(session, startEpochSeconds, endEpochSecondsExclusive, nextKey)
}

class MiFitnessStepsSyncException(
    val code: String,
    message: String,
) : IllegalStateException(message) {
    override fun toString(): String = "MiFitnessStepsSyncException(code=$code)"
}

internal data class MiFitnessCnDayWindow(
    val localDate: LocalDate,
    val period: HealthPeriod,
    val startEpochSeconds: Long,
    val endEpochSecondsExclusive: Long,
)

internal class MiFitnessStepsSyncOutcome(
    val summary: MiFitnessStepsSummary?,
    val refreshedPassToken: String?,
) {
    override fun toString(): String = "MiFitnessStepsSyncOutcome(<redacted>)"
}

private class MiFitnessAuthenticatedSession(
    credential: MiFitnessCredential,
    private val transport: MiFitnessStepsTransport,
) {
    private var activeCredential = credential
    private lateinit var current: MiFitnessSession
    private var reauthenticationUsed = false
    var refreshedPassToken: String? = null
        private set

    suspend fun open() {
        current = exchange()
    }

    suspend fun <T> read(block: suspend (MiFitnessSession) -> T): T = try {
        block(current)
    } catch (authentication: MiFitnessAuthenticationException) {
        if (reauthenticationUsed) throw authentication
        reauthenticationUsed = true
        current = exchange()
        block(current)
    }

    private suspend fun exchange(): MiFitnessSession = transport.exchangePassToken(activeCredential).also { session ->
        session.refreshedPassToken?.let { refreshed ->
            refreshedPassToken = refreshed
            activeCredential = MiFitnessCredential(activeCredential.userId, refreshed)
        }
    }
}

class MiFitnessStepsSyncService internal constructor(
    private val credentialStore: MiFitnessCredentialStore,
    private val transport: MiFitnessStepsTransport,
    private val cache: MiFitnessStepsCache,
    private val clock: Clock,
    private val zoneId: ZoneId,
) {
    internal constructor(
        credentialStore: MiFitnessCredentialStore,
        transport: MiFitnessStepsTransport,
        cache: MiFitnessStepsCache,
        clock: Clock,
    ) : this(credentialStore, transport, cache, clock, CaesarTime.zone)

    constructor(context: Context) : this(
        credentialStore = MiFitnessCredentialStore(context),
        transport = MiFitnessReadOnlyTransportAdapter(),
        cache = MiFitnessStepsCache(context),
        clock = Clock.systemUTC(),
        zoneId = CaesarTime.zone,
    )

    suspend fun syncToday(): Result<MiFitnessStepsSummary> = serialized {
        val credential = try {
            credentialStore.read()
        } catch (_: Exception) {
            null
        } ?: return@serialized failure("credentials_missing", "尚未保存小米运动健康凭据。")
        val window = todayWindow()
        val oldSummary = try {
            cache.read(window.period, window.localDate, credential.accountScope)
        } catch (_: Exception) {
            return@serialized failure("sync_failed", "小米运动健康数据同步失败。")
        }
        val outcome = syncTodayOutcomeLocked(credential, window).getOrElse { error ->
            return@serialized Result.failure(error)
        }
        val refreshedPassToken = outcome.refreshedPassToken
        if (refreshedPassToken != null) {
            val saved = try {
                credentialStore.save(credential.userId, refreshedPassToken).isSuccess
            } catch (_: Exception) {
                false
            }
            if (!saved) {
                if (outcome.summary != null) restoreCache(oldSummary)
                return@serialized failure("credential_write_failed", "系统安全存储不可用，刷新凭据未保存。")
            }
        }
        val summary = outcome.summary
            ?: return@serialized failure("no_cloud_data", "Mi Fitness 云端尚未返回今天的官方日步数。")
        Result.success(summary)
    }

    internal fun todayWindow(): MiFitnessCnDayWindow {
        val localDate = clock.instant().atZone(zoneId).toLocalDate()
        val startEpochSeconds = localDate.atStartOfDay(zoneId).toEpochSecond()
        val endEpochSecondsExclusive = localDate.plusDays(1).atStartOfDay(zoneId).toEpochSecond()
        return MiFitnessCnDayWindow(
            localDate = localDate,
            period = HealthPeriod(
                startEpochMillis = startEpochSeconds * 1_000L,
                endEpochMillis = endEpochSecondsExclusive * 1_000L - 1L,
                key = "today",
            ),
            startEpochSeconds = startEpochSeconds,
            endEpochSecondsExclusive = endEpochSecondsExclusive,
        )
    }

    internal suspend fun syncTodayOutcomeLocked(
        credential: MiFitnessCredential,
        window: MiFitnessCnDayWindow,
    ): Result<MiFitnessStepsSyncOutcome> = try {
        withTimeout(MAX_SYNC_DURATION_MILLIS) {
            val session = MiFitnessAuthenticatedSession(credential, transport)
            session.open()
            val stepDefinition = MiFitnessMetricRegistry.definition("steps")
            val stepRecords = fetchAggregateRecords(session, stepDefinition, window)
            val stepRecord = MiFitnessAggregateParser.selectDaily(
                stepRecords,
                window.startEpochSeconds,
                window.endEpochSecondsExclusive,
            ).getOrElse {
                throw error("aggregate_conflict", "小米运动健康返回了冲突的官方日步数。")
            }

            val metricValues = linkedMapOf<HealthMetricKey, HealthMetricValue>()
            metricValues += if (stepRecord == null) {
                MiFitnessMetricRegistry.unavailableValues(
                    stepDefinition,
                    HealthMetricStatus.EMPTY,
                    "no_cloud_data",
                )
            } else {
                MiFitnessAggregateParser.metricsFor(stepRecord).getOrElse {
                    throw error("response_invalid", "小米运动健康官方日步数格式无效。")
                }
            }
            MiFitnessMetricRegistry.definitions.drop(1).forEach { definition ->
                metricValues.mergeOptional(loadOptionalAggregate(session, definition, window))
            }
            val workouts = loadWorkouts(session, window)
            metricValues += workouts.metrics
            val metricTimeSeries = mapOf(
                HealthMetricKey.STEPS to loadStepSeries(session, window),
            )

            val steps = metricValues[HealthMetricKey.STEPS]
                ?.takeIf { it.status == HealthMetricStatus.AVAILABLE }
                ?.value
                ?.toLong()
            val recordCount = metricValues.values
                .asSequence()
                .filter { it.status == HealthMetricStatus.AVAILABLE }
                .mapNotNull { it.provenance.vendorKey }
                .distinct()
                .count()
            val syncedAt = clock.millis()
            val summary = MiFitnessStepsSummary(
                period = window.period,
                localDate = window.localDate,
                accountScope = credential.accountScope,
                steps = steps,
                recordCount = recordCount,
                observedAt = syncedAt,
                lastSyncAt = syncedAt,
                metricValues = metricValues.toMap(),
                metricTimeSeries = metricTimeSeries,
                workoutRevision = workouts.revision,
                schemaProvisional = false,
                aggregationProvisional = false,
            )
            if (cache.save(summary).isFailure) {
                throw error("cache_write_failed", "系统安全存储不可用，健康摘要未缓存。")
            }
            Result.success(MiFitnessStepsSyncOutcome(summary, session.refreshedPassToken))
        }
    } catch (_: TimeoutCancellationException) {
        failure("network_failed", "小米运动健康网络请求超时。")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (exception: MiFitnessStepsSyncException) {
        Result.failure(exception)
    } catch (_: MiFitnessAuthenticationException) {
        failure("authentication_failed", "小米运动健康身份验证失败。")
    } catch (_: MiFitnessRateLimitException) {
        failure("rate_limited", "小米运动健康请求受到限流。")
    } catch (_: MiFitnessServerException) {
        failure("server_unavailable", "小米运动健康服务暂时不可用。")
    } catch (_: MiFitnessNetworkException) {
        failure("network_failed", "小米运动健康网络请求失败。")
    } catch (_: MiFitnessProtocolException) {
        failure("response_invalid", "小米运动健康响应格式无效。")
    } catch (_: Exception) {
        failure("sync_failed", "小米运动健康数据同步失败。")
    }

    private suspend fun fetchAggregateRecords(
        session: MiFitnessAuthenticatedSession,
        definition: MiFitnessMetricDefinition,
        window: MiFitnessCnDayWindow,
    ): List<MiFitnessAggregateRecord> {
        val records = ArrayList<MiFitnessAggregateRecord>()
        val seenCursors = mutableSetOf("")
        var cursor = ""
        repeat(MAX_PAGES) {
            val rawPage = if (definition.requestKey == "steps") {
                session.read { active ->
                    transport.fetchSteps(
                        active,
                        window.startEpochSeconds,
                        window.endEpochSecondsExclusive,
                        cursor,
                    )
                }
            } else {
                session.read { active ->
                    transport.fetchDailyAggregate(
                        active,
                        definition.requestKey,
                        window.startEpochSeconds,
                        window.endEpochSecondsExclusive,
                        cursor,
                    )
                }
            }
            val page = MiFitnessAggregateParser.parse(rawPage, definition.requestKey).getOrElse {
                throw error("response_invalid", "小米运动健康日聚合响应格式无效。")
            }
            if (page.records.any { it.epochSeconds !in window.startEpochSeconds until window.endEpochSecondsExclusive }) {
                throw error("record_out_of_window", "小米运动健康返回了日期范围外的数据。")
            }
            if (records.size + page.records.size > MAX_RECORDS) {
                throw error("record_limit", "本次健康记录超出安全上限。")
            }
            records += page.records
            if (!page.hasMore) return records
            cursor = nextCursor(page.nextKey, seenCursors)
        }
        throw error("page_limit", "本次健康数据分页超出安全上限。")
    }

    private suspend fun loadOptionalAggregate(
        session: MiFitnessAuthenticatedSession,
        definition: MiFitnessMetricDefinition,
        window: MiFitnessCnDayWindow,
    ): Map<HealthMetricKey, HealthMetricValue> = try {
        val records = fetchAggregateRecords(session, definition, window)
        val selected = MiFitnessAggregateParser.selectDaily(
            records,
            window.startEpochSeconds,
            window.endEpochSecondsExclusive,
        ).getOrThrow()
        if (selected == null) {
            MiFitnessMetricRegistry.unavailableValues(definition, HealthMetricStatus.EMPTY, "no_cloud_data")
        } else {
            MiFitnessAggregateParser.metricsFor(selected).getOrThrow()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (authentication: MiFitnessAuthenticationException) {
        throw authentication
    } catch (_: MiFitnessRateLimitException) {
        MiFitnessMetricRegistry.unavailableValues(definition, HealthMetricStatus.ERROR, "rate_limited")
    } catch (_: MiFitnessServerException) {
        MiFitnessMetricRegistry.unavailableValues(definition, HealthMetricStatus.ERROR, "server_unavailable")
    } catch (_: MiFitnessNetworkException) {
        MiFitnessMetricRegistry.unavailableValues(definition, HealthMetricStatus.ERROR, "network_failed")
    } catch (_: Exception) {
        MiFitnessMetricRegistry.unavailableValues(definition, HealthMetricStatus.ERROR, "response_invalid")
    }

    private suspend fun loadWorkouts(
        session: MiFitnessAuthenticatedSession,
        window: MiFitnessCnDayWindow,
    ): WorkoutLoadResult = try {
        val records = fetchSportRecords(session, window)
        val completed = records
            .asSequence()
            .filterNot(MiFitnessSportRecord::deleted)
            .distinctBy(MiFitnessSportRecord::idDigest)
            .toList()
        val metrics = if (records.isEmpty()) {
            MiFitnessMetricRegistry.unavailableValues(
                MiFitnessMetricRegistry.workoutDefinition,
                HealthMetricStatus.EMPTY,
                "no_cloud_data",
            )
        } else {
            mapOf(
                HealthMetricKey.WORKOUT_COUNT to HealthMetricValue(
                    value = completed.size.toDouble(),
                    unit = checkNotNull(
                        MiFitnessMetricRegistry.workoutDefinition.outputs[HealthMetricKey.WORKOUT_COUNT],
                    ),
                    status = HealthMetricStatus.AVAILABLE,
                    provenance = MiFitnessMetricRegistry.provenance("sport_records", completed.size),
                ),
            )
        }
        WorkoutLoadResult(metrics, stableWorkoutRevision(records))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (authentication: MiFitnessAuthenticationException) {
        throw authentication
    } catch (_: MiFitnessRateLimitException) {
        WorkoutLoadResult(errorWorkoutValues("rate_limited"), null)
    } catch (_: MiFitnessServerException) {
        WorkoutLoadResult(errorWorkoutValues("server_unavailable"), null)
    } catch (_: MiFitnessNetworkException) {
        WorkoutLoadResult(errorWorkoutValues("network_failed"), null)
    } catch (_: Exception) {
        WorkoutLoadResult(errorWorkoutValues("response_invalid"), null)
    }

    private fun errorWorkoutValues(reason: String): Map<HealthMetricKey, HealthMetricValue> =
        MiFitnessMetricRegistry.unavailableValues(
            MiFitnessMetricRegistry.workoutDefinition,
            HealthMetricStatus.ERROR,
            reason,
        )

    private data class WorkoutLoadResult(
        val metrics: Map<HealthMetricKey, HealthMetricValue>,
        val revision: String?,
    )

    private suspend fun loadStepSeries(
        session: MiFitnessAuthenticatedSession,
        window: MiFitnessCnDayWindow,
    ): HealthMetricTimeSeries {
        val pointsByTime = linkedMapOf<Long, Long>()
        return try {
            val seenCursors = mutableSetOf("")
            var cursor = ""
            repeat(MAX_PAGES) {
                val page = MiFitnessStepSeriesParser.parsePage(
                    session.read { active ->
                        transport.fetchStepSeries(
                            active,
                            window.startEpochSeconds,
                            window.endEpochSecondsExclusive,
                            cursor,
                        )
                    },
                ).getOrElse { throw error("response_invalid", "小米运动健康步数趋势响应格式无效。") }
                page.points.forEach { point ->
                    if (point.epochSeconds !in window.startEpochSeconds until window.endEpochSecondsExclusive) {
                        throw error("record_out_of_window", "小米运动健康返回了日期范围外的步数趋势。")
                    }
                    val previous = pointsByTime[point.epochSeconds]
                    if (previous != null && previous != point.steps) {
                        throw error("series_conflict", "小米运动健康返回了冲突的步数趋势。")
                    }
                    if (previous == null && pointsByTime.size >= MAX_RECORDS) {
                        throw error("record_limit", "本次步数趋势记录超出安全上限。")
                    }
                    pointsByTime[point.epochSeconds] = point.steps
                }
                if (!page.hasMore) {
                    return stepSeries(
                        pointsByTime,
                        if (pointsByTime.isEmpty()) HealthMetricStatus.EMPTY else HealthMetricStatus.AVAILABLE,
                        if (pointsByTime.isEmpty()) "no_cloud_data" else null,
                    )
                }
                cursor = nextCursor(page.nextKey, seenCursors)
            }
            throw error("page_limit", "本次步数趋势分页超出安全上限。")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: MiFitnessAuthenticationException) {
            stepSeriesFailure(pointsByTime, "authentication_failed")
        } catch (_: MiFitnessRateLimitException) {
            stepSeriesFailure(pointsByTime, "rate_limited")
        } catch (_: MiFitnessServerException) {
            stepSeriesFailure(pointsByTime, "server_unavailable")
        } catch (_: MiFitnessNetworkException) {
            stepSeriesFailure(pointsByTime, "network_failed")
        } catch (failure: MiFitnessStepsSyncException) {
            stepSeriesFailure(pointsByTime, failure.code)
        } catch (_: Exception) {
            stepSeriesFailure(pointsByTime, "response_invalid")
        }
    }

    private fun stepSeriesFailure(
        pointsByTime: Map<Long, Long>,
        reasonCode: String,
    ): HealthMetricTimeSeries = stepSeries(
        pointsByTime,
        if (pointsByTime.isEmpty()) HealthMetricStatus.ERROR else HealthMetricStatus.PARTIAL,
        reasonCode,
    )

    private fun stepSeries(
        pointsByTime: Map<Long, Long>,
        status: HealthMetricStatus,
        reasonCode: String?,
    ): HealthMetricTimeSeries {
        val points = pointsByTime.entries
            .sortedBy { it.key }
            .map { (epochSeconds, steps) -> HealthTimeSeriesPoint(epochSeconds * 1_000L, steps.toDouble()) }
        return HealthMetricTimeSeries(
            unit = HealthMetricUnit.COUNT,
            status = status,
            points = points,
            provenance = MiFitnessMetricRegistry.stepSeriesProvenance(points.size),
            reasonCode = reasonCode,
        )
    }

    private suspend fun fetchSportRecords(
        session: MiFitnessAuthenticatedSession,
        window: MiFitnessCnDayWindow,
    ): List<MiFitnessSportRecord> {
        val records = ArrayList<MiFitnessSportRecord>()
        val seenCursors = mutableSetOf("")
        var cursor = ""
        repeat(MAX_PAGES) {
            val page = MiFitnessSportParser.parse(
                session.read { active ->
                    transport.fetchSportRecords(
                        active,
                        window.startEpochSeconds,
                        window.endEpochSecondsExclusive,
                        cursor,
                    )
                },
            ).getOrElse { throw error("response_invalid", "小米运动健康运动记录响应格式无效。") }
            if (page.records.any { it.epochSeconds !in window.startEpochSeconds until window.endEpochSecondsExclusive }) {
                throw error("record_out_of_window", "小米运动健康返回了日期范围外的运动记录。")
            }
            if (records.size + page.records.size > MAX_RECORDS) {
                throw error("record_limit", "本次运动记录超出安全上限。")
            }
            records += page.records
            if (!page.hasMore) return records
            cursor = nextCursor(page.nextKey, seenCursors)
        }
        throw error("page_limit", "本次运动记录分页超出安全上限。")
    }

    private fun nextCursor(nextKey: String?, seen: MutableSet<String>): String {
        val cursor = nextKey ?: throw error("cursor_missing", "小米运动健康分页响应缺少游标。")
        if (cursor.length > MAX_CURSOR_CHARS) throw error("cursor_limit", "小米运动健康分页游标超出安全上限。")
        if (!seen.add(cursor)) throw error("cursor_repeated", "小米运动健康分页游标重复。")
        return cursor
    }

    private fun MutableMap<HealthMetricKey, HealthMetricValue>.mergeOptional(
        incoming: Map<HealthMetricKey, HealthMetricValue>,
    ) {
        incoming.forEach { (key, value) ->
            val current = this[key]
            if (current == null || value.status == HealthMetricStatus.AVAILABLE) this[key] = value
        }
    }

    private fun restoreCache(summary: MiFitnessStepsSummary?) {
        try {
            if (summary == null) cache.delete() else cache.save(summary)
        } catch (_: Exception) {
            // Keep the surfaced failure generic; cache payloads never enter exceptions.
        }
    }

    companion object {
        private const val MAX_PAGES = 10
        private const val MAX_RECORDS = 10_000
        private const val MAX_CURSOR_CHARS = 4_096
        private const val MAX_SYNC_DURATION_MILLIS = 90_000L
        private val syncMutex = Mutex()

        internal suspend fun <T> serialized(block: suspend () -> T): T {
            syncMutex.lock()
            try {
                return block()
            } finally {
                syncMutex.unlock()
            }
        }

        private fun error(code: String, message: String) = MiFitnessStepsSyncException(code, message)

        private fun <T> failure(code: String, message: String): Result<T> =
            Result.failure(error(code, message))
    }
}

/** Duplicate pages cannot manufacture a new revision; distinct versions/tombstones still can. */
internal fun stableWorkoutRevision(records: List<MiFitnessSportRecord>): String {
    val canonical = records
        .map { record -> "${record.idDigest}|${record.revisionDigest}" }
        .distinct()
        .sorted()
        .joinToString("\n")
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
