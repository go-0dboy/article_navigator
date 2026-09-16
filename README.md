# Article Navigator

Article Navigator is a local-first Android application for collecting material from user-selected sources, reviewing it in an Inbox and preserving selected items in a durable on-device library.

The current product goal is deliberately narrow: **save useful material, read the saved text later without the network, and retain where and when it came from.** Search and optional intelligence are later stages and are not required for preservation.

## Current product flow

```text
configured RSS/Atom sources
          |
          v
background discovery + fetch
          |
          v
strict decode / extract / normalize / deduplicate
          |
          v
Inbox
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

## What is implemented

- Kotlin + Jetpack Compose Android application;
- Room 3 / SQLite canonical local storage;
- RSS/Atom collection through WorkManager and persisted scheduling state;
- persisted processing ownership so an expired or replaced worker cannot commit stale processing results;
- crash-resumable raw HTTP responses while temporary raw data remains valid;
- strict content decoding with BOM, HTTP charset and document metadata handling;
- transactional Inbox Save / Reject / Read-and-discard operations;
- durable provenance snapshots independent of later Source renames or disabling;
- saved-material library with stable keyset pagination and an independent exact count;
- offline document reading from normalized text stored in the local database;
- all saved provenance origins visible in document detail;
- Flow + ViewModel + lifecycle-aware UI observation for Inbox and Library instead of periodic polling;
- GitHub Actions tests, Room schema verification, Android lint and debug APK assembly.

## Saved-content boundary

Article Navigator currently preserves **extracted normalized text and provenance**, not a guaranteed complete archive of the original web page. Images, scripts, attachments and every original page asset are not promised to be available offline.

External URLs are opened only when the user explicitly chooses to open them. Availability of the original site is not required to read text that was already saved.

## Persistence compatibility

The current database schema is version 6. Released schema files are append-only in Git; migrations are tested instead of editing historical schema exports.

Schema v6 persists the parser version with pending Inbox text. This prevents an item extracted by an older parser from being relabelled as a newer parser merely because the user pressed Save after upgrading the application.

## Next product stages

The library stage is followed by separate changes, not one combined refactor:

1. exact offline full-text search with deterministic FTS/index rebuild tests;
2. export and restore with round-trip compatibility tests;
3. manual URL capture through Android **Share**;
4. semantic/hybrid retrieval only after exact search and archive portability are proven.

Scheduler continuation/backoff refinement is tracked separately from the library UI so it cannot destabilize this product stage.

See [Architecture](docs/ARCHITECTURE.md), [Development route](docs/ROADMAP.md), [Build instructions](docs/BUILDING.md) and [ADRs](docs/adr/).
