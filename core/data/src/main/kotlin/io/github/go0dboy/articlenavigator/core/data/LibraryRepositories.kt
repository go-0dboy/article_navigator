package io.github.go0dboy.articlenavigator.core.data

import io.github.go0dboy.articlenavigator.core.model.DocumentId
import io.github.go0dboy.articlenavigator.core.model.InboxItem
import io.github.go0dboy.articlenavigator.core.model.InboxPageKey
import io.github.go0dboy.articlenavigator.core.model.LibraryItem
import io.github.go0dboy.articlenavigator.core.model.LibraryPageKey
import io.github.go0dboy.articlenavigator.core.model.SavedDocument
import kotlinx.coroutines.flow.Flow

/** Read-only saved-material projection. Runtime writes still go through Inbox transactional APIs. */
interface LibraryRepository {
    fun observeRevision(): Flow<Long>
    fun observeSavedCount(): Flow<Int>
    suspend fun loadPage(after: LibraryPageKey?, limit: Int): List<LibraryItem>
    fun observeDocument(id: DocumentId): Flow<SavedDocument?>
}

/** Read-only Inbox paging/count projection; Save/Reject actions remain on InboxRepository/InboxService. */
interface InboxPagingRepository {
    fun observeRevision(): Flow<Long>
    fun observePendingCount(): Flow<Int>
    suspend fun loadPage(after: InboxPageKey?, limit: Int): List<InboxItem>
}
