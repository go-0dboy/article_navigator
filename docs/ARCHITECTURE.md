# Architecture

## Product objective

Article Navigator observes user-configured sources, lets the user decide what is worth keeping, and preserves saved normalized text together with durable historical provenance. The current product promise is: **a saved material can be read later from the local database, even when the original website is unavailable, and the user can still see where the material came from.**

## Architectural principles

1. **Local-first.** The on-device Room/SQLite database is the source of truth for saved material.
2. **Provenance is mandatory.** Saved documents retain source snapshots and original/final URLs instead of looking up mutable current Source settings.
3. **Inbox and Library are different lifecycles.** Fetched material is pending until the user saves it; rejected/read-and-discarded material does not become a saved document.
4. **Transactional user actions.** Save/Reject/Read-and-discard consume the current Inbox row and its current origins in one Room transaction.
5. **Processing ownership is persisted.** Article processing requires an expiring token; an old network request may finish, but an expired/replaced owner cannot commit raw/intermediate/final state.
6. **Content interpretation is versioned.** The parser version is captured with extracted Inbox text and copied into a new `DocumentVersion` when that text is saved.
7. **Historical schema is immutable.** Released Room schema exports are never rewritten; new schema versions use explicit migrations and compatibility tests.
8. **UI observation is reactive.** Inbox and Library are read through Flow/ViewModel/lifecycle-aware state, not timer polling.
9. **List reads are bounded.** List projections do not load full saved text; both Inbox and Library use stable keyset paging and independent counts.
10. **Derived indexes are rebuildable.** Future FTS/vector indexes are secondary to canonical saved text and provenance.
11. **AI is optional future infrastructure.** Preservation and offline reading do not require a model or cloud service.

## Current logical flow

```text
Sources
  |
  v
Collector adapters ----> persisted source cursor / scheduler state
  |
  v
Discovery
  |
  v
Article processing lease
  |
  +--> HTTP fetch ----> temporary durable raw bytes + Content-Type + final URL
  |                         |
  |                         `---- crash/reopen resume while valid
  v
strict decode / extract / normalize
  |
  v
atomic finalisation
  |- already saved/dismissed -> provenance/fingerprint update
  `- pending -> Inbox + origin snapshot
                 |
      +----------+-----------+
      |          |           |
   reject   read/discard    save
      |          |           |
      +----------+           v
   SeenFingerprint      documents
                        document_versions
                        document_provenance
                              |
                              v
                      Saved Library UI
                              |
                      local offline detail
```

## Modules and boundaries

- `app`: Android composition root, top-level navigation and explicit external-link intent.
- `core:model`: platform-independent identities, source/discovery/content/document/read models.
- `core:data`: repository contracts, including ownership-aware processing writes and read-only Library/Inbox projections.
- `core:network`: HTTP transport primitives.
- `collector:api`: collector/source adapter contracts.
- `collector:rss`: RSS/Atom adapter.
- `pipeline`: article processing, content decoding/extraction and Inbox user service.
- `scheduler:core`: persisted collection orchestration and shared execution deadline.
- `scheduler:android`: WorkManager integration.
- `storage:database`: canonical Room schema, migrations, transactional DAOs and observable read DAOs.
- `feature:inbox`: Inbox ViewModel/state/lazy paged UI; write actions delegate to `InboxService`.
- `feature:library`: saved-library ViewModel/state/lazy paged UI and offline document detail.
- `feature:sources`: source-management UI.

Feature modules do not own canonical writes. In particular, Library is read-only and Inbox UI cannot bypass `saveCurrent`/`discardCurrent` or article-processing ownership.

## Content decoding and parser identity

`DefaultContentExtractor` applies this precedence:

1. recognized BOM;
2. charset from HTTP `Content-Type`;
3. HTML/XML in-document charset metadata;
4. UTF-8 fallback.

The selected charset is decoded strictly. Unsupported charset names, malformed input and unmappable bytes fail extraction rather than silently producing replacement characters that could then be saved as valid content.

Current extracted Inbox content records `default-content-extractor-v2`. Schema v6 adds `inbox_items.parserVersion`; pending rows migrated from schema v5 are conservatively labelled `default-content-extractor-v1`. Save copies the Inbox value into `document_versions.parserVersion`. Existing saved versions are not rewritten automatically.

## Saved document and provenance model

`documents` contains the current saved normalized text and document metadata. `document_versions` contains immutable saved text versions plus fetch time and parser version. This stage does not expose editing or complex version management.

`document_provenance` is a historical snapshot. Each origin includes:

- Source id;
- source name snapshot;
- source URL snapshot;
- source type snapshot;
- source-published/discovered URL;
- final resolved URL after redirects when known;
- discovery time;
- fetch time.

Library detail reads these snapshots directly. Renaming or disabling a Source later does not rewrite historical provenance.

## Library and Inbox read models

The UI has separate read-only repository contracts from the transactional write path.

Library list query returns only:

- document id;
- title;
- saved date;
- short text snippet;
- source count;
- one source label for compact presentation.

Full `normalizedText` and all provenance are fetched only for document detail.

Ordering is deterministic:

- Library: `savedAt DESC, documentId DESC`;
- Inbox: `createdAt DESC, inboxItemId DESC`.

Paging is keyset-based, so equal timestamps do not create offset-related skips or duplicates. Total counts use independent `COUNT(*)` Flow queries and therefore do not depend on how many rows are currently loaded by the screen.

Room invalidations update Flow consumers. `collectAsStateWithLifecycle` prevents the UI from maintaining a permanent polling loop while the application is backgrounded.

## Offline-reading boundary

A saved document is readable without network access because normalized text is stored locally. Opening the original URL or redirect URL is an explicit Android intent triggered only by the user.

The archive guarantee is intentionally limited: Article Navigator does **not** currently claim to preserve the complete original HTML page, images, attachments, scripts or other remote assets. Temporary raw HTTP data exists for processing recovery and is removed after terminal processing; it is not the long-term archive format.

## Schema and recovery

Current schema version: **6**.

Important compatibility/recovery guarantees remain covered by tests:

- historical v1→v6 migration chain;
- both known historical physical v4 variants migrate without destructive fallback;
- v5→v6 preserves pending Inbox text and labels its parser version conservatively;
- exact temporary raw bytes, Content-Type and resolved URL survive a file-backed database close/reopen;
- expired article-processing owners cannot commit stale results;
- failed terminal transactions roll back and remain recoverable;
- saved text and provenance survive database reopen.

## Android background work

WorkManager is a persistent wake-up mechanism, not an exact timer. Collection scheduling state and article-processing ownership live in SQLite.

The current worker uses a shared pass deadline for collection plus ingestion. A known scheduler limitation is tracked separately: successful queue continuation and infrastructure retry currently share WorkManager `runAttemptCount`/retry backoff. That refinement is deliberately outside the Library/offline-reading PR so UI/storage changes do not destabilize scheduling semantics.

## Planned derived capabilities

The next independent product stages are:

1. exact offline full-text search (FTS) with deterministic rebuild/ranking tests;
2. export and restore with round-trip compatibility tests;
3. manual URL capture through Android Share;
4. semantic/hybrid retrieval only after exact search and archive portability are proven.

Any future FTS/vector/index data must remain rebuildable from canonical local text and metadata. Re-indexing may change derived indexes but must not rewrite saved provenance or historical parser/version records.
