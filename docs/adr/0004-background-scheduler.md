# ADR 0004: Persisted scheduler above WorkManager

**Status:** Accepted

## Decision

Use a small number of WorkManager jobs to wake the application. Source-specific schedules are persisted as data (`nextCheckAt`) and interpreted by an application scheduler.

## Consequences

- thousands of sources do not become thousands of WorkManager periodic jobs;
- collection intervals can change without re-registering Android jobs;
- the scheduling algorithm is portable to a future cloud worker.
