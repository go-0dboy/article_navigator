package io.github.go0dboy.articlenavigator.feature.inbox

import io.github.go0dboy.articlenavigator.core.data.InboxPagingRepository
import io.github.go0dboy.articlenavigator.core.model.DocumentId
import io.github.go0dboy.articlenavigator.core.model.InboxItem
import io.github.go0dboy.articlenavigator.core.model.InboxItemId
import io.github.go0dboy.articlenavigator.core.model.InboxPageKey
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InboxViewModelTest {
    @Test
    fun exactCountPagingAndBackgroundInvalidationRefreshLoadedRange() = runTest {
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
            assertEquals(25, viewModel.state.value.items.size)
            assertEquals(newest.id, viewModel.state.value.items.first().id)
            assertTrue(viewModel.state.value.hasMore)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun failedRefreshPreservesCursorAcrossRetryAndSubsequentPageLoad() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        try {
            val repository = FakeInboxRepository()
            repository.replace((0 until 60).map(::item), emitRevision = false)
            val viewModel = InboxViewModel(repository, NoopActions)
            advanceUntilIdle()

            viewModel.loadMore()
            advanceUntilIdle()
            assertEquals(40, viewModel.state.value.items.size)
            val beforeFailure = viewModel.state.value.items.map { it.id }

            repository.nextLoadFailure = IllegalStateException("inbox refresh failed")
            repository.revision.value += 1
            advanceUntilIdle()
            assertEquals("inbox refresh failed", viewModel.state.value.error)
            assertEquals(beforeFailure, viewModel.state.value.items.map { it.id })
            assertTrue(viewModel.state.value.hasMore)

            viewModel.retry()
            advanceUntilIdle()
            assertNull(viewModel.state.value.error)
            assertEquals(40, viewModel.state.value.items.size)

            viewModel.loadMore()
            advanceUntilIdle()
            assertEquals(60, viewModel.state.value.items.size)
            assertFalse(viewModel.state.value.hasMore)
            assertNotNull(repository.loadRequests.last())
            assertEquals(60, viewModel.state.value.items.map { it.id }.distinct().size)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun observationFailureIsVisibleAndRetryReconnectsThenReceivesChanges() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        try {
            val repository = FakeInboxRepository()
            repository.replace((0 until 3).map(::item), emitRevision = false)
            repository.revisionFailuresRemaining = 1
            val viewModel = InboxViewModel(repository, NoopActions)

            advanceUntilIdle()
            assertEquals("inbox revision stream failed", viewModel.state.value.subscriptionError)
            assertFalse(viewModel.state.value.loading)
            assertTrue(viewModel.state.value.items.isEmpty())
            assertEquals(1, repository.revisionSubscriptions)

            viewModel.retry()
            advanceUntilIdle()
            assertNull(viewModel.state.value.subscriptionError)
            assertEquals(3, viewModel.state.value.items.size)
            assertEquals(2, repository.revisionSubscriptions)

            val newest = item(99).copy(createdAt = BASE.plusSeconds(90), updatedAt = BASE.plusSeconds(90))
            repository.replace(repository.rows + newest)
            advanceUntilIdle()
            assertEquals(newest.id, viewModel.state.value.items.first().id)
            assertEquals(4, viewModel.state.value.totalCount)
            assertEquals(2, repository.revisionSubscriptions)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun countFailureIsVisibleAndRetryRestoresCountObservation() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        try {
            val repository = FakeInboxRepository()
            repository.replace((0 until 2).map(::item), emitRevision = false)
            repository.countFailuresRemaining = 1
            val viewModel = InboxViewModel(repository, NoopActions)

            advanceUntilIdle()
            assertEquals("inbox count stream failed", viewModel.state.value.subscriptionError)
            assertEquals(2, viewModel.state.value.items.size)
            assertEquals(0, viewModel.state.value.totalCount)

            viewModel.retry()
            advanceUntilIdle()
            assertNull(viewModel.state.value.subscriptionError)
            assertEquals(2, viewModel.state.value.totalCount)
            assertEquals(2, repository.countSubscriptions)

            repository.replace(repository.rows + item(8))
            advanceUntilIdle()
            assertEquals(3, viewModel.state.value.totalCount)
            assertEquals(2, repository.countSubscriptions)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun successfulSavePublishesDocumentNavigationAndRemovalRefreshesInbox() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        try {
            val repository = FakeInboxRepository()
            val pending = item(1)
            repository.replace(listOf(pending), emitRevision = false)
            val savedId = DocumentId("saved-document")
            val actions = object : InboxActions {
                override suspend fun save(id: InboxItemId): DocumentId {
                    assertEquals(pending.id, id)
                    repository.replace(emptyList())
                    return savedId
                }

                override suspend fun reject(id: InboxItemId): Boolean = true
                override suspend fun readAndDiscard(id: InboxItemId): Boolean = true
            }
            val viewModel = InboxViewModel(repository, actions)
            advanceUntilIdle()

            viewModel.save(pending.id)
            advanceUntilIdle()

            assertEquals(savedId, viewModel.state.value.savedDocumentToOpen)
            assertEquals("Материал сохранён в библиотеку", viewModel.state.value.message)
            assertEquals(0, viewModel.state.value.totalCount)
            assertTrue(viewModel.state.value.items.isEmpty())
            assertFalse(pending.id in viewModel.state.value.busyIds)

            viewModel.consumeSavedNavigation()
            assertNull(viewModel.state.value.savedDocumentToOpen)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun cancelledInboxActionIsNotConvertedIntoUiFailure() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        try {
            val repository = FakeInboxRepository()
            val pending = item(7)
            repository.replace(listOf(pending), emitRevision = false)
            val actions = object : InboxActions {
                override suspend fun save(id: InboxItemId): DocumentId {
                    throw CancellationException("screen left")
                }

                override suspend fun reject(id: InboxItemId): Boolean = true
                override suspend fun readAndDiscard(id: InboxItemId): Boolean = true
            }
            val viewModel = InboxViewModel(repository, actions)
            advanceUntilIdle()

            viewModel.save(pending.id)
            advanceUntilIdle()

            assertNull(viewModel.state.value.error)
            assertNull(viewModel.state.value.savedDocumentToOpen)
            assertFalse(pending.id in viewModel.state.value.busyIds)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class FakeInboxRepository : InboxPagingRepository {
        val revision = MutableStateFlow(0L)
        val count = MutableStateFlow(0)
        var rows: List<InboxItem> = emptyList()
        var nextLoadFailure: Throwable? = null
        var revisionFailuresRemaining = 0
        var countFailuresRemaining = 0
        var revisionSubscriptions = 0
        var countSubscriptions = 0
        val loadRequests = mutableListOf<InboxPageKey?>()

        override fun observeRevision(): Flow<Long> = flow {
            revisionSubscriptions += 1
            if (revisionFailuresRemaining > 0) {
                revisionFailuresRemaining -= 1
                throw IllegalStateException("inbox revision stream failed")
            }
            emitAll(revision)
        }

        override fun observePendingCount(): Flow<Int> = flow {
            countSubscriptions += 1
            if (countFailuresRemaining > 0) {
                countFailuresRemaining -= 1
                throw IllegalStateException("inbox count stream failed")
            }
            emitAll(count)
        }

        override suspend fun loadPage(after: InboxPageKey?, limit: Int): List<InboxItem> {
            loadRequests += after
            nextLoadFailure?.let { failure ->
                nextLoadFailure = null
                throw failure
            }
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
