# Development route

This roadmap is architecture-preserving: each stage adds one product capability without weakening canonical storage, processing ownership, transactional Inbox actions or historical provenance. Product requirements and source-support boundaries are defined in `docs/PRODUCT-SPEC.md`.

## Quality gate for every functional stage

A stage is not complete until all applicable checks are true:

- new production behavior has deterministic automated tests/fixtures;
- every reproducible defect fixed in the stage has a regression test;
- schema changes have a new migration and committed Room schema export; historical exports are not edited;
- supported migration paths preserve canonical content, versions and provenance;
- new collectors/adapters use existing scheduler, ownership, retry, limits and transactional finalisation;
- Android UI/background behavior has unit/Robolectric/WorkManager tests where appropriate;
- navigation, system Back, Activity restoration, failure/retry and cold-offline behavior are tested when the stage changes those flows;
- narrow-screen/dark-theme/large-font visual checks are reported when an emulator/device is available; unperformed visual/device checks are stated explicitly;
- `./gradlew test --stacktrace`, Room schema consistency, `./gradlew lint --stacktrace` and `./gradlew assembleDebug --stacktrace` pass on the exact final SHA;
- the debug APK identity/artifact gate remains enabled;
- exit criteria are demonstrated by tests or a reproducible user scenario.

No automatic merge is part of a stage. Merge is a separate explicit decision.

## Completed foundation

### Repository, storage and collector foundation — COMPLETE

- reproducible multi-module Android/Kotlin build;
- Room canonical database and committed schema history;
- source/cursor/discovery/document/provenance persistence;
- RSS/Atom collection and deterministic feed fixtures;
- OkHttp transport and conditional validators;
- WorkManager wake-up plus persisted scheduling state;
- discovery/fetch/normalization pipeline;
- Inbox Save / Reject / Read-and-discard;
- canonical URL/content deduplication and compact seen fingerprints.

### Reliability hardening — COMPLETE

- persisted source-collection ownership;
- persisted article-processing ownership;
- stale owner writes/finalisation rejected;
- transactional discovery/source commits;
- transactional Inbox actions using current origins;
- durable provenance snapshots with restrictive source relationships;
- exact temporary raw bytes + Content-Type + final URL for crash recovery;
- file-backed close/reopen recovery tests;
- bounded concurrency and network-policy filtering;
- shared collection+ingestion execution budget;
- infrastructure errors separated from ordinary article failures;
- historical v4 compatibility and complete migration-chain coverage.

Issue #18 remains an independent scheduler follow-up: successful continuation and infrastructure retry currently share WorkManager retry/backoff history.

### Saved Library and text offline reading — VALIDATED IN PR #17

Baseline source point for subsequent stacked work: `feat/library-offline-reading@83df388` with Android CI #343 green.

Implemented there:

- strict BOM / HTTP charset / document metadata / UTF-8 decoding;
- parser identity persisted with pending text;
- Room v6 migration;
- saved Library with stable keyset paging and exact count;
- local text reading and provenance detail;
- reactive Inbox/Library ViewModels;
- Save -> Library navigation;
- restart/reopen tests for raw and saved data.

## Stage A — Structured content and formatted reader — CURRENT

First functional stage after requirements fixation.

Scope:

- ADR 0007: `normalizedText` + versioned `safe-html-v1`;
- make `ContentExtractor` explicitly parser-version aware (issue #19);
- deterministic sanitizer for headings, paragraphs, lists, quotes, emphasis, links, inline/preformatted code and tables;
- relative link resolution against final fetched URL;
- inert offline image placeholders/metadata, with no remote image loading;
- representation-aware content hash so structural/link changes can produce a new version even when plain text is unchanged;
- persist structured format/content through Inbox -> Document -> DocumentVersion;
- Room v7 migration with null structured fields for all legacy v1-v6 rows;
- legacy text-only reader fallback without re-download or fabricated structure;
- formatted offline reader that disables source script/active content and embedded network loads;
- regression tests for sanitizer, parser version, migration, same-plain-text/different-structure version identity and file-backed reopen.

**Exit:** existing RSS scenario produces a formatted saved document, survives reopen/offline reading, preserves provenance/versions, and exact final SHA passes all CI gates.

## Stage B — One-shot URL capture

Deliver two entry points in one acquisition contract:

- paste HTTP/HTTPS URL inside the application;
- Android Share target for HTTP/HTTPS URL.

Both create/enqueue normal discovery work and use the existing article-processing ownership/fetch/extract/finalisation path. Manual capture is explicitly not a subscription.

Acceptance:

- invalid/non-http input has a clear user error;
- duplicate URL/content follows normal dedup/version rules;
- offline/transient failure remains retryable;
- provenance identifies manual capture without inventing a subscribed Source;
- Share/Paste -> Inbox -> read -> Save -> Library is tested.

## Stage C — Discover RSS/Atom from a site page

- user enters a normal site/page URL;
- fetch page safely and inspect explicit feed declarations plus conservative same-site candidates;
- show discovered feeds with titles/URLs and preview recent entries;
- user chooses which feed to subscribe to;
- no feed found -> clear explanation plus optional one-shot article capture when appropriate.

Acceptance: deterministic HTML fixtures, relative feed URL resolution, multiple-feed choice, duplicate feed handling, no false claim that the whole site is automatically supported.

## Stage D — Static HTML section subscriptions

Implement a separate constrained adapter for index/list pages that expose stable publication links without JavaScript/authentication.

- default heuristics use article-like links and same-site boundaries;
- configuration preview shows which links would be discovered before enabling;
- advanced CSS selectors are optional power-user settings, not required knowledge;
- bound links per pass and response sizes;
- use existing scheduler/ownership/retry/finalisation;
- rediscovery vs changed content remains distinct;
- disappearance from index never deletes saved content.

Acceptance: deterministic section fixtures, preview, stable ids, bounded extraction, policy/error UI and queue integration.

## Stage E — Exact offline search

Implement exact search before semantic retrieval:

- Search top-level destination;
- SQLite/Room FTS or equivalent derived indexes for saved title/plain body and selected metadata;
- exact terms, phrases and identifiers;
- deterministic ordering and useful filters;
- complete rebuild from canonical documents/versions after restore;
- no network or AI dependency.

Acceptance: exact-term/phrase/identifier fixtures, index update after Save/version change, equal-score ordering, filters and rebuild equivalence.

## Stage F — Export and restore

Create a portable versioned archive including:

- documents and immutable document versions;
- normalized plain text;
- structured format/content;
- provenance snapshots;
- local resource metadata/bytes once resource storage exists;
- archive/schema version and validation metadata.

Restore into a clean install with explicit duplicate/idempotency policy. Derived search indexes are rebuilt from restored canonical data.

Acceptance: export -> clean database -> restore -> export round trip with equivalent canonical content, multiple origins, versions, Cyrillic/Unicode, legacy text-only records and local resources.

## Stage G — Local image/resources

Implement the image policy defined by ADR 0007/product spec:

- app-private storage only;
- HTTP/HTTPS resource acquisition through bounded transport;
- supported safe image formats;
- MIME/hash/byte-length/original-URL metadata;
- per-resource/per-document byte limits and image-count cap;
- document/version ownership;
- no silent remote fallback on missing/corrupt files;
- export/restore integration.

Until this stage, formatted reading uses inert image placeholders/captions.

## Stage H — Product UI completion

Converge implemented capabilities into finished Android navigation:

- top-level Inbox / Library / Sources / Search / Settings;
- Inbox detail reading before Save/Reject;
- consistent typography/components and light/dark themes;
- adjustable reading size, selection/copy and provenance panel;
- accessible labels/system font scaling;
- correct system Back behavior;
- selected screen/document and reading-position restoration;
- explicit loading/offline/error/empty/unsupported states;
- source add/preview/edit/pause/resume, collection parameters, last-check state/error, form preservation;
- release UI excludes debug fixtures/commands; understandable diagnostics live in Settings.

Acceptance includes Activity/navigation/back/retry/cold-offline tests and visual checks on narrow screen, dark theme and enlarged font when runtime infrastructure is available.

## Concrete API and platform adapters — AFTER CORE FLOWS

No generic “API support” or “platform support” milestone exists. Each integration is its own small stage and names the actual provider/contract.

Required before support is claimed:

- verified accessible API/feed/export contract;
- authentication/session/rate/pagination semantics where applicable;
- fixtures/mocks from the documented contract;
- source preview and user-facing limitation text;
- same scheduler/ownership/retry/pipeline/finalisation guarantees.

Generic dynamic JavaScript sites and generic authenticated sites remain unsupported until dedicated secure acquisition designs exist.

## Semantic/hybrid retrieval — LATER

Only after exact search and archive portability are proven:

- replaceable embedding provider;
- rebuildable chunk/vector indexes;
- hybrid fusion with exact search;
- retrieval evaluation corpus.

LLM summaries, recommendations or answers never replace canonical source-derived content/provenance.

## Non-negotiable engineering requirements

- no feature silently discards provenance;
- stale processing owners cannot commit;
- UI cannot bypass transactional Inbox lifecycle methods;
- collector/discovery remains idempotent;
- source removal/disable never destroys saved copies or historical origin snapshots;
- structured/source active content is never executed by the offline reader;
- offline document open never silently loads remote resources;
- historical schema exports are immutable and migrations are tested;
- derived search indexes can be deleted/rebuilt;
- legacy text-only content remains readable without automatic re-download;
- user archive remains recoverable without a proprietary server;
- unsupported source classes are explained rather than falsely advertised;
- CI quality gates remain enabled for every merge decision.
