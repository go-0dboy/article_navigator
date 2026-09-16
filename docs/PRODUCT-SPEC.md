# Product specification

## Product promise

Article Navigator is a local-first Android application for following supported Internet sources, reviewing new material, reading it in a safe formatted form, preserving useful material locally, and later finding it together with durable provenance.

The product does not promise that every web site can be subscribed to. A source is supported only when Article Navigator has a concrete acquisition contract and acceptance tests for that source class. Unsupported sources must fail with a user-facing explanation and must not be presented as an automatic subscription merely because a single URL can be saved manually.

## Core user scenarios

### Save one link

The user pastes or shares one HTTP/HTTPS article URL. Article Navigator validates and canonicalizes the URL, fetches it through the normal ownership-aware pipeline, extracts content, and places the result in Inbox for review. Manual capture is a one-item operation; it is not a subscription and does not imply future discovery from the same site.

Acceptance: invalid/non-HTTP input is rejected clearly; transient failures are retryable; duplicates use the same canonical/content policy as scheduled ingestion; provenance records that the material was manually captured; no second simplified storage path is introduced.

### Subscribe to a source and discover new material

The user adds a supported source, previews what Article Navigator can discover, confirms the subscription and collection policy, and can pause/resume it later. Scheduler, persisted source state, ownership, retry policy, limits and transactional finalisation are shared by every adapter.

Acceptance: a new publication is discovered once, rediscovery advances observation metadata without duplicating Inbox, and disabling/removing a subscription never deletes saved documents or historical provenance.

### Detect a new version of an existing material

A previously known canonical material may be fetched again when a source reports it again or when the configured source contract indicates updates. Article Navigator distinguishes URL rediscovery from content change. For structured content, version identity includes the persisted safe structure as well as readable text, so a changed link/table/code structure is not hidden merely because plain text is unchanged.

Acceptance: unchanged canonical structured content does not create a new saved version; changed canonical structured content can create a new version; the old saved version remains immutable; disappearance from a source never deletes a saved copy.

### Read, save, search and restore the library

Inbox is the decision point. The user can read a pending material before Save/Reject. Save atomically moves the current pending content and all current origins into canonical saved storage. Library reading is local and must not silently fetch external assets. Exact offline search is derived from canonical plain text/metadata. Export/restore preserves text, structure, versions, provenance and local resources; search indexes are rebuilt rather than treated as canonical data.

Acceptance: cold offline open reads the saved representation from local storage; provenance remains visible; exact search works without network; export -> clean install -> restore preserves canonical records and local resource references/bytes according to the archive format.

## Source support matrix

| Source class | Status | Acquisition contract | Acceptance boundary |
| --- | --- | --- | --- |
| RSS / Atom | Implemented | Poll declared feed URL; parse RSS/Atom entries; fetch article URLs with the registered RSS adapter | Deterministic feed fixtures; cursor/validator behavior; ownership/retry/network limits; new vs rediscovered entries; article -> Inbox -> Save |
| Single HTML article | Next stage | Explicit pasted/shared HTTP/HTTPS URL; fetch once through normal ingestion pipeline | Paste + Android Share; validation; retry; dedup; formatted extraction; manual provenance; no implied subscription |
| Static HTML section/index | Next stage | Poll a configured section page; constrained link discovery with preview; optional advanced CSS selectors | Preview before enable; deterministic HTML fixtures; stable discovery keys; bounded links; same scheduler/ownership/pipeline; clear failure when no reliable rule is found |
| Structured API | Planned, adapter-specific | Concrete documented HTTP API contract per provider; pagination/cursors/auth rules implemented per adapter | Mocked contract fixtures; pagination/rate/error semantics; stable IDs; no generic “API support” claim without an implemented adapter |
| Special platform adapter | Planned, platform-specific | Official/publicly usable feed/API/export endpoint or another verified contract implemented for that platform | Named platform, documented integration method, adapter fixtures and error limits before UI labels it supported |
| Dynamic JavaScript-rendered source | Limited / unsupported generically | No generic browser automation in the collector | UI explains that the page requires unsupported dynamic rendering; manual article capture may still work only if the article URL returns usable static content |
| Authentication-required source | Limited / unsupported generically | No credential/session automation until a dedicated secure adapter is designed | No password scraping or hidden browser session reuse; UI explains the limitation; future adapter must define credential storage, expiry, logout and tests |

An enum value, adapter interface or configuration field is architecture only and does not make a source class implemented.

## Content representation

Canonical readable content has two coordinated representations:

- `normalizedText`: stable plain text for snippets, exact search and compatibility;
- versioned structured representation: currently `safe-html-v1` for formatted reading.

The safe representation is produced by the extractor that parsed the response and is stored through Inbox, saved Document and immutable DocumentVersion. The extractor declares its durable parser/version identity explicitly.

Minimum `safe-html-v1` semantics: headings, paragraphs, ordered/unordered lists, block quotes, bold/emphasis, links, inline code, preformatted code blocks and tables. Scripts, forms, iframe/object/embed, event handlers, style attributes and other active source content are not persisted.

Relative links are resolved against the final fetched URL before persistence. Only HTTP/HTTPS external links survive. Opening a link is always an explicit user action.

### Images

Image handling is deliberately offline-safe. The structured representation may preserve image metadata/alt text and an absolute source URL as inert metadata, but the reader must never load that URL automatically. Remote image download is a separate bounded local-resource stage.

Local-resource policy for that stage:

- store only successfully downloaded HTTP/HTTPS image bytes in app-private storage;
- bind every resource to a stable resource id and owning document/version;
- enforce per-resource and per-document byte limits plus a bounded image count;
- record MIME type, byte length, content hash, original URL and optional caption/alt text;
- decode only supported raster formats and treat SVG/active formats conservatively;
- if the local file is absent/corrupt, render a visible placeholder/caption and never fall back to the network silently;
- export/restore includes local resource metadata and bytes.

Until the local-resource stage is implemented, images render as offline placeholders/captions. This is an explicit limitation, not a claim of image archiving.

## Deduplication and version policy

URL canonicalisation identifies likely material identity; provenance keeps the source-published and final resolved URLs unchanged. Content identity is representation-aware for newly extracted structured content:

- text-only legacy/current content: hash the canonical plain text representation;
- `safe-html-v1`: hash the format identifier plus canonical sanitized HTML.

This deliberately allows a structural/link change with identical plain text to be observed as a content change. Existing historical hashes are never rewritten solely to adopt the new policy.

## Navigation and user experience target

Top-level destinations: **Inbox**, **Library**, **Sources**, **Search**, **Settings**.

Product UI requirements:

- shared typography/components and light/dark themes;
- system font scaling and accessible labels;
- predictable system Back behavior;
- restoration of selected screen, selected material and reading position where feasible;
- explicit loading, offline, retry, empty and unsupported-source states;
- Russian user-facing terminology without database ids/adapter names in normal flows;
- debug fixtures/commands separated from release user journeys;
- user-facing diagnostics live under Settings.

Inbox must support opening/reading before Save/Reject. Reader must support formatted content, text selection/copy, adjustable reading size and provenance. Sources must support add/preview, edit, pause/resume, available collection settings, last-check state/error and form preservation after failure.

## Search and archive

Exact offline search is required before embeddings or LLM retrieval. FTS/index data is derived and completely rebuildable from saved canonical plain text and metadata.

Export/restore is a canonical archive operation. The archive format is versioned and includes current documents, immutable versions, normalized text, structured representation, provenance snapshots and local resources. Restore is validated, idempotent by documented identity rules and never depends on a proprietary server.

## Delivery stages

1. **Structured content + reader** — version-aware extractor, `safe-html-v1`, schema migration, Inbox/DocumentVersion persistence, formatted offline Library reader, deterministic extraction/migration/reader tests.
2. **Manual URL capture** — in-app paste and Android Share through the same ingestion queue.
3. **Feed discovery** — detect RSS/Atom declarations on a site page and let the user choose/preview a feed.
4. **Static HTML subscriptions** — constrained section link discovery with preview and optional advanced selectors.
5. **Exact offline search** — rebuildable FTS and Search destination.
6. **Export/restore** — canonical round-trip archive including structure/provenance/resources.
7. **Product UI completion** — navigation/back/activity restore/offline/error/accessibility/theme/large-font visual gates across completed flows.
8. API/platform adapters only as concrete named integrations with verified contracts.

Issue #18 remains a separate scheduler change and must be considered before claiming robust long-running automatic observation. Embeddings, LLM summaries/recommendations and semantic retrieval remain out of scope until collection, reading, exact search and archive safety are complete.
