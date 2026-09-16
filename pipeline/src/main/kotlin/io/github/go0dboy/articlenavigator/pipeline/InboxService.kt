package io.github.go0dboy.articlenavigator.pipeline

import io.github.go0dboy.articlenavigator.core.data.InboxRepository
import io.github.go0dboy.articlenavigator.core.model.ContentDisposition
import io.github.go0dboy.articlenavigator.core.model.DocumentId
import io.github.go0dboy.articlenavigator.core.model.InboxItem
import io.github.go0dboy.articlenavigator.core.model.InboxItemId
import java.time.Clock

class InboxService(
    private val repository: InboxRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun list(limit: Int = 100): List<InboxItem> = repository.listPending(limit)

    suspend fun get(id: InboxItemId): InboxItem? = repository.findById(id)

    /** Returns false if another committed action already consumed the Inbox item. */
    suspend fun reject(id: InboxItemId): Boolean = discard(id, ContentDisposition.REJECTED)

    /** Returns false if another committed action already consumed the Inbox item. */
    suspend fun readAndDiscard(id: InboxItemId): Boolean = discard(id, ContentDisposition.READ_AND_DISCARDED)

    /**
     * Save is a single repository transaction. A concurrent prior Save/Reject is surfaced as
     * not-found/already-handled instead of reporting a false successful save.
     */
    suspend fun save(id: InboxItemId): DocumentId = repository.saveCurrent(
        id = id,
        at = clock.instant(),
        parserVersion = PARSER_VERSION,
    ) ?: throw NoSuchElementException("Inbox item ${id.value} not found or already handled")

    private suspend fun discard(id: InboxItemId, disposition: ContentDisposition): Boolean =
        repository.discardCurrent(id, disposition, clock.instant())

    companion object {
        const val PARSER_VERSION: String = "default-content-extractor-v1"
    }
}
