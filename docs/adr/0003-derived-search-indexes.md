# ADR 0003: Search indexes are derived

**Status:** Accepted

## Decision

FTS/vector/embedding data is treated as an index over canonical knowledge. Every derived record carries the index/chunker/model version needed to decide when it must be rebuilt.

## Consequences

Changing the vector engine or embedding model is a re-index operation rather than a canonical database migration.
