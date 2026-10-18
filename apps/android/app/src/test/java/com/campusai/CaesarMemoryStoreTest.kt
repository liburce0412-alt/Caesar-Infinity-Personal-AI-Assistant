package com.campusai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.campusai.core.agent.CaesarMemoryStore
import com.campusai.core.database.CampusDatabase
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CaesarMemoryStoreTest {
    private lateinit var database: CampusDatabase
    private lateinit var store: CaesarMemoryStore

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            CampusDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = CaesarMemoryStore(database.campusDao())
    }

    @After fun tearDown() = database.close()

    @Test fun `memory is not context until confirmed and remains editable`() = runTest {
        val proposal = store.propose("preference", "晚上八点提醒我运动", "user turn", .9)
        assertTrue(store.context().isEmpty())
        assertTrue(store.updateContent(proposal.id, "晚上九点提醒我运动"))
        assertTrue(store.confirm(proposal.id))
        assertEquals("晚上九点提醒我运动", store.context().single().content)

        store.forget(proposal.id)
        assertTrue(store.context().isEmpty())
        assertFalse(store.updateContent(proposal.id, "不应该成功"))
    }

    @Test fun `expired memory never enters context`() = runTest {
        val proposal = store.propose("goal", "本周完成五次运动", "user turn", .8, expiresAt = 100)
        assertTrue(store.confirm(proposal.id))
        assertTrue(store.context(now = 101).isEmpty())
    }

    @Test fun `account context and writes never include another owner or legacy local memories`() = runTest {
        val alice = store.propose("fact", "Alice private fact", "alice", .9, ownerUserId = "alice")
        val local = store.propose("fact", "Old local fact", "local", .9)
        assertFalse(store.confirm(alice.id, "bob"))
        assertFalse(store.updateContent(alice.id, "Bob overwrite", "bob"))
        assertFalse(store.forget(alice.id, "bob"))
        assertTrue(store.confirm(alice.id, "alice"))
        assertTrue(store.confirm(local.id))
        assertTrue(store.context("bob").isEmpty())
        assertEquals(listOf("Alice private fact"), store.context("alice").map { it.content })
        assertEquals(listOf("Old local fact"), store.context().map { it.content })
        assertEquals(listOf(alice.id), database.campusDao().getAgentMemoriesFlow("alice").first().map { it.id })
        assertEquals(listOf(local.id), database.campusDao().getAgentMemoriesFlow().first().map { it.id })
        assertFalse(store.updateContent(local.id, "Claimed", "alice"))
        assertFalse(store.forget(local.id, "alice"))
    }

    @Test fun `bulk deletion deletes only the captured account and preserves local legacy rows`() = runTest {
        val alice = store.propose("fact", "Alice", "alice", .9, ownerUserId = "alice")
        val bob = store.propose("fact", "Bob", "bob", .9, ownerUserId = "bob")
        val local = store.propose("fact", "Local", "local", .9)
        store.confirm(alice.id, "alice")
        store.confirm(bob.id, "bob")
        store.confirm(local.id)
        store.forgetAll("bob")
        assertTrue(store.context("bob").isEmpty())
        assertEquals(alice.id, store.context("alice").single().id)
        assertEquals(local.id, store.context().single().id)
        assertTrue(store.forget(alice.id, "alice"))
        assertTrue(store.context("alice").isEmpty())
    }
}
