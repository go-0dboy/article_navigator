# Hardening acceptance criteria

Scope: PR #15, branch `fix/phase4-hardening`.

This document is the release gate for the Phase 4 hardening pass. It intentionally excludes semantic search, embeddings, LLM features, recommendations, and other new product functionality.

## 1. Persisted collection lease

Acceptance criteria:
- two concurrent runs cannot own the same Source lease;
- the loser reports `ALREADY_CLAIMED` and does not perform the network request;
- an abandoned expired lease is reclaimable;
- the atomic claim re-checks the due schedule so a stale in-memory due snapshot cannot re-collect a Source already scheduled into the future;
- expired/replaced/settings-stale owners cannot commit.

Automated evidence:
- `CollectionHardeningIntegrationTest.concurrentCollectionRunsClaimOnceReportAlreadyClaimedAndFetchOnce`
- `CollectionHardeningIntegrationTest.expiredLeaseCanBeClaimedByNextRunWithoutRelease`
- `CollectionHardeningIntegrationTest.staleRunCannotOverwriteNewerCommittedState`
- `CollectionHardeningIntegrationTest.expiredLeaseCannotCommitEvenIfNoReplacementClaimedIt`
- `CollectionLeaseDueGuardIntegrationTest.staleDueSnapshotCannotClaimAfterNewerRunSchedulesSourceInFuture`

## 2. Atomic successful collection commit

Acceptance criteria:
- discovery sightings, cursor, collection diagnostics, next schedule, successful-check timestamp, and lease release are committed as one Room/SQLite transaction;
- an exception during the transaction rolls back every write;
- no state such as “items persisted but cursor not persisted” is observable.

Automated evidence:
- `CollectionHardeningIntegrationTest.commitSuccessRollsBackAllRowsWhenMiddleWriteFails`
- `CollectionHardeningIntegrationTest.staleRunCannotOverwriteNewerCommittedState`

## 3. Source configuration isolation

Acceptance criteria:
- scheduler code does not use stale full-row Source upserts for operational state;
- URL, name, enabled, poll policy, adapter type/configuration remain user-owned;
- schedule, lease, collection diagnostics, and successful-check metadata use targeted operational writes;
- changing Source configuration invalidates an in-flight collection result rather than being overwritten by it.

Automated evidence:
- `CollectionHardeningIntegrationTest.userSourceEditsInvalidateInFlightCollectorWithoutBeingReverted`

## 4. DiscoveredItem race safety

Acceptance criteria:
- repeat sightings do not overwrite ingestion-owned processing status, content hash, attempts, retry schedule, or processing error;
- first discovery time is immutable;
- last seen time advances on repeat discovery;
- concurrency-sensitive scheduler writes are targeted SQL operations rather than read/merge/full-upsert.

Automated evidence:
- `CollectionHardeningIntegrationTest.repeatedSightingPreservesNewerIngestionStateAndFirstDiscoveryTime`

## 5. HTTP cancellation

Acceptance criteria:
- cancelling the coroutine cancels the underlying OkHttp Call;
- cancellation propagates as `CancellationException`;
- a slow response body is not fully consumed after cancellation;
- collection cancellation is not recorded as an ordinary Source failure and the owned lease is released best-effort.

Automated evidence:
- `OkHttpTransportTest.cancellingCoroutineCancelsOkHttpCallAndStopsBodyRead`
- `CollectionRetrySemanticsTest.cancellationReleasesLeaseAndIsNotRecordedAsSourceFailure`

## 6. Network resource limits

Acceptance criteria:
- advertised Content-Length above the configured limit fails with `ResponseTooLargeException`;
- chunked/unknown-length bodies are bounded while streaming;
- exact-limit and below-limit responses succeed;
- feed and article limits are distinct and bounded;
- OkHttp has bounded global/per-host concurrency and a call timeout.

Automated evidence:
- `OkHttpTransportTest.contentLengthAboveLimitFailsBeforeBufferingBody`
- `OkHttpTransportTest.chunkedBodyWithoutContentLengthIsLimitedWhileStreaming`
- `OkHttpTransportTest.responseExactlyAtLimitSucceeds`
- `OkHttpTransportTest.responseBelowLimitSucceeds`
- `ResponseLimitPolicyTest.feedAndArticleUseDistinctBoundedResponseLimits`

Current policy:
- feed: 2 MiB;
- article: 5 MiB.

## 7. HTTP retry/backoff semantics

Acceptance criteria:
- 429 and transient 5xx responses use retry policy;
- 401/403/404 are permanent for automatic acquisition and do not enter aggressive exponential retry;
- transport timeouts are transient;
- `Retry-After` supports delta-seconds and RFC-1123 HTTP-date;
- invalid or past `Retry-After` falls back to local policy;
- server delay is treated as a minimum and wins only when greater than local backoff.

Automated evidence:
- `RssAtomSourceAdapterTest.retryAfterSecondsIsExposedFor429And503`
- `RssAtomSourceAdapterTest.retryAfterHttpDateIsParsed`
- `RssAtomSourceAdapterTest.pastOrInvalidRetryAfterIsIgnored`
- `RssAtomSourceAdapterTest.permanentHttpErrorsAreNotRetryableButServerErrorsAre`
- `CollectionRetrySemanticsTest.serverRetryAfterWinsWhenLongerThanLocalBackoff`
- `CollectionRetrySemanticsTest.permanentHttpClassificationUsesNormalPollIntervalNotAggressiveRetry`
- `CollectionRetrySemanticsTest.transportTimeoutUsesTransientLocalBackoff`
- `IngestionHttpRetrySemanticsTest.retryAfterSecondsOverridesLocalBackoffOnlyWhenLonger`
- `IngestionHttpRetrySemanticsTest.shortRetryAfterDoesNotReduceLocalBackoff`
- `IngestionHttpRetrySemanticsTest.retryAfterHttpDateOverridesLocalBackoff`
- `IngestionHttpRetrySemanticsTest.pastOrInvalidRetryAfterFallsBackToLocalBackoff`
- `IngestionHttpRetrySemanticsTest.permanentClientErrorsAreSkippedWithoutRetry`
- `IngestionHttpRetrySemanticsTest.http500UsesTransientLocalBackoff`
- `IngestionHttpRetrySemanticsTest.transportTimeoutUsesTransientLocalBackoff`

## 8. Feed validators

Acceptance criteria:
- 304 without ETag/Last-Modified preserves stored validators;
- 200 without those headers clears the corresponding old validators.

Automated evidence:
- `RssAtomSourceAdapterTest.notModifiedWithoutValidatorsPreservesPreviousValidators`
- `RssAtomSourceAdapterTest.successfulResponseWithoutValidatorsClearsPreviousValidators`

## 9. Provenance durability

Acceptance criteria:
- every saved Document retains discovery provenance;
- provenance includes immutable Source snapshots needed to explain origin even after later Source edits;
- Source archive/disable does not change saved provenance;
- physical Source deletion is rejected while durable provenance/seen-history references exist;
- migration 3→4 backfills provenance snapshots without silent loss.

Automated evidence:
- `CollectionHardeningIntegrationTest.archivedSourceDoesNotChangeSavedProvenanceSnapshot`
- `MigrationHardeningTest.migration3To4PreservesOperationalInboxKnowledgeAndProvenanceData`
- `MigrationHardeningTest.migration1To2To3To4PreservesDataAcrossFullSupportedChain`

## 10. Database migrations

Acceptance criteria:
- 3→4 succeeds with data preserved;
- 1→2→3→4 succeeds with data preserved;
- seeded coverage includes Sources, cursors, collection state, discovered items, Inbox, Documents, versions, provenance and seen history;
- critical indexes and foreign-key delete policies are verified.

Automated evidence:
- `MigrationHardeningTest.migration3To4PreservesOperationalInboxKnowledgeAndProvenanceData`
- `MigrationHardeningTest.migration1To2To3To4PreservesDataAcrossFullSupportedChain`
- CI Room-schema consistency gate.

## 11. Reproducible clean checkout

Acceptance criteria:
- no system Gradle dependency;
- committed Wrapper JAR is checksum-verified;
- Gradle distribution SHA-256 is pinned;
- all build gates use `./gradlew`;
- Room generated schema remains committed and reproducible.

Required commands/gates:

```bash
./gradlew --version
./gradlew test --stacktrace
./gradlew lint --stacktrace
./gradlew assembleDebug --stacktrace
```

CI additionally verifies the debug APK signature and application id.

## Failure-domain policy

Source-local remote/adapter failures are persisted per Source. `CancellationException` is never downgraded to a normal Source failure. Shared database/storage failures remain run-level infrastructure failures and are passed to the Android Worker for bounded WorkManager retry; they are not falsely recorded as remote Source failures.

This is why audit D6 is classified **PARTIALLY FIXED** rather than hiding infrastructure failures behind source-local diagnostics.

## Final Definition of Done

PR #15 may leave Draft only when all of the following are true:
- unit tests are green;
- Room integration tests are green;
- migration tests are green;
- concurrency/lease tests are green;
- cancellation/network-limit/retry tests are green;
- Android lint is green;
- `assembleDebug` is green;
- GitHub Actions for the current head is fully green;
- clean checkout uses only the committed Gradle Wrapper;
- `docs/AUDIT-2026-09-15.md` classifies D1–D11;
- no known Critical/High data-integrity defect remains.

Until every gate above is satisfied, PR #15 stays Draft. This document does not authorize automatic merge; merge remains a separate explicit action.
