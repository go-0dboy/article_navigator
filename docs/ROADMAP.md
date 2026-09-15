# Development route

This route is intentionally architecture-preserving: each phase adds a vertical capability without invalidating earlier storage and module boundaries.

## Quality gate for every phase

No phase is complete until all of the following are true:

- new production behavior has automated tests;
- regressions for defects fixed during the phase are added as tests;
- persistence changes include migration/schema tests where applicable;
- collectors include deterministic fixture/contract tests and do not rely on live Internet for unit tests;
- Android-specific scheduling/UI behavior receives instrumentation or Robolectric tests where unit tests are insufficient;
- `gradle test` passes in GitHub Actions;
- `gradle lint` passes in GitHub Actions;
- `gradle assembleDebug` passes and publishes an installable APK artifact;
- the phase exit criteria below are demonstrated by tests or a reproducible manual acceptance scenario.

A PR with failing or missing required tests is not considered ready to merge.

## Test releases

- **Technical test APK:** produced by Phase 0 and continuously rebuilt by CI. Its purpose is reproducible Git/CI build and installation on a real Android device.
- **First functional test release:** after Phase 4. It must support the complete path: configure RSS/Atom source -> collect entries -> show Inbox -> open item -> reject/read-and-discard/save.
- Later phases add search, semantic retrieval, relevance filtering and optional AI without changing the canonical storage contract.

## Phase 0 — Repository and build foundation — COMPLETE

- multi-module Gradle project;
- Android application shell;
- stable Android toolchain pinned in version catalog;
- pure Kotlin domain/collector modules;
- canonical Room 3 schema foundation;
- GitHub Actions build, test, lint and APK artifact;
- foundation unit tests;
- architecture decisions and roadmap in Git.

**Exit:** a fresh checkout passes tests/lint, builds in CI and produces a debug APK artifact.

## Phase 1 — Persistence contracts and migrations — COMPLETE

- repository interfaces over canonical entities;
- full source/source-cursor/discovery/document persistence;
- schema export checked into Git;
- Room/JVM integration-test foundation for future migrations;
- transaction boundaries for item lifecycle transitions;
- document identity separated from provenance so one document may be discovered through multiple sources.

**Tests:** DAO/repository tests, real in-memory SQLite integration tests, uniqueness/idempotency foundations, transaction tests and committed schema gate.

**Exit:** source and document lifecycle is represented durably and schema changes are guarded by CI.

## Phase 2 — Collector framework and RSS/Atom — IN PROGRESS

- HTTP transport abstraction with JVM/Android-compatible OkHttp implementation;
- conditional requests using ETag/Last-Modified;
- one RSS/Atom adapter with format auto-detection;
- conservative canonical URL normalization;
- deterministic discovery identities so persistence remains idempotent;
- collector fixtures and contract tests;
- explicit retry/backoff/rate-limit policy objects for the Phase 3 scheduler.

**Tests:** RSS/Atom fixtures, malformed feeds, conditional HTTP, duplicate discovery identity, retry/backoff, URL canonicalization and HTTP transport tests. No unit test depends on live Internet.

**Exit:** configured feeds can be polled repeatably, conditional HTTP is preserved, and repeated feed contents resolve to the same discovery identities.

## Phase 3 — Android collection scheduler

- persisted `nextCheckAt` scheduler;
- one WorkManager wake-up path;
- network/battery constraints;
- bounded parallelism and per-source failure isolation;
- collection diagnostics.

**Tests:** scheduler selection/order, retry timing, source failure isolation and WorkManager integration tests.

**Exit:** feeds update automatically under Android background restrictions.

## Phase 4 — Ingestion and Inbox

- fetch and content extraction pipeline;
- deduplication by canonical URL and content hash;
- normalized text storage;
- Inbox UI;
- reject, read-and-discard, save transitions;
- compact `SeenFingerprint` retention.

**Tests:** extraction fixtures, deduplication/idempotency, state-transition tests, repository integration tests and Inbox UI tests.

**Exit:** automatic discovery reaches the user and unwanted items do not pollute permanent knowledge.

## Phase 5 — Durable Knowledge library

- saved-document reader;
- source provenance UI;
- notes and tags;
- document update/version policy;
- export and backup format.

**Tests:** provenance preservation, versioning, notes/tags, export/import round-trip and backup compatibility tests.

**Exit:** a saved item remains useful and traceable years later, independent of the original website.

## Phase 6 — Exact search

- Room 3 FTS5 tables;
- indexing pipeline;
- phrase/exact search and metadata filters;
- ranking tests.

**Tests:** exact terms, phrases, identifiers, filters, ranking fixtures and index rebuild tests.

**Exit:** titles, identifiers, phrases, notes and body text are searchable offline.

## Phase 7 — Semantic and hybrid search

- `EmbeddingProvider` interface;
- on-device embedding implementation;
- chunking with version metadata;
- replaceable vector index;
- hybrid rank fusion with FTS.

**Tests:** chunk stability/versioning, vector-index rebuild, semantic retrieval benchmark fixtures and hybrid ranking tests.

**Exit:** natural-language queries find conceptually related saved material offline.

## Phase 8 — Interest model and relevance filtering

- interests with positive/negative examples;
- relevance scoring before expensive processing;
- feedback signals from reject/read/save;
- explainable "why this was shown" metadata.

**Tests:** fixed relevance corpus, positive/negative feedback behavior, threshold tests and regression evaluation set.

**Exit:** the incoming stream is automatically prioritized for the user.

## Phase 9 — Additional source adapters

- generic static HTML extraction;
- explicit site adapters where required;
- structured REST sources;
- later: release feeds and other legally/technically appropriate integrations.

**Tests:** each adapter ships with captured fixtures and the shared collector contract suite.

**Exit:** new source types plug in without changes to the domain or scheduler.

## Phase 10 — Optional intelligence layer

- `Summarizer`, `Tagger`, `AnswerSynthesizer` interfaces;
- extractive/local/cloud implementations as available;
- summaries generated only after cheap relevance filtering;
- answers always retain citations back to saved source material.

**Tests:** provider contract tests, deterministic fake-provider integration tests and evaluation datasets for non-deterministic providers.

**Exit:** AI improves reading/search without becoming a dependency of the archive.

## Phase 11 — Ask my knowledge

- retrieve with hybrid search;
- answer synthesis over selected chunks;
- source-backed responses;
- evaluation dataset for retrieval and answer quality.

**Tests:** retrieval benchmark, citation/source-grounding checks and answer-quality eval set.

## Phase 12 — Optional cloud collector and multi-device sync

- shared collector runtime;
- outbox/revision protocol;
- encrypted sync design;
- conflict handling;
- cloud remains optional for core local functionality.

**Tests:** outbox delivery/idempotency, conflict fixtures, offline/online transitions and end-to-end sync tests.

## Non-negotiable engineering requirements

- no feature may silently discard provenance;
- canonical data migrations are tested before release;
- derived indexes can be deleted and rebuilt;
- collectors must be idempotent;
- every phase must add and pass its required automated tests;
- CI must remain green on every merge;
- AI-generated metadata never replaces saved source text;
- user export must remain possible without a proprietary server.
