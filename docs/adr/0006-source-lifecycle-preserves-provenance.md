# ADR 0006: Source lifecycle must preserve provenance

## Status
Accepted

## Context
A saved knowledge document must keep answering the question "where did I find this?" years after collection. `DocumentProvenance` references the source that discovered the material, while users will eventually need to stop or remove sources from active collection.

Physically deleting an ordinary user source can destroy or invalidate provenance and seen-fingerprint history. That would violate the product's core long-term-memory goal.

## Decision
Normal user-facing source removal is an archival operation, not a physical delete:

- stop scheduling the source (`enabled = false`);
- retain its stable `SourceId` and metadata while persisted knowledge or history refers to it;
- keep `DocumentProvenance` and source URLs durable;
- a future cleanup job may physically delete an archived source only after proving that no retained canonical data references it.

Known application-owned diagnostic fixtures are different. A migration may physically remove a fixture with a reserved ID when the application can prove that it contains no user knowledge. Phase 3's `phase3-sample-rss` is such a fixture.

## Consequences
Source-management UI must use disable/archive semantics. Repository APIs added later must not expose an unconditional hard-delete operation for normal user sources. Provenance remains meaningful independently of whether a source is still actively collected.
