# Article Navigator

Article Navigator is a local-first Android application for automatically collecting information from user-selected sources and building a durable, searchable personal knowledge base.

The product is designed around a simple problem: **you find something valuable today and months later you should still be able to find it again, know where it came from, and search it using normal language.**

## Core product flow

```text
configured sources
      |
      v
automatic collection
      |
      v
filter / deduplicate / normalize
      |
      v
Inbox
  |        |          |
reject   read      save
                     |
                     v
              Knowledge library
                     |
            exact + semantic search
```

## Engineering direction

- Kotlin + Jetpack Compose Android application;
- local-first canonical storage;
- platform-independent collector contracts;
- durable source provenance;
- Room 3 / SQLite storage foundation;
- FTS5 + replaceable vector search planned as derived indexes;
- optional on-device/cloud AI behind interfaces, never required for preserving knowledge;
- GitHub Actions builds, tests, lints and produces a debug APK.

See [Architecture](docs/ARCHITECTURE.md), [Development route](docs/ROADMAP.md) and [ADRs](docs/adr/).

## Repository status

Foundation development has started on the first architecture/bootstrap pull request. Current code establishes the multi-module build, domain model, collector API, ingestion-stage API and canonical database schema.
