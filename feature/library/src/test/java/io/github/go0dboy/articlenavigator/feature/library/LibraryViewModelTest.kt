package io.github.go0dboy.articlenavigator.feature.library

import io.github.go0dboy.articlenavigator.core.data.LibraryRepository
import io.github.go0dboy.articlenavigator.core.model.ContentDisposition
import io.github.go0dboy.articlenavigator.core.model.Document
import io.github.go0dboy.articlenavigator.core.model.DocumentId
import io.github.go0dboy.articlenavigator.core.model.LibraryItem
import io.github.go0dboy.articlenavigator.core.model.LibraryPageKey
import io.github.go0dboy.articlenavigator.core.model.SavedDocument
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
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryViewModelTest {
    @Test
    fun pagingAndBackgroundRevisionRefreshStateWithoutManualReload() = runTest {
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
            assertEquals(20, viewModel.state.value.items.size)
            assertEquals(newest.id, viewModel.state.value.items.first().id)
            assertTrue(viewModel.state.value.hasMore)
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
            val document = Document(
                id = id,
                canonicalUrl = "https://example.test/offline",
                title = "Offline",
                normalizedText = "Saved local text",
                contentHash = "hash",
                createdAt = BASE,
                updatedAt = BASE,
                disposition = ContentDisposition.SAVED,
            )
            val saved = SavedDocument(document = document, provenance = emptyList())
            repository.documents[id] = MutableStateFlow(saved)
            val viewModel = LibraryViewModel(repository)

            viewModel.openDocument(id)
            advanceUntilIdle()

            assertEquals("Saved local text", viewModel.state.value.selectedDocument?.document?.normalizedText)
            assertFalse(viewModel.state.value.detailLoading)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class FakeLibraryRepository : LibraryRepository {
        val revision = MutableStateFlow(0L)
        val count = MutableStateFlow(0)
        var rows: List<LibraryItem> = emptyList()
        val documents = mutableMapOf<DocumentId, MutableStateFlow<SavedDocument?>>()

        override fun observeRevision(): Flow<Long> = revision
        override fun observeSavedCount(): Flow<Int> = count

        override suspend fun loadPage(after: LibraryPageKey?, limit: Int): List<LibraryItem> {
            val ordered = rows.sortedWith(compareByDescending<LibraryItem> { it.savedAt }.thenByDescending { it.id.value })
            return ordered.asSequence()
                .filter { candidate ->
                    after == null || candidate.savedAt < after.savedAt ||
                        (candidate.savedAt == after.savedAt && candidate.id.value < after.documentId.value)
                }
                .take(limit)
                .toList()
        }

        override fun observeDocument(id: DocumentId): Flow<SavedDocument?> =
            documents.getOrPut(id) { MutableStateFlow(null) }

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
    }
}
