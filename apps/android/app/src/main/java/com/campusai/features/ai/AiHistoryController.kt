package com.campusai.features.ai

import com.campusai.core.database.CampusDao
import com.campusai.core.model.AiReportSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Account changes clear replayed metadata synchronously, before the next Room query completes. */
internal class AiHistoryController(
    dao: CampusDao,
    scope: CoroutineScope,
    private val onError: () -> Unit = {},
) {
    private data class Query(val owner: String, val limit: Int, val revision: Long)
    private val query = MutableStateFlow(Query("local_user", PAGE_SIZE, 0))
    private val _history = MutableStateFlow<List<AiReportSummary>>(emptyList())
    val history = _history.asStateFlow()
    private val _local = MutableStateFlow(true)
    val viewingLocal = _local.asStateFlow()
    private val _limit = MutableStateFlow(PAGE_SIZE)
    val limit = _limit.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val subscription = scope.launch {
        combine(query, _history.subscriptionCount) { request, subscribers -> request to (subscribers > 0) }
            .flatMapLatest { (request, subscribed) ->
                if (!subscribed) emptyFlow() else dao.observeAiReportSummaries(request.owner, request.limit + 1)
                    .map { rows -> request to rows.map { it.toDomain() } }
                    .catch { if (query.value == request) onError() }
            }
            .collect { (request, rows) ->
                if (query.value == request) _history.value = rows.filter { it.ownerUserId == request.owner }
            }
    }

    fun selectOwner(owner: String) {
        val current = query.value
        _history.value = emptyList()
        _local.value = owner == "local_user"
        _limit.value = PAGE_SIZE
        query.value = Query(owner, PAGE_SIZE, current.revision + 1)
    }

    fun loadMore() {
        val current = query.value
        val next = current.copy(limit = current.limit + PAGE_SIZE, revision = current.revision + 1)
        _limit.value = next.limit
        query.value = next
    }

    private companion object { const val PAGE_SIZE = 40 }
}
