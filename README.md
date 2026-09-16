# Article Navigator

Article Navigator is a local-first Android application for following **supported** Internet sources, reviewing new publications, reading them in a safe formatted form, saving useful material into a durable on-device library, and later finding it together with historical provenance.

The product is not a generic web crawler and does not promise support for every site. A source class is called supported only when there is a concrete acquisition contract, UI behavior and automated acceptance coverage.

## Product scenarios

Article Navigator is being built around four distinct scenarios:

1. **Save one link** — paste/share one HTTP/HTTPS article and process it through the same reliable pipeline as scheduled content. This is manual capture, not a subscription.
2. **Subscribe to a source** — configure a supported source and automatically discover new publications over time.
3. **Detect an updated material** — distinguish rediscovery from an actual content/structure change and preserve immutable saved versions.
4. **Read, save, search and restore** — review in Inbox, preserve locally, search offline and export/restore the canonical archive with provenance.

The detailed contract and source-support matrix live in [Product specification](docs/PRODUCT-SPEC.md).

## Current supported source boundary

| Source class | Product state |
| --- | --- |
| RSS / Atom | Implemented and tested |
| Single HTML article via paste/share | Next independent stage |
| Static HTML section/index subscription | Next independent stage after feed discovery |
| Structured API | Adapter-specific future work; no generic integration claim |
| Named platform adapter | Future work only after a verified platform contract exists |
| Dynamic JavaScript-rendered site | Not generically supported |
| Authentication-required site | Not generically supported |

An adapter enum/interface is not treated as an implemented integration. Unsupported sources must produce a clear explanation instead of silently degrading into a misleading “subscription”.

## Current reliable flow

```text
supported source (currently RSS/Atom)
          |
          v
background discovery + fetch
          |
          v
persisted processing ownership
          |
          v
strict decode / safe extract / normalize
          |
          v
Inbox + provenance snapshot
  |            |                 |
reject    read-and-discard      save
                                 |
                                 v
                         Saved library
                                 |
                    local offline document
                                 |
                 durable provenance snapshots
```

## Structured reading stage

The next functional layer preserves two coordinated representations:

- `normalizedText` — plain text used for snippets, exact offline search and compatibility;
- `safe-html-v1` — a versioned passive representation for headings, paragraphs, lists, quotes, emphasis, links, code and tables.

Source scripts/forms/iframes and other active content are not persisted or executed. Relative links are resolved during extraction. Saved reading must not silently load remote resources.

Images are deliberately conservative: the structured representation may retain inert image metadata/caption, but the first structured-content stage does not claim remote images are archived. Bounded app-private image storage is a separate local-resource stage; missing resources must show a placeholder and must never trigger silent network fallback.

Legacy documents that contain only `normalizedText` remain readable in text mode. They are not re-downloaded or rewritten to invent structure.

See [ADR 0007](docs/adr/0007-safe-limited-html.md).

## Reliability baseline retained

- Kotlin + Jetpack Compose Android application;
- Room 3 / SQLite canonical local storage;
- WorkManager wake-up with persisted scheduling state;
- RSS/Atom collection with deterministic fixtures;
- persisted source/article ownership and stale-owner rejection;
- crash-resumable temporary raw response bytes with Content-Type/final URL;
- transactional Inbox Save / Reject / Read-and-discard;
- durable Source/provenance snapshots;
- stable keyset paging and reactive Inbox/Library observation;
- local offline library reading;
- migration-chain, schema, unit/integration, lint and APK CI gates.

Issue #18 tracks a separate scheduler refinement: successful queue continuation and infrastructure retry currently share WorkManager retry/backoff history.

## Delivery order

Work is intentionally split into small PRs:

1. structured content persistence + migration + formatted offline reader for the existing RSS path;
2. in-app URL capture + Android Share through the same ingestion queue;
3. RSS/Atom discovery from an ordinary site page with feed choice/preview;
4. constrained static HTML section subscriptions with preview;
5. exact offline full-text search with rebuildable indexes;
6. export/restore of text, structure, versions, provenance and local resources;
7. product UI completion gates: navigation/back/activity restoration/offline/error/accessibility/theme/large-font checks;
8. concrete API/platform adapters only when a real integration contract is implemented and tested.

Embeddings, LLM features and recommendations remain out of scope until collection, formatted reading, exact search and archive safety are complete.

See [Architecture](docs/ARCHITECTURE.md), [Development route](docs/ROADMAP.md), [Build instructions](docs/BUILDING.md), [Product specification](docs/PRODUCT-SPEC.md) and [ADRs](docs/adr/).
