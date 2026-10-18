package com.campusai.features.ai

import com.campusai.core.database.AiReportSummaryEntity
import com.campusai.core.database.CampusDao
import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiHistoryControllerTest {
    @Test fun `owner change clears replay immediately and old rows cannot return after resubscription`() = runTest {
        val alice = MutableStateFlow(listOf(row("a", "alice")))
        val bob = MutableStateFlow(listOf(row("b", "bob"), row("foreign", "alice")))
        val queries = mutableListOf<Pair<String, Int>>()
        val dao = Proxy.newProxyInstance(CampusDao::class.java.classLoader, arrayOf(CampusDao::class.java)) { _, method, args ->
            check(method.name == "observeAiReportSummaries")
            val owner = args[0] as String
            queries += owner to (args[1] as Int)
            if (owner == "alice") alice else bob
        } as CampusDao
        val controller = AiHistoryController(dao, backgroundScope)
        controller.selectOwner("alice")
        val observer = backgroundScope.launch { controller.history.collect {} }
        runCurrent()
        assertEquals(listOf("a"), controller.history.value.map { it.id })
        observer.cancel()
        runCurrent()
        controller.selectOwner("bob")
        assertTrue(controller.history.value.isEmpty())
        alice.value = listOf(row("late-a", "alice"))
        backgroundScope.launch { controller.history.collect {} }
        runCurrent()
        assertEquals(listOf("b"), controller.history.value.map { it.id })
        assertFalse(controller.viewingLocal.value)
        controller.loadMore()
        runCurrent()
        assertEquals("bob" to 81, queries.last())
        assertEquals(80, controller.limit.value)
        controller.selectOwner("local_user")
        assertTrue(controller.history.value.isEmpty())
        assertTrue(controller.viewingLocal.value)
        assertEquals(40, controller.limit.value)
    }

    private fun row(id: String, owner: String) = AiReportSummaryEntity(
        id, "LOCAL", "FAST", "test", "LOCAL_DETERMINISTIC", "", "title", "summary", 1, 2, owner,
    )
}
