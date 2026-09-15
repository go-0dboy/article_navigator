package io.github.go0dboy.articlenavigator.storage.database

import io.github.go0dboy.articlenavigator.core.model.ContentDisposition
import io.github.go0dboy.articlenavigator.core.model.Document
import io.github.go0dboy.articlenavigator.core.model.DocumentId
import io.github.go0dboy.articlenavigator.core.model.DocumentProvenance
import io.github.go0dboy.articlenavigator.core.model.DocumentVersion
import io.github.go0dboy.articlenavigator.core.model.SeenFingerprint
import io.github.go0dboy.articlenavigator.core.model.SourceId
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RoomKnowledgeRepositoryTest {
    @Test
    fun persistStoresDocumentVersionProvenanceAndFingerprint() = runTest {
        val dao = FakeDocumentDao()
        val repository = RoomKnowledgeRepository(dao)
        val now = Instant.parse("2026-09-15T08:00:00Z")
        val document = Document(
            id = DocumentId("doc-1"),
            canonicalUrl = "https://example.test/article",
            title = "Article",
            normalizedText = "Body",
            contentHash = "hash-v1",
            createdAt = now,
            updatedAt = now,
            disposition = ContentDisposition.SAVED,
        )
        val version = DocumentVersion(document.id, 1, "hash-v1", "Body", now, "parser-v1")
        val provenance = DocumentProvenance(document.id, SourceId("source-1"), "https://example.test/article", now, now)
        val fingerprint = SeenFingerprint("url-hash", "hash-v1", SourceId("source-1"), now, ContentDisposition.SAVED)

        repository.persist(document, version, provenance, fingerprint)

        assertEquals(document, repository.findById(document.id))
        assertEquals(listOf(version), repository.versions(document.id))
        assertEquals(listOf(provenance), repository.provenance(document.id))
        assertEquals(fingerprint, repository.findSeen("url-hash", SourceId("source-1")))
    }

    @Test
    fun dispositionCanChangeWithoutLosingDocument() = runTest {
        val dao = FakeDocumentDao()
        val repository = RoomKnowledgeRepository(dao)
        val now = Instant.parse("2026-09-15T08:00:00Z")
        val document = Document(
            id = DocumentId("doc-2"),
            canonicalUrl = "https://example.test/two",
            title = "Two",
            normalizedText = "Body",
            contentHash = "hash",
            createdAt = now,
            updatedAt = now,
            disposition = ContentDisposition.PENDING,
        )
        dao.upsert(document.toEntity())

        repository.markDisposition(document.id, ContentDisposition.SAVED, now.plusSeconds(5))

        val stored = repository.findById(document.id)
        assertNotNull(stored)
        assertEquals(ContentDisposition.SAVED, stored?.disposition)
    }
}

private class FakeDocumentDao : DocumentDao {
    private val documents = linkedMapOf<String, DocumentEntity>()
    private val versions = linkedMapOf<Pair<String, Long>, DocumentVersionEntity>()
    private val provenance = linkedMapOf<Pair<String, String>, DocumentProvenanceEntity>()
    private val fingerprints = linkedMapOf<Pair<String, String>, SeenFingerprintEntity>()

    override suspend fun upsert(document: DocumentEntity) { documents[document.id] = document }
    override suspend fun upsertVersion(version: DocumentVersionEntity) { versions[version.documentId to version.version] = version }
    override suspend fun upsertProvenance(provenance: DocumentProvenanceEntity) { this.provenance[provenance.documentId to provenance.sourceId] = provenance }
    override suspend fun upsertFingerprint(fingerprint: SeenFingerprintEntity) { fingerprints[fingerprint.canonicalUrlHash to fingerprint.sourceId] = fingerprint }
    override suspend fun findById(id: String): DocumentEntity? = documents[id]
    override suspend fun findByCanonicalUrl(canonicalUrl: String): DocumentEntity? = documents.values.firstOrNull { it.canonicalUrl == canonicalUrl }
    override suspend fun versions(documentId: String): List<DocumentVersionEntity> = versions.values.filter { it.documentId == documentId }.sortedBy { it.version }
    override suspend fun provenance(documentId: String): List<DocumentProvenanceEntity> = provenance.values.filter { it.documentId == documentId }.sortedBy { it.discoveredAtEpochMillis }
    override suspend fun findFingerprint(canonicalUrlHash: String, sourceId: String): SeenFingerprintEntity? = fingerprints[canonicalUrlHash to sourceId]
    override suspend fun updateDisposition(id: String, disposition: String, updatedAtEpochMillis: Long) {
        documents[id] = documents.getValue(id).copy(disposition = disposition, updatedAtEpochMillis = updatedAtEpochMillis)
    }
}
