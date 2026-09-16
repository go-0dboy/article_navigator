# Architecture

## Product objective

Article Navigator follows **supported** Internet sources, lets the user review and read discovered material, preserves selected content locally, and keeps durable historical provenance so saved knowledge remains understandable even when the original site changes or disappears.

The architecture must support more than RSS over time, but it does not assume that every website can be crawled. Each supported source class needs an explicit acquisition contract, preview/error behavior and deterministic tests. Manual saving of one URL is a different capability from automatic subscription.

The normative product scenarios and source support matrix are defined in `docs/PRODUCT-SPEC.md`.

## Architectural principles

1. **Local-first.** Room/SQLite is canonical for saved material and user-visible historical state.
2. **Provenance is mandatory.** Source snapshots and original/final URLs survive later source edits/removal.
3. **Inbox and Library are distinct lifecycles.** Pending material is not saved knowledge until transactional Save.
4. **Transactional user actions.** Save/Reject/Read-and-discard consume the current Inbox row and current origins in one Room transaction.
5. **Processing ownership is persisted.** An old/expired worker may finish a network request but cannot commit stale state.
6. **Acquisition adapters share one pipeline.** New manual/source adapters must use existing scheduler/ownership/retry/limits/finalisation rather than a simplified write path.
7. **Content interpretation is versioned.** The extractor declares the durable parser version and the structured format that produced persisted content.
8. **Plain text and reading structure are separate responsibilities.** Plain text remains the exact-search/compatibility representation; formatted reading uses a versioned safe representation.
9. **Historical schema is immutable.** Released Room exports are append-only; compatibility changes use explicit migrations.
10. **UI observation is reactive and bounded.** Flow/ViewModel/lifecycle state plus keyset paging replace polling/unbounded reads.
11. **Derived indexes are rebuildable.** FTS/vector/search indexes are secondary to canonical documents/versions/provenance/resources.
12. **AI is optional future infrastructure.** Collection, reading, exact search and restore must work without a model/cloud provider.

## Logical flow

```text
Source configuration / manual capture
          |
          v
Source adapter or one-shot acquisition contract
          |
          v
Discovery record + durable provenance URL
          |
          v
Article processing lease
          |
          +--> HTTP fetch --> temporary exact raw bytes + Content-Type + final URL
          |                         |
          |                         `--> crash/reopen reuse while valid
          v
version-aware extractor
          |
          +--> normalizedText (search/snippet/legacy compatibility)
          |
          `--> structuredContent (safe-html-v1 when available)
          |
          v
representation-aware content hash
          |
          v
atomic finalisation
   |- known unchanged -> fingerprint/provenance handling
   `- changed/new     -> Inbox + current origins
                              |
                      Save / Reject / Read-discard
                              |
                              v
               documents + document_versions
                    + document_provenance
                              |
                              v
                     local formatted reader
```

A publication disappearing from a source never deletes a saved document.

## Modules and boundaries

- `app`: Android composition root, top-level navigation, external-link intent, theme/settings integration.
- `core:model`: source/discovery/content/document/read models including persisted representation metadata.
- `core:data`: repository contracts; ownership-aware writes and read-only feature projections.
- `core:network`: HTTP transport primitives and response limits.
- `collector:api`: source adapter/discovery contracts.
- `collector:rss`: implemented RSS/Atom source adapter.
- future collector modules: concrete static-HTML/API/platform adapters only when their acquisition contracts exist.
- `pipeline`: article decoding/extraction, representation hashing and Inbox lifecycle service.
- `scheduler:core`: persisted collection orchestration and shared execution budget.
- `scheduler:android`: WorkManager wake-up/integration.
- `storage:database`: canonical Room schema, migrations, transactional DAOs and observable read DAOs.
- `feature:inbox`: pending list/read/decision UI; writes delegate to `InboxService`.
- `feature:library`: local saved list/detail reader.
- `feature:sources`: source add/preview/edit/pause/resume/error UI.
- future `feature:search`: exact offline search over rebuildable indexes.
- future `feature:settings`: user diagnostics, reading settings, archive/export controls.

Feature modules do not own canonical write shortcuts. Library remains read-only; Inbox cannot bypass `saveCurrent`/`discardCurrent`; future manual URL/Share adapters must enqueue into the same processing path.

## Source support contract

Source type declarations in models are not support claims. A source class becomes supported only when all are present:

- an acquisition/discovery implementation;
- explicit URL/auth/pagination/rate/error semantics;
- preview/validation UX where configuration can be ambiguous;
- deterministic fixtures;
- scheduler/ownership/retry/limit integration;
- documented user-facing limitation/error behavior.

Current implementation: RSS/Atom polling and article fetch.

Planned independent additions: one-shot HTML URL capture, RSS/Atom discovery from a site page, static HTML section discovery, then named structured-API/platform adapters as concrete contracts warrant them. Generic JavaScript-rendered/authenticated-site crawling is not claimed.

## Content representation

ADR 0007 chooses two coordinated forms.

### Canonical plain text

`normalizedText` remains durable for:

- exact search/FTS;
- list snippets;
- text-only legacy compatibility;
- inspection/export independent from rendering technology.

### Safe formatted representation

New formatted extraction may additionally persist:

- `structuredContentFormat = safe-html-v1`;
- sanitized `structuredContent` containing only passive reading semantics.

`safe-html-v1` supports headings/paragraphs, ordered/unordered lists, block quotes, bold/emphasis, HTTP/HTTPS links, inline/preformatted code and tables. Active source content is stripped. Relative links are resolved against the final fetched page URL before persistence.

The reader must not execute source scripts/forms/iframes or silently load remote resources. Link opening is explicit user action. Images are represented conservatively as inert metadata/placeholders until a bounded local-resource stage stores supported bytes in app-private storage.

Legacy rows where structured fields are null are valid and render through text mode. Migration never re-downloads or guesses missing structure.

## Parser and representation identity

`ContentExtractor` is a version-aware contract. Every extractor must expose a durable parser identifier; an alternative extractor cannot be wired without one. The pipeline stores that exact identifier with the extracted Inbox row, and Save copies it unchanged to immutable `DocumentVersion`.

For newly extracted `safe-html-v1` content, `contentHash` is representation-aware (domain-separated format id + deterministic sanitized HTML). Consequently a changed link target/table/code/formatting structure can be a new version even if normalized plain text is unchanged. Existing historical hashes are never rewritten only to adopt the policy.

## Document/version model

`documents` stores the current saved representation and metadata. `document_versions` stores immutable historical saved representations, fetch time and parser identity. `document_provenance` stores immutable origin snapshots.

A new version is created only through the existing transactional Save/finalisation policy. Provenance merging does not destroy historical versions.

## Offline image/resource policy

Remote resources are not fetched by the saved reader. A later resource stage will introduce canonical local-resource records/files with:

- stable id and owning document/version;
- original URL, MIME, byte length and content hash;
- app-private file storage;
- per-resource/per-document byte and count limits;
- safe supported image formats;
- caption/alt metadata;
- explicit placeholder behavior if bytes are absent/corrupt;
- archive export/restore.

Until then, image metadata can survive in safe content but the reader displays an offline placeholder and never falls back to remote loading.

## Library and Inbox read models

Lists use stable keyset ordering and lightweight projections. Full body/structured content is loaded only for an opened material. Independent counts do not depend on loaded page size. Room invalidation drives refresh; failures are visible and retryable rather than translated to an empty list.

Top-level product navigation target is Inbox / Library / Sources / Search / Settings. State that matters to the user (selected document, reading position/settings, unsaved source form) must be explicitly owned/restorable rather than depending on transient composable state.

## Search and archive

Exact offline search is implemented before semantic retrieval. FTS/index data is derived from canonical plain text/metadata and must be fully rebuildable after restore.

Export/restore is a canonical archive path. Its versioned format must include documents, immutable versions, normalized text, structured content, provenance snapshots and local resource records/bytes once resources exist. Restoring must not require a proprietary service.

## Android background work

WorkManager is a persistent wake-up mechanism, not an exact timer. Source schedule state and processing ownership live in SQLite. Issue #18 separately tracks decoupling successful queue continuation from infrastructure retry/backoff history. Automatic-observation acceptance must account for that limitation until #18 is closed.

## Security boundaries

- Only HTTP/HTTPS source/article links are accepted by generic public acquisition paths.
- Persisted formatted content is sanitizer output, never original executable DOM.
- Source JavaScript, forms, iframes/object/embed and event handlers are not executed.
- Saved-reader network access is disabled for embedded resources; external navigation requires explicit user action.
- Authentication-required adapters need a dedicated credential/session design before support can be claimed.

## Quality boundary

Every functional PR must preserve ownership, transactional Inbox lifecycle and provenance guarantees; include deterministic fixtures/regressions; add schema migration/export when needed; and finish with exact-HEAD green tests, Room schema check, lint and assemble/APK gates. Physical-device/emulator or visual checks are reported explicitly when unavailable rather than inferred from unit/Robolectric coverage.
