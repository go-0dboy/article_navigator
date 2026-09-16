# Hardening acceptance criteria

PR #15 established the Phase 4 collection/network/data-integrity baseline and is merged into `main`. The current release gate is PR #16, branch `fix/reliability-stage2`, covering the existing discovery → article fetch → processing → Inbox → save/reject chain through Room database version 5.

Semantic search, embeddings, LLM features, recommendations, and unrelated product functionality are explicitly outside this reliability gate.

## 1. Persisted Source collection lease

Acceptance criteria:
- two concurrent collection runs cannot own the same Source lease;
- the loser reports `ALREADY_CLAIMED` and does not issue the network request;
- an abandoned expired lease is reclaimable;
- a stale owner cannot release a replacement owner's lease;
- claim atomically re-checks the due schedule;
- expired/replaced/settings-stale owners cannot commit.

Evidence includes `CollectionHardeningIntegrationTest`, `CollectionLeaseDueGuardIntegrationTest`, and `CollectionLeaseReleaseIntegrationTest`.

## 2. Atomic Source collection commit

Acceptance criteria:
- discovery sightings, cursor, diagnostics, next schedule, successful-check timestamp and lease release commit in one Room/SQLite transaction;
- an exception rolls back every write;
- stale owners cannot partially publish a collection result.

Evidence includes `CollectionHardeningIntegrationTest.commitSuccessRollsBackAllRowsWhenMiddleWriteFails` and stale-owner commit tests.

## 3. Source configuration isolation

Acceptance criteria:
- scheduler/runtime code does not overwrite user-owned Source settings from stale snapshots;
- URL, name, enabled state, poll policy, adapter type and configuration remain user-owned;
- operational scheduling/lease state uses targeted writes;
- changing Source configuration invalidates an in-flight collector result.

Evidence: `CollectionHardeningIntegrationTest.userSourceEditsInvalidateInFlightCollectorWithoutBeingReverted`.

## 4. Discovery sighting integrity

Acceptance criteria:
- repeat sightings do not overwrite ingestion status, hash, attempts, retry schedule or processing error;
- first discovery time is immutable;
- last-seen advances on repeat discovery;
- runtime scheduler writes are targeted rather than full-row merge/upsert.

Evidence: `CollectionHardeningIntegrationTest.repeatedSightingPreservesNewerIngestionStateAndFirstDiscoveryTime`.

## 5. Persisted article-processing ownership

Acceptance criteria:
- runtime ingestion must acquire a persisted expiring article lease before processing;
- ownership is identified by item + token + unexpired expiry;
- an expired or replaced owner cannot persist raw bytes, mark `FETCHED`, mark retry/failure, skip, finalize success, or release a replacement lease;
- two workers cannot fetch the same discovery concurrently under valid ownership;
- abandoned ownership becomes reclaimable after persisted expiry;
- cancellation and escaping shared-infrastructure exceptions release owned processing best-effort in `NonCancellable` context without hiding the original cancellation/failure.

Automated evidence:
- `ProcessingLeaseStaleFinalizeTest`
- `ReliabilityStage2RegressionTest.twoHandlersCannotFetchTheSameDiscovery`
- `ReliabilityStage2RegressionTest.expiredOwnerCannotPersistRawFailureSuccessOrReleaseReplacementLease`
- `ReliabilityStage2RegressionTest.infrastructureFailureAfterClaimReleasesLeaseForImmediateRetry`
- `IngestionCrashRecoveryIntegrationTest.claimedWorkBecomesAvailableOnlyAfterPersistedLeaseExpiresAcrossReopen`

## 6. Crash-resumable raw article response

Acceptance criteria:
- successful article HTTP bytes are durably stored before extraction/finalization;
- v5 raw content stores BLOB payload, content type, resolved URL, fetch timestamp, status and optional expiry;
- valid persisted raw is reused after DB/process reopen without another HTTP request;
- a persisted `FETCHED` intermediate state is recoverable after reopen;
- failed terminal finalization rolls back and leaves recoverable raw/intermediate state;
- expired raw is not reused and a successful refetch deterministically replaces the old row under current ownership;
- terminal processed/skipped work removes temporary raw data.

Automated evidence:
- `IngestionCrashRecoveryIntegrationTest.durableRawBytesResumeAfterReopenWithoutSecondHttpRequest`
- `IngestionCrashRecoveryIntegrationTest.fetchedIntermediateStateResumesAfterReopenWithoutHttp`
- `IngestionCrashRecoveryIntegrationTest.failedFinalizationRollsBackCompletelyAndWorkRecoversAfterReopen`
- `IngestionCrashRecoveryIntegrationTest.expiredRawIsNotReusedAndSuccessfulRefetchReplacesIt`

## 7. Atomic article finalization and deduplication

Acceptance criteria:
- runtime success re-checks article ownership inside the final transaction;
- saved-document match, dismissed-fingerprint match, pending-Inbox merge, or new-Inbox creation is decided transactionally;
- origin/provenance/fingerprint writes, discovery `PROCESSED`, lease clearing and raw cleanup share the terminal boundary;
- a stale owner returns `STALE` instead of publishing partial state;
- the same material discovered by multiple Sources converges without losing either origin.

Automated evidence:
- `ProcessingLeaseStaleFinalizeTest`
- `ReliabilityStage2RegressionTest.sameMaterialFromTwoSourcesProducesOneInboxWithBothOrigins`
- rollback/reopen tests in `IngestionCrashRecoveryIntegrationTest`

## 8. Atomic Inbox Save / Reject / Read-and-Discard

Acceptance criteria:
- Save reads the current Inbox row and all current origins inside one transaction;
- Save resolves or creates the Document/version, copies all immutable provenance, writes seen fingerprints, finishes discoveries, removes raw content and consumes Inbox atomically;
- Reject and Read-and-Discard persist all current dismissal fingerprints and consume Inbox atomically;
- Save-vs-Reject has exactly one committed winner;
- repeat Save after Inbox consumption cannot succeed;
- a late origin after committed Save attaches to the saved Document instead of being lost;
- a late origin after committed dismissal inherits the dismissal instead of recreating Inbox.

Automated evidence:
- `ReliabilityStage2RegressionTest.saveCommittedBeforeLateOriginRoutesThatOriginIntoSavedDocument`
- `ReliabilityStage2RegressionTest.rejectCommittedWhileOldProcessingRunsCannotRecreateInbox`
- `ReliabilityStage2RegressionTest.saveAndRejectRaceHasExactlyOneCommittedWinnerAndRepeatSaveFails`
- `InboxLifecyclePersistenceTest`
- `InboxServiceTest`

## 9. Provenance durability

Acceptance criteria:
- every pending/saved origin retains immutable Source name/URL/type snapshots captured at ingestion time;
- Save does not re-read mutable Source metadata;
- Source edits/archive do not rewrite historical provenance;
- hard Source deletion is rejected while pending Inbox provenance, saved Document provenance, or retained seen-history references exist;
- migration backfills legacy provenance snapshots without silent loss;
- stage-2 late-origin routing preserves the same provenance guarantees.

Automated evidence:
- `InboxProvenancePersistenceTest.sourceEditsAfterInboxAttachmentDoNotRewriteOriginSnapshot`
- `CollectionHardeningIntegrationTest.archivedSourceDoesNotChangeSavedProvenanceSnapshot`
- `MigrationFullChainV5Test`
- `Migration4To5CompatibilityTest`

## 10. HTTP cancellation and resource limits

Acceptance criteria:
- coroutine cancellation cancels the underlying OkHttp Call and propagates as cancellation;
- slow bodies are not fully consumed after cancellation;
- Content-Length above the configured bound fails before buffering;
- chunked/unknown-length responses are bounded while streaming;
- exact-limit/below-limit responses succeed;
- feed and article limits remain distinct and bounded;
- global/per-host concurrency and call timeout are bounded.

Current limits:
- feed: 2 MiB;
- article: 5 MiB.

Evidence: `OkHttpTransportTest` and `ResponseLimitPolicyTest`.

## 11. HTTP retry/backoff and feed validators

Acceptance criteria:
- 429 and transient 5xx use retry policy;
- 401/403/404 are permanent for automatic acquisition;
- transport timeout is transient;
- `Retry-After` supports delta-seconds and RFC-1123 date;
- invalid/past values fall back to local policy;
- server delay is a minimum and never shortens local backoff;
- 304 without validators preserves stored validators;
- 200 without validators clears stale validators;
- article retry mutations remain guarded by current processing ownership.

Evidence: `RssAtomSourceAdapterTest`, `CollectionRetrySemanticsTest`, and `IngestionHttpRetrySemanticsTest`.

## 12. Bounded collection/ingestion passes

Acceptance criteria:
- one WorkManager pass has a shared absolute time budget;
- discovery has a bounded per-pass Source count;
- ingestion has a bounded per-pass article count;
- reaching an item cap sets `budgetExhausted` only if eligible persisted work still remains;
- an ingestion continuation probe uses the same persisted ownership protocol and immediately releases the probe lease;
- no work is consumed merely to detect backlog;
- deadline exhaustion reports durable continuation rather than entering an in-process busy loop.

Evidence: scheduler-core budget tests in `CollectionOrchestratorTest` and ingestion budget tests in `IngestionPipelineTest`.

## 13. WorkManager integration

Acceptance criteria:
- periodic collection is unique and requires `CONNECTED` network plus battery-not-low;
- immediate collection is unique and requires `CONNECTED` network;
- immediate replacement/cancellation semantics are verified against WorkManager test infrastructure;
- shared database/storage exceptions are not downgraded to Source/article failures and request bounded infrastructure retry;
- a pass that exhausts its work budget requests persisted WorkManager continuation;
- combined collection + ingestion diagnostics are exposed as worker progress/result data.

Automated evidence:
- `CollectionWorkSchedulerTest.periodicWorkRequiresNetworkAndHealthyBattery`
- `CollectionWorkSchedulerTest.immediateWorkRequiresNetworkButDoesNotRequireHealthyBattery`
- `CollectionWorkSchedulerTest.ensurePeriodicRegistersOneUniquePeriodicWork`
- `CollectionWorkSchedulerTest.runNowReplacesPreviousImmediateWork`
- `CollectionWorkSchedulerTest.cancelImmediateCancelsCurrentUniqueWork`
- `CollectionWorkSchedulerTest.sharedInfrastructureFailureRequestsWorkManagerRetry`
- `CollectionWorkSchedulerTest.exhaustedSharedPassBudgetRequestsPersistedRetry`

Known non-integrity refinement: repeated budget continuations currently use WorkManager `Result.retry()` and therefore share exponential-backoff timing. This preserves work and avoids busy looping, but a later scheduler refinement can improve very-large-backlog drain latency with a dedicated continuation-work policy.

## 14. Database version 5 and migration compatibility

Acceptance criteria:
- production opens Room with migrations 1→2, 2→3, 3→4 and 4→5 registered;
- `MIGRATION_4_5` accepts both physical v4 layouts known to have existed in repository history;
- legacy raw TEXT is converted to v5 BLOB without changing the stored UTF-8 bytes;
- v4 Inbox origin snapshots are preserved when present and backfilled from Source when absent;
- article lease fields/index are created;
- critical provenance/seen Source foreign keys remain `ON DELETE RESTRICT`;
- the committed v5 Room schema is generated, reproducible and matches entities/migrations;
- a real exported v1 schema migrates through 1→2→3→4→5 with seeded domain data preserved;
- the migrated file opens successfully through the current generated Room v5 implementation.

Automated evidence:
- `MigrationFullChainV5Test.exportedVersion1MigratesThroughEverySupportedStepToCurrentRoom`
- `Migration4To5CompatibilityTest`
- existing `MigrationHardeningTest` coverage for historical steps
- CI Room-schema consistency gate.

Generated v5 Room identity hash: `e567d35de736b7412b95dfcbb18f6d87`.

## 15. Reproducible clean checkout and APK gates

Acceptance criteria:
- no system Gradle dependency;
- committed Wrapper JAR is checksum-verified;
- Gradle distribution SHA-256 is pinned;
- build gates use `./gradlew` on JDK 17;
- Room generated schemas stay committed and reproducible;
- Android lint passes;
- debug APK assembles;
- CI verifies APK application identity/signature and uploads the artifact.

Required CI commands/gates include:

```bash
./gradlew --version
./gradlew test --stacktrace
./gradlew lint --stacktrace
./gradlew assembleDebug --stacktrace
```

## Failure-domain policy

Source-local remote/adapter failures are persisted per Source. Article-local HTTP/extraction outcomes are persisted under article-processing ownership. `CancellationException` is never downgraded to a normal failure. Shared database/storage failures remain run-level infrastructure failures and propagate to the Android Worker for WorkManager retry.

If an escaping infrastructure exception occurs after an article lease was acquired, the pipeline attempts ownership-safe release in `NonCancellable` context so the retry is not blocked by its own lease. If storage cannot perform that release, persisted lease expiry is the recovery fallback. This is an explicit failure-domain design, not silent failure conversion.

## Current acceptance evidence

Implementation head `db2c8a3f507861de20b65410705ff726613598bb` passed Android CI run #272 end-to-end on 2026-09-16. That run passed:
- wrapper validation and clean-checkout toolchain;
- complete unit, Room, migration, concurrency, crash-recovery and WorkManager tests;
- Room schema consistency;
- Android lint;
- `assembleDebug`;
- APK identity/signature verification;
- schema and APK artifact upload.

## Final Definition of Done for PR #16

PR #16 may leave Draft only when all of the following are true:
- persisted Source and article ownership tests are green;
- Room integration and atomic lifecycle tests are green;
- crash/reopen recovery tests are green;
- full migration 1→2→3→4→5 and both-v4 compatibility tests are green;
- network cancellation/limit/retry tests are green;
- WorkManager constraints/unique-work/retry tests are green;
- bounded-pass continuation tests are green;
- committed generated Room v5 schema matches build output;
- Android lint is green;
- `assembleDebug` and APK identity/signature gates are green;
- GitHub Actions for the **current PR head** is fully green;
- `docs/AUDIT-2026-09-15.md` reflects stage-2 results;
- no known Critical/High defect remains in the PR scope.

D11 (UI polling) remains outside this integrity scope. The continuation-backoff refinement is also non-blocking because persisted work is not lost.

Until the documentation-updated current head passes the workflow above, PR #16 stays Draft. This document does not authorize automatic merge; merge remains a separate explicit action.
