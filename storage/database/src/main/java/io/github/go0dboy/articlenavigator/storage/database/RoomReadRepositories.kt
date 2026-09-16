package io.github.go0dboy.articlenavigator.storage.database

import io.github.go0dboy.articlenavigator.core.data.InboxPagingRepository
import io.github.go0dboy.articlenavigator.core.data.LibraryRepository
import io.github.go0dboy.articlenavigator.core.model.DocumentId
import io.github.go0dboy.articlenavigator.core.model.InboxItem
import io.github.go0dboy.articlenavigator.core.model.InboxPageKey
import io.github.go0dboy.articlenavigator.core.model.LibraryItem
import io.github.go0dboy.articlenavigator.core.model.LibraryPageKey
import io.github.go0dboy.articlenavigator.core.model.SavedDocument
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class RoomLibraryRepository(
    private val dao: LibraryReadDao,
) : LibraryRepository {
    override fun observeRevision(): Flow<Long> = dao.observeRevision()

    override fun observeSavedCount(): Flow<Int> = dao.observeSavedCount()

    override suspend fun loadPage(after: LibraryPageKey?, limit: Int): List<LibraryItem> {
        require(limit > 0)
        return dao.loadPage(
            afterSavedAtEpochMillis = after?.savedAt?.toEpochMilli(),
            afterDocumentId = after?.documentId?.value,
            limit = limit,
        ).map { row ->
            LibraryItem(
                id = DocumentId(row.id),
                title = row.title,
                savedAt = Instant.ofEpochMilli(row.savedAtEpochMillis),
                snippet = row.snippet,
                sourceCount = row.sourceCount,
                primarySourceName = row.primarySourceName,
            )
        }
    }

    override fun observeDocument(id: DocumentId): Flow<SavedDocument?> = combine(
        dao.observeSavedDocument(id.value),
        dao.observeProvenance(id.value),
    ) { document, provenance ->
        document?.let {
            SavedDocument(
                document = it.toDomain(),
                provenance = provenance.map(DocumentProvenanceEntity::toDomain),
            )
        }
    }
}

class RoomInboxPagingRepository(
    private val dao: InboxPagingDao,
) : InboxPagingRepository {
    override fun observeRevision(): Flow<Long> = dao.observeRevision()

    override fun observePendingCount(): Flow<Int> = dao.observePendingCount()

    override suspend fun loadPage(after: InboxPageKey?, limit: Int): List<InboxItem> {
        require(limit > 0)
        return dao.loadPage(
            afterCreatedAtEpochMillis = after?.createdAt?.toEpochMilli(),
            afterItemId = after?.itemId?.value,
            limit = limit,
        ).map(InboxItemEntity::toDomain)
    }
}
