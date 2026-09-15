# Development route

This route is intentionally architecture-preserving: each phase adds a vertical capability without invalidating earlier storage and module boundaries.

## Phase 0 — Repository and build foundation (in progress)

- multi-module Gradle project;
- Android application shell;
- current stable Android toolchain pinned in version catalog;
- pure Kotlin domain/collector modules;
- canonical Room 3 schema foundation;
- GitHub Actions build, test, lint and APK artifact;
- architecture decisions and roadmap in Git.

**Exit:** a fresh checkout builds in CI and produces a debug APK.

## Phase 1 — Persistence contracts and migrations

- repository interfaces over canonical entities;
- full source/source-cursor/discovery/document persistence;
- schema export checked into Git;
- migration test harness;
- transaction boundaries for item lifecycle transitions.

**Exit:** source and document lifecycle survives process restart and schema is migration-tested.

## Phase 2 — Collector framework and RSS/Atom

- HTTP client abstraction;
- conditional requests using ETag/Last-Modified;
- RSS and Atom adapters;
- canonical URL normalization;
- collector fixtures and contract tests;
- retry/backoff/rate-limit policy.

**Exit:** configured feeds can be polled idempotently and new entries are discovered once.

## Phase 3 — Android collection scheduler

- persisted `nextCheckAt` scheduler;
- one WorkManager wake-up path;
- network/battery constraints;
- bounded parallelism and per-source failure isolation;
- collection diagnostics.

**Exit:** feeds update automatically under Android background restrictions.

## Phase 4 — Ingestion and Inbox

- fetch and content extraction pipeline;
- deduplication by canonical URL and content hash;
- normalized text storage;
- Inbox UI;
- reject, read-and-discard, save transitions;
- compact `SeenFingerprint` retention.

**Exit:** automatic discovery reaches the user and unwanted items do not pollute permanent knowledge.

## Phase 5 — Durable Knowledge library

- saved-document reader;
- source provenance UI;
- notes and tags;
- document update/version policy;
- export and backup format.

**Exit:** a saved item remains useful and traceable years later, independent of the original website.

## Phase 6 — Exact search

- Room 3 FTS5 tables;
- indexing pipeline;
- phrase/exact search and metadata filters;
- ranking tests.

**Exit:** titles, identifiers, phrases, notes and body text are searchable offline.

## Phase 7 — Semantic and hybrid search

- `EmbeddingProvider` interface;
- on-device embedding implementation;
- chunking with version metadata;
- replaceable vector index;
- hybrid rank fusion with FTS.

**Exit:** natural-language queries find conceptually related saved material offline.

## Phase 8 — Interest model and relevance filtering

- interests with positive/negative examples;
- relevance scoring before expensive processing;
- feedback signals from reject/read/save;
- explainable "why this was shown" metadata.

**Exit:** the incoming stream is automatically prioritized for the user.

## Phase 9 — Additional source adapters

- generic static HTML extraction;
- explicit site adapters where required;
- structured REST sources;
- later: release feeds and other legally/technically appropriate integrations.

**Exit:** new source types plug in without changes to the domain or scheduler.

## Phase 10 — Optional intelligence layer

- `Summarizer`, `Tagger`, `AnswerSynthesizer` interfaces;
- extractive/local/cloud implementations as available;
- summaries generated only after cheap relevance filtering;
- answers always retain citations back to saved source material.

**Exit:** AI improves reading/search without becoming a dependency of the archive.

## Phase 11 — Ask my knowledge

- retrieve with hybrid search;
- answer synthesis over selected chunks;
- source-backed responses;
- evaluation dataset for retrieval and answer quality.

## Phase 12 — Optional cloud collector and multi-device sync

- shared collector runtime;
- outbox/revision protocol;
- encrypted sync design;
- conflict handling;
- cloud remains optional for core local functionality.

## Non-negotiable engineering requirements

- no feature may silently discard provenance;
- canonical data migrations are tested before release;
- derived indexes can be deleted and rebuilt;
- collectors must be idempotent;
- CI must remain green on every merge;
- AI-generated metadata never replaces saved source text;
- user export must remain possible without a proprietary server.
