# Library and offline reading acceptance

This document is the product acceptance gate for PR #17 (`feat/library-offline-reading`). It builds on the reliability guarantees already established by PR #16 and does not replace `HARDENING-ACCEPTANCE.md`.

The scope of this gate is intentionally narrow: correct text decoding, durable saved-document metadata, local Library reading, reactive Inbox/Library presentation, pagination, and database compatibility through Room version 6.

Semantic search, embeddings, LLM features, notes/tags, editing, document deletion, cloud sync and scheduler continuation redesign are outside this gate. Scheduler continuation/backoff refinement is tracked separately in issue #18.

## 1. Content decoding

Acceptance criteria:
- raw response bytes are decoded only after the response has been persisted;
- precedence is deterministic: BOM -> HTTP charset -> HTML/XML metadata -> UTF-8 fallback;
- BOM overrides a conflicting HTTP charset;
- HTTP charset overrides conflicting document metadata;
- UTF-8 Cyrillic text is preserved exactly;
- Windows-1251 text is decoded correctly when declared by HTTP or document metadata;
- unsupported selected charset and malformed bytes fail extraction instead of silently introducing replacement characters.

Automated evidence:
- `ContentExtractionTest`
- deterministic fixtures under `pipeline/src/test/resources/fixtures/encoding/`
- `EncodingRawReopenIntegrationTest` for persisted raw bytes across database reopen.

## 2. Parser-version integrity

Acceptance criteria:
- the parser/extractor version describing normalized Inbox text is captured when extraction produces that text;
- Save must copy the Inbox parser version into a new `DocumentVersion` rather than assigning the current Save-time parser version;
- changing the application parser version later must not relabel historical extracted text;
- pending Inbox rows from v5 migrate to v6 with a conservative historical extractor-v1 marker.

Automated evidence:
- `InboxLifecyclePersistenceTest`
- `Migration5To6Test`
- full migration-chain tests updated through v6.

## 3. Room v6 compatibility

Acceptance criteria:
- the application database version is 6;
- production registers `MIGRATION_5_6` in addition to all historical migrations;
- v5 pending Inbox data survives migration with parserVersion populated;
- both historical physical v4 variants still migrate through 4->5->6;
- a database created from the exported v1 schema migrates through every supported step and opens through the current generated Room implementation;
- generated `6.json` is committed and CI verifies that generated schema output matches Git.

Automated evidence:
- `Migration5To6Test`
- `Migration4To5CompatibilityTest`
- `MigrationFullChainV5Test` (historical filename; its assertions now continue through v6)
- CI Room-schema consistency gate.

## 4. Saved Library read model

Acceptance criteria:
- Library includes only saved documents;
- list rows load lightweight metadata/snippet rather than full normalized document text;
- exact saved-document count is observable independently from the currently loaded page;
- ordering is stable by saved timestamp and document id;
- keyset pagination neither loses nor duplicates rows when more than one page exists or multiple rows share the same timestamp;
- source summary is derived from immutable provenance snapshots.

Automated evidence:
- `LibraryReadIntegrationTest`
- `LibraryViewModelTest`.

## 5. Offline document reading

Acceptance criteria:
- opening a saved Library item reads normalized text from the local Room database;
- full saved normalized text remains available after database close/reopen without a network request;
- all saved provenance rows remain available after reopen;
- original/resolved URLs are metadata only and network/browser access happens only after explicit user action;
- the product promise is extracted normalized text + provenance, not a complete archival copy of page styling, images or attachments.

Automated evidence:
- `LibraryFilePersistenceTest`
- `LibraryReadIntegrationTest`
- `LibraryViewModelTest.offlineDocumentFlowUpdatesOpenDetailFromLocalRepository`.

## 6. Provenance preservation

Acceptance criteria:
- saved Library detail exposes all durable provenance rows for the document;
- historical Source name, URL and type come from snapshots captured during ingestion, not current mutable Source settings;
- adding the Library UI does not introduce a new write path that can bypass transactional Inbox Save;
- Save continues to use the existing `InboxService` / `saveCurrent` transaction and processing ownership guarantees from PR #16.

Automated evidence:
- `LibraryReadIntegrationTest`
- `LibraryFilePersistenceTest`
- existing PR #16 provenance and Inbox lifecycle integration tests remain part of the full test suite.

## 7. Reactive state and bounded pagination refresh

Acceptance criteria:
- Inbox and Library expose exact counts independently from the currently loaded page;
- keyset pagination is stable for datasets larger than one page and for equal timestamps;
- background invalidation refreshes the already loaded bounded range instead of collapsing the screen back to the first 20 rows;
- after a refresh error the visible rows, next key and `hasMore` remain mutually consistent;
- retry refreshes the same loaded window, and the next `loadMore` continues from the preserved/recomputed key rather than starting again from page one;
- Save of an Inbox row after multiple pages are loaded removes that row, backfills the loaded window from the next row, and preserves a valid continuation cursor;
- Flow failures are visible UI state rather than an empty list or an endless spinner;
- retry cancels the prior observation job before reconnecting, so repeated retry cannot accumulate duplicate subscriptions;
- `CancellationException` is propagated and is not presented as a database/UI error.

Automated evidence:
- `InboxPagingIntegrationTest`
- `InboxViewModelTest`, including multi-page Save/removal, equal-timestamp paging, failed refresh/retry and subscription reconnection;
- `LibraryViewModelTest`, including multi-page background insertion/update, failed refresh/retry and subscription/detail reconnection.

## 8. Application-level count subscriptions

The top-level Inbox/Library badges no longer collect Room count Flows directly with an implicit `0` fallback. `AppCountsViewModel` owns one reconnectable observation job and retains the last successful values if observation fails.

Acceptance criteria:
- an observation failure is visible above the section selector;
- before the first successful value the badge renders `?` rather than falsely reporting zero;
- retry cancels the prior observation job and starts one new pair of count subscriptions;
- subsequent count changes are visible after reconnect;
- cancellation is not converted into a database-error message.

Automated evidence:
- `AppCountsViewModelTest`.

## 9. Save while Library is actively observed

CI #321 originally exposed two independent test classes of failure and they were handled separately.

### Robolectric SDK mismatch

`LibraryAndroidBuilderIntegrationTest` originally inherited the application target SDK and Robolectric 4.16.1 refused to run it because target SDK 37 exceeded that runner's supported maximum. Production `compileSdk`/`targetSdk` were not changed. The test is explicitly pinned to Robolectric SDK 35, which is supported by the current JDK 17/Robolectric toolchain.

### Host-JVM/path-builder SQL failures

The remaining diagnostic failures occurred only in tests that opened Room through the contextless JVM/path builder while running inside the Android unit-test module. The same full operation succeeds through the builder used by the application: `Room.databaseBuilder<ArticleNavigatorDatabase>(Context, name)` + `BundledSQLiteDriver`.

The retained regression `LibraryAndroidBuilderIntegrationTest` now keeps both `observeSavedCount()` and `observeRevision()` active while `saveCurrent(existingDocument)` performs the complete transactional update. It additionally uses the same provenance key before and after Save, so neither saved-document count nor provenance count changes. The test requires a second revision invalidation emission with the same scalar value and then verifies:
- updated title/text;
- original `createdAt` preserved;
- `updatedAt` advanced;
- exactly two document versions;
- provenance remains one row for the same origin;
- consumed Inbox row is gone.

A separate file-backed close/reopen regression keeps proving that an existing saved document can be updated transactionally and that versions/provenance survive a new Room runtime.

The earlier contextless connection-pool / explicit-transaction / invalidation probes were useful for narrowing the failure but did not represent the application's Android builder. After their hypotheses were exhausted they were removed rather than retained as permanently red duplicates. The stronger Android-builder behavior check remains enabled.

**Evidence boundary:** the current repository/connected CI does not provide an Android emulator system image or a physical device. Therefore this PR has Robolectric SDK35 coverage using the production-style Android Room builder plus file-backed JVM reopen tests, but it does **not** claim a physical-device/emulator execution of this scenario.

## 10. Android lifecycle boundary

Implemented behavior:
- `MainActivity` stores the selected top-level section and selected saved `DocumentId` with `rememberSaveable`;
- Inbox, Library and top-level counts are collected through lifecycle-aware Compose state backed by ViewModels;
- Inbox and Library actions/state live in ViewModels rather than in a transient composable coroutine;
- Sources/Diagnostics still refresh on section entry; Inbox/Library depend on Room/Flow invalidation instead of periodic polling;
- closing Library detail clears the saveable selected id, while opening a row or successful Inbox Save updates it.

Automated evidence covers ViewModel state/reconnection, Room invalidation and file-backed persistence. There is **no dedicated end-to-end Activity/Compose recreation or physical process-death test in this PR** that recreates `MainActivity` and asserts the rendered screen afterward. The repository currently has no app-level Compose activity test harness. This is a UI-test coverage limitation, not a known data-integrity defect; durable Library data itself is covered by file-backed close/reopen tests.

## 11. CI / release gate

PR #17 may leave Draft only when the exact current head passes all repository gates:
- wrapper validation and clean-checkout toolchain;
- `./gradlew test --stacktrace`;
- Room schema consistency;
- `./gradlew lint --stacktrace`;
- `./gradlew assembleDebug --stacktrace`;
- debug APK identity/signature verification;
- schema and debug APK artifact upload.

The PR must remain unmerged until merge is a separate explicit decision. No automatic merge is authorized by this document.

## Remaining non-blocking limits

- no full-page/offline asset archiving: normalized extracted text is the durable reading format;
- no physical-device/emulator verification in the current CI environment;
- no app-level Activity/Compose recreation/process-death rendering test yet;
- scheduler successful-continuation and infrastructure retry currently share WorkManager retry/backoff history; tracked in #18;
- the current runtime wires only `DefaultContentExtractor`; before adding any alternative extractor the extraction contract must make its durable parser version explicit, tracked in #19;
- exact full-text search, export/restore and Android Share intake are separate later product stages.
