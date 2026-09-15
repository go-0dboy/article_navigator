# Architecture

## Product objective

Article Navigator continuously observes user-configured information sources and turns useful findings into a durable personal knowledge base. The primary product promise is: **interesting information can be found again later, together with where and when it originally came from.**

## Architectural principles

1. **Local-first.** The on-device canonical database is the source of truth.
2. **Provenance is mandatory.** Every stored document retains source, URL, discovery time, publication time when available, content hash and parser version.
3. **Inbox and knowledge are different lifecycles.** Discovered material is temporary until the user saves it.
4. **Derived data is rebuildable.** FTS indexes, vector indexes, embeddings, summaries and generated tags must be reproducible from canonical data.
5. **Collectors are platform-independent.** Source discovery and parsing contracts live in pure Kotlin/JVM modules so they can later run from Android or a server worker.
6. **Background execution is a trigger, not the scheduler.** Android WorkManager will wake a persisted scheduler; scheduling state belongs in the database.
7. **AI is optional infrastructure.** Core collection, storage and exact search continue to work without a generative model or cloud service.
8. **Idempotency first.** Reprocessing the same remote item must not create duplicate knowledge records.

## Logical architecture

```text
Sources
  |
  v
Collector adapters ----> persisted source cursor
  |
  v
Discovery / Fetch
  |
  v
Ingestion pipeline
  |- canonicalize URL
  |- deduplicate
  |- extract content
  |- normalize
  |- detect language
  |- evaluate relevance
  `- optional summary
  |
  v
Inbox ---------------------> reject/read -> SeenFingerprint
  |
  `------------------------> save
                              |
                              v
                       Knowledge store
                              |
                     +--------+---------+
                     |                  |
                    FTS             Vector index
                     |                  |
                     +--------+---------+
                              |
                         Hybrid search
```

## Current modules

- `app`: Android application shell and composition root.
- `core:model`: platform-independent domain model and identity types.
- `collector:api`: stable collector contracts; no Android dependencies.
- `pipeline`: processing-stage contracts for the ingestion pipeline.
- `storage:database`: canonical Room 3 schema and DAOs.

Modules are added when they own real implementation. Planned boundaries are documented in `ROADMAP.md`; empty modules are deliberately avoided because they create build cost without enforcing additional architecture.

## Storage model

The canonical database stores user-owned facts and normalized document text. Search and AI outputs are secondary indexes/artifacts.

Canonical records include:

- sources and source cursors;
- discovered item lifecycle metadata;
- normalized documents;
- document versions;
- seen fingerprints for rejected/read-but-not-saved items;
- interests and user feedback;
- future user notes/tags and sync metadata.

Large raw fetch payloads will be retained only while required for reliable reprocessing and debugging; saved normalized source text is retained with the knowledge item.

## Search strategy

Search will be hybrid:

- FTS5/BM25 for exact terms, identifiers and quoted phrases;
- local embeddings for semantic recall;
- rank fusion and metadata filters for final ranking.

The vector index will be isolated behind a `VectorIndex` contract and may live in a separate database. Replacing the embedding model or vector implementation must require re-indexing, not migrating canonical knowledge data.

## Android background work

WorkManager is suitable for deferrable persistent work, but Android does not guarantee exact periodic execution. A single worker will wake the collection scheduler, which queries persisted `nextCheckAt` values and processes due sources under network/battery constraints.

This design also permits a future optional cloud collector to execute the same collector/pipeline contracts without changing source adapters.

## Future sync

Canonical identifiers are globally unique strings. Before cloud sync is introduced, mutable user-owned records will gain revision/deletion metadata and an outbox. Sync is an optional transport over local-first data, not a replacement for it.
