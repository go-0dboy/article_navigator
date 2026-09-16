# Development route

This route is architecture-preserving: each stage adds one product capability without weakening canonical storage, processing ownership, transactional Inbox actions or historical provenance.

## Quality gate for every stage

A stage is not complete until all applicable checks are true:

- new production behavior has automated tests;
- every reproducible defect fixed in the stage has a regression test;
- schema changes have a new migration and a newly exported Room schema; released schema files are not edited;
- migration compatibility is tested from supported historical versions;
- collectors/parser tests are deterministic and do not require live Internet;
- Android UI/background behavior has unit, Robolectric or WorkManager tests where appropriate;
- `./gradlew test --stacktrace` passes in GitHub Actions;
- `./gradlew lint --stacktrace` passes in GitHub Actions;
- `./gradlew assembleDebug --stacktrace` passes and the APK artifact is published;
- Room schema consistency and wrapper/toolchain gates remain enabled;
- exit criteria are demonstrated by tests or a reproducible device scenario.

No failing or bypassed quality gate is accepted for merge.

## Completed foundation

### Repository, storage and collector foundation — COMPLETE

Implemented across the earlier phases:

- reproducible multi-module Android/Kotlin build;
- Room 3 canonical database with committed schema history;
- source/source-cursor/discovery/document/provenance persistence;
- RSS/Atom collection and deterministic fixture tests;
- OkHttp transport and conditional collection state;
- WorkManager Android wake-up path and persisted scheduling state;
- discovery/fetch/normalization pipeline;
- Inbox with Save / Reject / Read-and-discard;
- canonical URL/content deduplication;
- compact seen fingerprints.

### Reliability hardening — COMPLETE

The reliability stage is the baseline for all future product work:

- persisted source-collection ownership;
- persisted article-processing ownership;
- stale owner/version writes rejected;
- transactional discovery/source commits;
- transactional Inbox Save/Reject/Read-and-discard using current origins;
- durable provenance snapshots with restrictive source relationships;
- exact raw response bytes + Content-Type + final redirect URL retained for crash recovery while raw is valid;
- file-backed close/reopen recovery tests;
- bounded concurrency and article network-policy filtering;
- shared collection+ingestion execution deadline;
- infrastructure errors remain distinct from ordinary article failures;
- historical v4 compatibility and complete migration-chain tests.

Scheduler continuation/backoff refinement is intentionally tracked separately from product UI work because the current worker uses one WorkManager `runAttemptCount` for successful continuation and infrastructure retry.

## Current stage — Saved Library and offline reading — IN VALIDATION (PR #17)

Product goal: **Save a material, see it in Library, read the saved text without network access, and understand where it came from.**

Implemented in the current PR:

- strict content decoding with precedence `BOM -> HTTP charset -> document metadata -> UTF-8`;
- exact Cyrillic/Windows-1251/BOM/unknown/malformed decoding regression fixtures;
- parser identity captured with extracted Inbox text;
- schema v6 migration so existing v5 pending Inbox rows keep a conservative parser-v1 identity;
- saved-library read model with title, saved date, short snippet and source summary;
- stable keyset paging and exact independent count;
- full local document detail loaded only when opened;
- all saved provenance snapshots displayed from historical data;
- offline reading independent of original-site availability;
- external URLs opened only on explicit user action;
- Inbox and Library Flow/ViewModel/lifecycle-aware observation;
- removal of the two-second MainActivity storage polling loop;
- Save -> saved-document navigation;
- restart/file-backed tests for raw extraction, saved content and provenance;
- background invalidation and paging ViewModel tests.

Explicitly out of scope for this stage:

- document deletion;
- text editing;
- complex version-management UI;
- notes and tags;
- cloud synchronization;
- semantic search, embeddings or LLM functions;
- scheduler retry/backoff redesign.

**Exit:** final PR HEAD passes tests/schema/lint/assemble/APK gates and the device scenario proves Save -> Library -> offline reopen -> provenance.

## Next stage 1 — Exact offline full-text search

Implement exact search before semantic retrieval:

- Room/SQLite FTS tables as derived indexes;
- indexing of saved title/body and appropriate metadata;
- exact terms, quoted phrases and identifiers;
- deterministic ordering and metadata filters;
- rebuild command/path from canonical saved records;
- no dependency on network or an AI provider.

**Tests:** exact-term fixtures, phrase/identifier cases, equal-score ordering, filters, index update after Save, and complete index rebuild equivalence.

**Exit:** every saved text is discoverable offline through deterministic full-text search.

## Next stage 2 — Export and restore

Create a portable archive path independent of a proprietary service:

- documented export format for saved documents, versions and provenance;
- explicit format/schema version;
- restore into a clean installation;
- duplicate/idempotency policy;
- validation before replacing/adding canonical data;
- no loss of parser version or historical Source snapshots.

**Tests:** export -> clean database -> restore -> export round-trip with equivalent canonical content/provenance, multiple origins, Unicode/Cyrillic and older supported records.

**Exit:** a user can recover the same saved knowledge/provenance on a clean installation.

## Next stage 3 — Manual save through Android Share

Allow explicit capture of a URL sent from a browser or another Android application:

- Android Share target for HTTP/HTTPS URLs;
- canonical validation and deduplication;
- manual item enters the same ownership-aware fetch/extract/finalisation pipeline instead of a second storage path;
- user can review/save using the same Inbox/Library model;
- failures are visible and retryable without duplicate documents.

**Tests:** Share intent parsing, invalid/non-http input, duplicate URL/content, offline/failure recovery and end-to-end manual URL -> Inbox -> Save -> Library.

**Exit:** a user can send a link to Article Navigator through Android Share and preserve it through the same reliable pipeline.

## Later stages

### Semantic and hybrid retrieval

Only after exact search is proven:

- replaceable `EmbeddingProvider`;
- chunk/version metadata;
- rebuildable vector index;
- hybrid fusion with exact FTS;
- retrieval evaluation corpus.

### Interest/relevance model

- explicit interests and positive/negative feedback;
- cheap relevance scoring before expensive optional processing;
- explainable reason metadata;
- regression evaluation corpus.

### Additional source adapters

- static HTML/manual-page adapters where technically and legally appropriate;
- structured REST/release feeds;
- shared collector contract tests for every adapter.

### Optional intelligence layer

- summarizer/tagger/answer interfaces only after archive/search fundamentals;
- local/cloud implementations optional;
- generated metadata never replaces saved source text;
- answers retain references to saved material.

### Optional multi-device sync

- revision/outbox protocol;
- encrypted transport design;
- conflict behavior;
- local database remains canonical for offline operation.

## Non-negotiable engineering requirements

- no feature silently discards provenance;
- stale processing owners cannot commit;
- UI cannot bypass transactional Inbox lifecycle methods;
- canonical migrations are tested before release;
- historical schema exports are immutable;
- derived indexes can be deleted and rebuilt;
- collector/discovery processing remains idempotent;
- saved normalized text remains readable without network access;
- user export must remain possible without a proprietary server;
- AI-generated data never becomes the only copy of source-derived text;
- CI quality gates remain enabled on every merge.
