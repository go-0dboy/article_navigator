package io.github.go0dboy.articlenavigator.feature.inbox

import io.github.go0dboy.articlenavigator.core.data.InboxPagingRepository
import io.github.go0dboy.articlenavigator.core.model.DocumentId
import io.github.go0dboy.articlenavigator.core.model.InboxItem
import io.github.go0dboy.articlenavigator.core.model.InboxItemId
import io.github.go0dboy.articlenavigator.core.model.InboxPageKey
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxViewModelTest {
    @Test
    fun exactCountPagingAndBackgroundInvalidationRefreshVisibleItems() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        try {
            val repository = FakeInboxRepository()
            repository.replace((0 until 25).map(::item), emitRevision = false)
            val viewModel = InboxViewModel(repository, NoopActions)

            advanceUntilIdle()
            assertEquals(25, viewModel.state.value.totalCount)
            assertEquals(20, viewModel.state.value.items.size)
            assertTrue(viewModel.state.value.hasMore)

            viewModel.loadMore()
            advanceUntilIdle()
            assertEquals(25, viewModel.state.value.items.size)
            assertFalse(viewModel.state.value.hasMore)

            val newest = InboxItem(
                id = InboxItemId("inbox-new"),
                canonicalUrl = "https://example.test/new",
                title = "New background item",
                normalizedText = "Body",
                contentHash = "new-hash",
                createdAt = BASE.plusSeconds(60),
                updatedAt = BASE.plusSeconds(60),
            )
            repository.replace(repository.rows + newest)
            advanceUntilIdle()

            assertEquals(26, viewModel.state.value.totalCount)
            assertEquals(20, viewModel.state.value.items.size)
            assertEquals(newest.id, viewModel.state.value.items.first().id)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun successfulSavePublishesDocumentNavigationWithoutCompositionOwnedCoroutine() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        try {
            val repository = FakeInboxRepository()
            val item = item(1)
            repository.replace(listOf(item), emitRevision = false)
            val savedId = DocumentId("saved-document")
            val actions = object : InboxActions {
                override suspend fun save(id: InboxItemId): DocumentId {
                    assertEquals(item.id, id)
                    repository.replace(emptyList())
                    return savedId
                }

                override suspend fun reject(id: InboxItemId): Boolean = true
                override suspend fun readAndDiscard(id: InboxItemId): Boolean = true
            }
            val viewModel = InboxViewModel(repository, actions)
            advanceUntilIdle()

            viewModel.save(item.id)
            advanceUntilIdle()

            assertEquals(savedId, viewModel.state.value.savedDocumentToOpen)
            assertEquals("Материал сохранён в библиотеку", viewModel.state.value.message)
            assertEquals(0, viewModel.state.value.totalCount)
            assertTrue(viewModel.state.value.items.isEmpty())
            assertFalse(item.id in viewModel.state.value.busyIds)

            viewModel.consumeSavedNavigation()
            assertNull(viewModel.state.value.savedDocumentToOpen)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class FakeInboxRepository : InboxPagingRepository {
        val revision = MutableStateFlow(0L)
        val count = MutableStateFlow(0)
        var rows: List<InboxItem> = emptyList()

        override fun observeRevision(): Flow<Long> = revision
        override fun observePendingCount(): Flow<Int> = count

        override suspend fun loadPage(after: InboxPageKey?, limit: Int): List<InboxItem> {
            val ordered = rows.sortedWith(compareByDescending<InboxItem> { it.createdAt }.thenByDescending { it.id.value })
            return ordered.asSequence()
                .filter { candidate ->
                    after == null || candidate.createdAt < after.createdAt ||
                        (candidate.createdAt == after.createdAt && candidate.id.value < after.itemId.value)
                }
                .take(limit)
                .toList()
        }

        fun replace(values: List<InboxItem>, emitRevision: Boolean = true) {
            rows = values
            count.value = values.size
            if (emitRevision) revision.value += 1
        }
    }

    private object NoopActions : InboxActions {
        override suspend fun save(id: InboxItemId): DocumentId = DocumentId("unused")
        override suspend fun reject(id: InboxItemId): Boolean = true
        override suspend fun readAndDiscard(id: InboxItemId): Boolean = true
    }

    companion object {
        private val BASE = Instant.parse("2026-09-16T10:00:00Z")

        private fun item(index: Int): InboxItem {
            val suffix = index.toString().padStart(2, '0')
            return InboxItem(
                id = InboxItemId("inbox-$suffix"),
                canonicalUrl = "https://example.test/$suffix",
                title = "Title $suffix",
                normalizedText = "Body $suffix",
                contentHash = "hash-$suffix",
                createdAt = BASE,
                updatedAt = BASE,
            )
        }
    }
}
