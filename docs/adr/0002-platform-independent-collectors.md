# ADR 0002: Platform-independent collectors

**Status:** Accepted

## Decision

Collector contracts and parsing logic are pure Kotlin/JVM code and must not depend on Android `Context`, Activities, WorkManager or UI types.

## Consequences

- the same collector logic can later execute in an Android worker or optional server process;
- source adapters are unit-testable without an emulator;
- Android is responsible for scheduling and connectivity policy, not parsing semantics.
