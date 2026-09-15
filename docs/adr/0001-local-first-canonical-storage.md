# ADR 0001: Local canonical storage

**Status:** Accepted

## Decision

The Android device owns the canonical knowledge database. Network and future cloud services update or synchronize it but do not become the only source of truth.

Search indexes, embeddings, summaries and generated tags are derived artifacts and must be rebuildable.

## Consequences

- the knowledge library remains usable offline;
- vendor/model changes do not require moving canonical content;
- backup/export is under application control;
- sync must resolve against local revisions rather than assuming server ownership.
