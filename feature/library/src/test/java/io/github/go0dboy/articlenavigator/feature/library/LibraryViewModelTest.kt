package io.github.go0dboy.articlenavigator.feature.library

import io.github.go0dboy.articlenavigator.core.data.LibraryRepository
import io.github.go0dboy.articlenavigator.core.model.ContentDisposition
import io.github.go0dboy.articlenavigator.core.model.Document
import io.github.go0dboy.articlenavigator.core.model.DocumentId
import io.github.go0dboy.articlenavigator.core.model.LibraryItem
import io.github.go0dboy.articlenavigator.core.model.LibraryPageKey
import io.github.go0dboy.articlenavigator.core.model.SavedDocument
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
class LibraryViewModelTest {
    @Test
    fun pagingAndBackgroundRevisionRefreshAlreadyLoadedRange() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        try {
            val repository = FakeLibraryRepository()
            repository.replace((0 until 25).map(::item), emitRevision = false)
            val viewModel = LibraryViewModel(repository)

            advanceUntilIdle()
            assertEquals(25, viewModel.state.value.totalCount)
            assertEquals(20, viewModel.state.value.items.size)
            assertTrue(viewModel.state.value.hasMore)

            viewModel.loadMore()
            advanceUntilIdle()
            assertEquals(25, viewModel.state.value.items.size)
            assertFalse(viewModel.state.value.hasMore)

            val newest = LibraryItem(
                id = DocumentId("doc-new"),
                title = "New background item",
                savedAt = BASE.plusSeconds(60),
                snippet = "new",
                sourceCount = 1,
                primarySourceName = "Background",
            )
            repository.replace(repository.rows + newest)
            advanceUntilIdle()

            assertEquals(26, viewModel.state.value.totalCount)
            assertEquals(25, viewModel.state.value.items.size)
            assertEquals(newest.id, viewModel.state.value.items.first().id)
            assertTrue(viewModel.state.value.hasMore)

            val changedId = viewModel.state.value.items[5].id
            repository.replace(
                repository.rows.map { row ->
                    if (row.id == changedId) row.copy(title = "Updated without count change") else row
                },
            )
            advanceUntilIdle()

            assertEquals(26, viewModel.state.value.totalCount)
            assertEquals(25, viewModel.state.value.items.size)
            assertEquals(
                "Updated without count change",
                viewModel.state.value.items.single { it.id == changedId }.title,
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun failedRefreshPreservesLoadedWindowAndCursorAcrossRetryAndLoadMore() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        try {
            val repository = FakeLibraryRepository()
            repository.replace((0 until 60).map(::item), emitRevision = false)
            val viewModel = LibraryViewModel(repository)
            advanceUntilIdle()

            viewModel.loadMore()
            advanceUntilIdle()
            assertEquals(40, viewModel.state.value.items.size)
            assertTrue(viewModel.state.value.hasMore)
            val beforeFailure = viewModel.state.value.items.map { it.id }

            repository.nextLoadFailure = IllegalStateException("refresh failed")
            repository.revision.value += 1
            advanceUntilIdle()

            assertEquals("refresh failed", viewModel.state.value.error)
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
            assertEquals(60, viewModel.state.value.items.map { it.id }.distinct().size)
            assertNotNull(repository.loadRequests.last())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun observationFailureIsVisibleAndRetryReconnectsWithoutDuplicateSubscription() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        try {
            val repository = FakeLibraryRepository()
            repository.replace((0 until 3).map(::item), emitRevision = false)
            repository.revisionFailuresRemaining = 1
            val viewModel = LibraryViewModel(repository)

            advanceUntilIdle()
            assertEquals("revision stream failed", viewModel.state.value.subscriptionError)
            assertFalse(viewModel.state.value.loading)
            assertTrue(viewModel.state.value.items.isEmpty())
            assertEquals(1, repository.revisionSubscriptions)

            viewModel.retry()
            advanceUntilIdle()

            assertNull(viewModel.state.value.subscriptionError)
            assertEquals(3, viewModel.state.value.items.size)
            assertEquals(2, repository.revisionSubscriptions)

            val newest = LibraryItem(
                id = DocumentId("after-retry"),
                title = "After retry",
                savedAt = BASE.plusSeconds(120),
                snippet = "after retry",
                sourceCount = 1,
                primarySourceName = "Retry source",
            )
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
            val repository = FakeLibraryRepository()
            repository.replace((0 until 2).map(::item), emitRevision = false)
            repository.countFailuresRemaining = 1
            val viewModel = LibraryViewModel(repository)

            advanceUntilIdle()
            assertEquals("count stream failed", viewModel.state.value.subscriptionError)
            assertEquals(2, viewModel.state.value.items.size)
            assertEquals(0, viewModel.state.value.totalCount)

            viewModel.retry()
            advanceUntilIdle()
            assertNull(viewModel.state.value.subscriptionError)
            assertEquals(2, viewModel.state.value.totalCount)
            assertEquals(2, repository.countSubscriptions)

            repository.replace(repository.rows + item(9))
            advanceUntilIdle()
            assertEquals(3, viewModel.state.value.totalCount)
            assertEquals(2, repository.countSubscriptions)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun detailFlowFailureCanReconnectAndContinueUpdatingLocalDocument() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        try {
            val repository = FakeLibraryRepository()
            val id = DocumentId("offline")
            val first = savedDocument(id, "Saved local text")
            repository.documents[id] = MutableStateFlow(first)
            repository.detailFailuresRemaining[id] = 1
            val viewModel = LibraryViewModel(repository)

            viewModel.openDocument(id)
            advanceUntilIdle()
            assertEquals("detail stream failed", viewModel.state.value.detailError)
            assertFalse(viewModel.state.value.detailLoading)
            assertEquals(1, repository.detailSubscriptions[id])

            viewModel.retryDetail()
            advanceUntilIdle()
            assertNull(viewModel.state.value.detailError)
            assertEquals("Saved local text", viewModel.state.value.selectedDocument?.document?.normalizedText)
            assertEquals(2, repository.detailSubscriptions[id])

            repository.documents.getValue(id).value = savedDocument(id, "Updated local text")
            advanceUntilIdle()
            assertEquals("Updated local text", viewModel.state.value.selectedDocument?.document?.normalizedText)
            assertEquals(2, repository.detailSubscriptions[id])
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun offlineDocumentFlowUpdatesOpenDetailFromLocalRepository() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        try {
            val repository = FakeLibraryRepository()
            val id = DocumentId("offline")
            repository.documents[id] = MutableStateFlow(savedDocument(id, "Saved local text"))
            val viewModel = LibraryViewModel(repository)

            viewModel.openDocument(id)
            advanceUntilIdle()

            assertEquals("Saved local text", viewModel.state.value.selectedDocument?.document?.normalizedText)
            assertFalse(viewModel.state.value.detailLoading)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun cancelledBackgroundRefreshIsNotConvertedIntoUiFailure() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        try {
            val repository = FakeLibraryRepository()
            repository.replace(listOf(item(1)), emitRevision = false)
            val viewModel = LibraryViewModel(repository)
            advanceUntilIdle()
            assertNull(viewModel.state.value.error)

            repository.nextLoadFailure = CancellationException("view model left")
            repository.revision.value += 1
            advanceUntilIdle()

            assertNull(viewModel.state.value.error)
            assertNull(viewModel.state.value.subscriptionError)
            assertEquals(1, viewModel.state.value.items.size)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class FakeLibraryRepository : LibraryRepository {
        val revision = MutableStateFlow(0L)
        val count = MutableStateFlow(0)
        var rows: List<LibraryItem> = emptyList()
        var nextLoadFailure: Throwable? = null
        var revisionFailuresRemaining: Int = 0
        var countFailuresRemaining: Int = 0
        var revisionSubscriptions: Int = 0
        var countSubscriptions: Int = 0
        val loadRequests = mutableListOf<LibraryPageKey?>()
        val documents = mutableMapOf<DocumentId, MutableStateFlow<SavedDocument?>>()
        val detailFailuresRemaining = mutableMapOf<DocumentId, Int>()
        val detailSubscriptions = mutableMapOf<DocumentId, Int>()

        override fun observeRevision(): Flow<Long> = flow {
            revisionSubscriptions += 1
            if (revisionFailuresRemaining > 0) {
                revisionFailuresRemaining -= 1
                throw IllegalStateException("revision stream failed")
            }
            emitAll(revision)
        }

        override fun observeSavedCount(): Flow<Int> = flow {
            countSubscriptions += 1
            if (countFailuresRemaining > 0) {
                countFailuresRemaining -= 1
                throw IllegalStateException("count stream failed")
            }
            emitAll(count)
        }

        override suspend fun loadPage(after: LibraryPageKey?, limit: Int): List<LibraryItem> {
            loadRequests += after
            nextLoadFailure?.let { failure ->
                nextLoadFailure = null
                throw failure
            }
            val ordered = rows.sortedWith(compareByDescending<LibraryItem> { it.savedAt }.thenByDescending { it.id.value })
            return ordered.asSequence()
                .filter { candidate ->
                    after == null || candidate.savedAt < after.savedAt ||
                        (candidate.savedAt == after.savedAt && candidate.id.value < after.documentId.value)
                }
                .take(limit)
                .toList()
        }

        override fun observeDocument(id: DocumentId): Flow<SavedDocument?> = flow {
            detailSubscriptions[id] = (detailSubscriptions[id] ?: 0) + 1
            val remaining = detailFailuresRemaining[id] ?: 0
            if (remaining > 0) {
                detailFailuresRemaining[id] = remaining - 1
                throw IllegalStateException("detail stream failed")
            }
            emitAll(documents.getOrPut(id) { MutableStateFlow(null) })
        }

        fun replace(values: List<LibraryItem>, emitRevision: Boolean = true) {
            rows = values
            count.value = values.size
            if (emitRevision) revision.value += 1
        }
    }

    companion object {
        private val BASE = Instant.parse("2026-09-16T10:00:00Z")

        private fun item(index: Int): LibraryItem {
            val suffix = index.toString().padStart(2, '0')
            return LibraryItem(
                id = DocumentId("doc-$suffix"),
                title = "Title $suffix",
                savedAt = BASE,
                snippet = "Snippet $suffix",
                sourceCount = 1,
                primarySourceName = "Source",
            )
        }

        private fun savedDocument(id: DocumentId, text: String): SavedDocument {
            val document = Document(
                id = id,
                canonicalUrl = "https://example.test/${id.value}",
                title = "Offline",
                normalizedText = text,
                contentHash = "hash-${text.hashCode()}",
                createdAt = BASE,
                updatedAt = BASE,
                disposition = ContentDisposition.SAVED,
            )
            return SavedDocument(document = document, provenance = emptyList())
        }
    }
}
