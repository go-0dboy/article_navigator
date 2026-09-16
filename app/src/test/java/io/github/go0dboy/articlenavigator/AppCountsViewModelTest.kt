package io.github.go0dboy.articlenavigator

import io.github.go0dboy.articlenavigator.core.data.InboxPagingRepository
import io.github.go0dboy.articlenavigator.core.data.LibraryRepository
import io.github.go0dboy.articlenavigator.core.model.DocumentId
import io.github.go0dboy.articlenavigator.core.model.InboxItem
import io.github.go0dboy.articlenavigator.core.model.InboxPageKey
import io.github.go0dboy.articlenavigator.core.model.LibraryItem
import io.github.go0dboy.articlenavigator.core.model.LibraryPageKey
import io.github.go0dboy.articlenavigator.core.model.SavedDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppCountsViewModelTest {
    @Test
    fun countFailureIsVisibleAndRetryReconnectsWithoutDuplicateSubscriptions() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val inbox = FakeInboxCountsRepository(initialCount = 4)
            val library = FakeLibraryCountsRepository(initialCount = 7).apply {
                failuresRemaining = 1
            }
            val viewModel = AppCountsViewModel(inbox, library)

            advanceUntilIdle()
            assertEquals("library count failed", viewModel.state.value.observationError)
            assertEquals(1, inbox.subscriptions)
            assertEquals(1, library.subscriptions)

            viewModel.retry()
            advanceUntilIdle()

            assertNull(viewModel.state.value.observationError)
            assertEquals(4, viewModel.state.value.inboxCount)
            assertEquals(7, viewModel.state.value.libraryCount)
            assertEquals(2, inbox.subscriptions)
            assertEquals(2, library.subscriptions)

            inbox.count.value = 5
            library.count.value = 8
            advanceUntilIdle()

            assertEquals(5, viewModel.state.value.inboxCount)
            assertEquals(8, viewModel.state.value.libraryCount)
            assertEquals(2, inbox.subscriptions)
            assertEquals(2, library.subscriptions)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun cancellationIsNotReportedAsDatabaseErrorAndCanBeRetried() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val inbox = FakeInboxCountsRepository(initialCount = 2)
            val library = FakeLibraryCountsRepository(initialCount = 3).apply {
                cancellationsRemaining = 1
            }
            val viewModel = AppCountsViewModel(inbox, library)

            advanceUntilIdle()
            assertNull(viewModel.state.value.observationError)
            assertEquals(1, library.subscriptions)

            viewModel.retry()
            advanceUntilIdle()

            assertNull(viewModel.state.value.observationError)
            assertEquals(2, viewModel.state.value.inboxCount)
            assertEquals(3, viewModel.state.value.libraryCount)
            assertEquals(2, inbox.subscriptions)
            assertEquals(2, library.subscriptions)
        } finally {
            Dispatchers.resetMain()
        }
    }
}

private class FakeInboxCountsRepository(initialCount: Int) : InboxPagingRepository {
    val count = MutableStateFlow(initialCount)
    var subscriptions = 0

    override fun observePendingCount(): Flow<Int> = flow {
        subscriptions += 1
        emitAll(count)
    }

    override fun observeRevision(): Flow<Long> = emptyFlow()

    override suspend fun loadPage(after: InboxPageKey?, limit: Int): List<InboxItem> = emptyList()
}

private class FakeLibraryCountsRepository(initialCount: Int) : LibraryRepository {
    val count = MutableStateFlow(initialCount)
    var subscriptions = 0
    var failuresRemaining = 0
    var cancellationsRemaining = 0

    override fun observeSavedCount(): Flow<Int> = flow {
        subscriptions += 1
        if (cancellationsRemaining > 0) {
            cancellationsRemaining -= 1
            throw CancellationException("cancel count observation")
        }
        if (failuresRemaining > 0) {
            failuresRemaining -= 1
            throw IllegalStateException("library count failed")
        }
        emitAll(count)
    }

    override fun observeRevision(): Flow<Long> = emptyFlow()

    override suspend fun loadPage(after: LibraryPageKey?, limit: Int): List<LibraryItem> = emptyList()

    override fun observeDocument(id: DocumentId): Flow<SavedDocument?> = flowOf(null)
}
