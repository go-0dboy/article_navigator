package io.github.go0dboy.articlenavigator.core.model

import java.time.Instant

/** Stable keyset cursor for saved documents ordered by savedAt DESC, id DESC. */
data class LibraryPageKey(
    val savedAt: Instant,
    val documentId: DocumentId,
)

/** Lightweight projection for the library list. Full normalized text is loaded only in detail. */
data class LibraryItem(
    val id: DocumentId,
    val title: String,
    val savedAt: Instant,
    val snippet: String,
    val sourceCount: Int,
    val primarySourceName: String?,
)

data class SavedDocument(
    val document: Document,
    val provenance: List<DocumentProvenance>,
)

/** Stable keyset cursor for Inbox ordered by createdAt DESC, id DESC. */
data class InboxPageKey(
    val createdAt: Instant,
    val itemId: InboxItemId,
)
