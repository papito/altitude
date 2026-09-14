# Service-layer test coverage

Static review of all 22 Scala files under `altitude/src/altitude/core/service/`, including the file-store interface and implementation, plus their pipeline flows and the integration, unit, and controller tests. Reviewed on 2026-09-12. This is use-case coverage from source inspection, not an instrumented line/branch coverage measurement or confirmation that the tests currently pass. Tests were not executed for this report.

✅ means an active test asserts the stated behavior; indirect controller coverage and smoke checks are explicitly identified. Empty tests, commented-out tests, unused Boolean expressions, and fixture setup alone do not establish behavioral coverage. Missing cases are test recommendations, not claims that every listed behavior is broken.

Severity: **CRITICAL** — security boundaries, permanent data loss, or failure recovery that can leave substantial persistent corruption; **MEDIUM** — incorrect core workflows or inconsistent state; **MINOR** — limited output or convenience behavior; **EDGE** — unusual inputs or boundary conditions. Related cases are grouped under their primary concern rather than repeated for every caller.

The [integration bundle](../altitude/test/src/altitude/core/suites/AllIntegrationTestSuites.scala) registers the same service suites for SQLite and PostgreSQL. Relevant helper tests are in the [unit bundle](../altitude/test/src/altitude/core/suites/AllUnitTestSuites.scala); [controller tests](../altitude/test/src/altitude/core/controller/ControllerTestCore.scala) use real HTTP handlers with SQLite. Suite registration establishes the intended execution matrix, not a passing result. The [Makefile](../Makefile) exposes `test-unit`, `test-sqlite`, `test-controllers`, and `test-psql`; the last requires the PostgreSQL test container.

## Repository isolation and shared service transactions

Sources: [BaseService](../altitude/src/altitude/core/service/BaseService.scala), [TransactionManager](../altitude/src/altitude/core/transactions/TransactionManager.scala). Evidence: [AssetServiceTests](../altitude/test/src/altitude/core/integration/AssetServiceTests.scala), [AlbumServiceTests](../altitude/test/src/altitude/core/integration/AlbumServiceTests.scala), [LibraryServiceTests](../altitude/test/src/altitude/core/integration/LibraryServiceTests.scala), [SearchGroupingTests](../altitude/test/src/altitude/core/integration/SearchGroupingTests.scala).

- ✅ Persist, retrieve, update, and delete records through concrete services; reject an unknown asset ID with `NotFoundException`.
- ✅ Translate duplicate album/folder names on supported add/update paths into `DuplicateException`.
- ✅ Scope normal library queries, grouped search results/counts, and album listings to the current repository.
- [ ] Attempt reads and mutations using another repository's asset, folder, album, person, and face IDs; verify neither foreign records nor their counters/memberships change. Listing isolation does not exercise ID-based operations. [CRITICAL]
- [ ] Reject empty `updateByQuery` and `deleteByQuery` inputs without changing any rows; verify valid bulk operations remain repository-scoped with two populated repositories. [CRITICAL]
- [ ] Force a failure after an inner service writes, then verify the outer transaction rolls back every participating row and clears/closes the request connection. Successful workflows do not establish rollback behavior. [CRITICAL]
- [ ] Exercise a write nested inside a read-only service transaction, and specify/test whether it must be rejected or supported consistently on both engines. [MEDIUM]
- [ ] Verify non-duplicate SQL failures propagate unchanged, and check affected-row counts for updates/deletes that match no records. [MEDIUM]
- [ ] Verify service calls after a failed transaction can open a new connection and do not inherit the previous account/repository accidentally. [MEDIUM]

## User creation and password authentication

Sources: [UserService](../altitude/src/altitude/core/service/UserService.scala). Evidence: [UserServiceTests](../altitude/test/src/altitude/core/integration/UserServiceTests.scala), [LoginControllerTests](../altitude/test/src/altitude/core/controller/LoginControllerTests.scala).

- ✅ Create a password-bearing user and retrieve the same persisted ID.
- ✅ Correct credentials produce a login result; a wrong password or nonexistent user produces `None`.
- ✅ A valid HTTP login returns a cookie that authenticates a subsequent page request; invalid HTTP credentials receive 401.
- [ ] Assert the authenticated user's exact identity, account type, nonempty token, and `RequestContext.account` after login. The direct success test only checks that the result exists. [MEDIUM]
- [ ] Assert failed login does not replace the current account with the attempted user; cover database lookup failure as well as invalid credentials. [MEDIUM]
- [ ] Verify password storage contains a usable hash rather than plaintext, and define/test duplicate-email registration behavior without creating another account. [MEDIUM]
- [ ] Verify both unknown-user and wrong-password paths perform a password check; use deterministic observation rather than a fragile wall-clock timing assertion. [MEDIUM]
- [ ] Read the user back after `setLastActiveRepoId` and assert the saved repository ID. The existing test calls the setter without checking its result. [MEDIUM]
- [ ] Cover `getDevUser` with complete valid credentials, invalid credentials, and either configuration value absent. [MINOR]
- [ ] Verify adding a user through the passwordless overload is rejected without persistence. [EDGE]

## Session token creation and validation

Sources: [PasetoService](../altitude/src/altitude/core/service/PasetoService.scala), [UserService](../altitude/src/altitude/core/service/UserService.scala). Evidence: [LoginControllerTests](../altitude/test/src/altitude/core/controller/LoginControllerTests.scala). There is no dedicated PASETO test suite.

- ✅ A token generated during login can authenticate a cookie-based request through `validateTokenAndGetUser` (indirect HTTP coverage).
- [ ] Reject expired tokens through both validation methods; include the expiration boundary using controlled time. [CRITICAL]
- [ ] Reject modified ciphertext, a token encrypted with another key, malformed input, and empty input without authenticating a user. [CRITICAL]
- [ ] Reject missing/empty subjects and invalid required user claims, including missing email/name and an unknown account type; explicitly define the policy for a missing expiration claim. [CRITICAL]
- [ ] Round-trip subject, email, name, account type, and both present/absent `lastActiveRepoId`; test `validateToken` directly as well as `validateTokenAndGetUser`. [MEDIUM]
- [ ] Verify token reconstruction works without a database connection and that `getUserFromToken`/`getByToken` update request identity only on successful validation. [MEDIUM]
- [ ] Document with a test that a new service instance generates a different key and rejects tokens from the previous instance. [MEDIUM]
- [ ] Verify the documented stateless logout contract: logout does not revoke the token, while the HTTP session removes the client cookie. [MINOR]

## Repository creation, lookup, and context

Sources: [RepositoryService](../altitude/src/altitude/core/service/RepositoryService.scala). Evidence: [RepositoryServiceTests](../altitude/test/src/altitude/core/integration/RepositoryServiceTests.scala), [SystemServiceTests](../altitude/test/src/altitude/core/integration/SystemServiceTests.scala).

- ✅ Create and retrieve a repository with the supplied name, a creation timestamp, and no update timestamp.
- ✅ System initialization creates an additional repository and user.
- [ ] Assert repository creation sets the owner/storage type, creates exactly one self-parented root folder and all six zeroed statistics, and selects the intended context. These are heavily used as fixtures but not fully asserted together. [MEDIUM]
- [ ] Fail root-folder or statistics creation and verify no partial repository remains; verify request context is usable after rollback. [CRITICAL]
- [ ] Test `setContextFromRequest` with valid, unknown, and absent IDs following a request for a different repository; prevent reuse of a stale repository context. [CRITICAL]
- [ ] Exercise cache hits and misses in `getById`, plus lookup after repository update/deletion, so cached data cannot silently remain authoritative forever. [MEDIUM]
- [ ] Define/test `getDefaultRepository` with no repositories and with multiple repositories. [EDGE]

## System initialization and metadata

Sources: [SystemService](../altitude/src/altitude/core/service/SystemService.scala). Evidence: [SystemServiceTests](../altitude/test/src/altitude/core/integration/SystemServiceTests.scala).

- ✅ Initialize an administrator and repository; assert persisted and in-memory initialized flags, user/repository counts, and the administrator in request context.
- [ ] Inject failure during initialization and verify user, repository, root folder, statistics, initialized flags, and request context do not leave a partial installation. [CRITICAL]
- [ ] Define/test repeated and concurrent initialization attempts so setup cannot create unintended additional administrators/repositories. The current test starts with fixture data but does not repeat `initializeSystem`. [MEDIUM]
- [ ] Directly verify `setVersion`/`version` persistence and `readMetadata`; distinguish an absent schema from other SQL failures currently caught by `version`. [MEDIUM]

## Database migrations

Sources: [MigrationService](../altitude/src/altitude/core/service/MigrationService.scala). Evidence: [SqliteSuiteBundle](../altitude/test/src/altitude/core/suites/SqliteSuiteBundle.scala), [PostgresSuiteBundle](../altitude/test/src/altitude/core/suites/PostgresSuiteBundle.scala). Migration execution is shared setup, not a dedicated assertion suite.

- ✅ Fresh-schema migration is exercised as a setup smoke check before each engine's integration bundle; subsequent tests use the resulting tables.
- [ ] Starting at a nonzero older version, verify the existing upgrade scripts run in order, preserve seeded user data, and stamp the resulting version. Test the current migration machinery without introducing new migrations. [CRITICAL]
- [ ] Fail a migration script and verify rollback/version behavior, then retry; separately test failure after script commit but before version stamping. [CRITICAL]
- [ ] Assert `migrationRequired` for a fresh database, an older schema, the current schema, and a newer-than-supported schema. [MEDIUM]
- [ ] Re-run `migrate` on the current schema and assert no schema/data changes; assert the exact version after fresh-schema setup. [MEDIUM]
- [ ] Cover missing/unreadable migration resources and ensure failure does not advance the schema version. [EDGE]

## Asset persistence, queries, and previews

Sources: [AssetService](../altitude/src/altitude/core/service/AssetService.scala). Evidence: [AssetServiceTests](../altitude/test/src/altitude/core/integration/AssetServiceTests.scala), [AssetQueryTests](../altitude/test/src/altitude/core/integration/AssetQueryTests.scala), [AssetImportServiceTests](../altitude/test/src/altitude/core/integration/AssetImportServiceTests.scala), [LibraryServiceTests](../altitude/test/src/altitude/core/integration/LibraryServiceTests.scala).

- ✅ Unknown asset and preview IDs raise `NotFoundException`.
- ✅ Persist an `isRecycled` update; query active assets; query an empty library and paginate ordinary results, including beyond the last page.
- ✅ Rename an active asset and verify persistence; reject renaming a recycled asset.
- ✅ Import produces nonzero image dimensions and nonempty preview data with the expected MIME type.
- [ ] Implement the empty `Search triage` test with active, recycled-triage, sorted, and foreign assets; explicitly establish `queryTriaged` filtering. [MEDIUM]
- [ ] Replace the empty `Search recycled` test with combined-filter and pagination assertions. Basic recycled counts are already covered by recycle/restore tests. [MEDIUM]
- [ ] Verify `getByChecksum` selects only an eligible asset in the current repository when the same checksum exists elsewhere or only in recycled records. [MEDIUM]
- [ ] Define/test invalid filename handling on rename and ensure rejection preserves the old filename. [MINOR]
- [ ] Assert preview size/aspect ratio and exact width/height for known images; cover corrupt/empty image bytes and the non-image zero-dimension/no-preview branches. [EDGE]
- [ ] Verify `setRecycledProp` is a no-op when the persisted state already matches and `markAsCompleted` changes only the intended asset. [EDGE]

## Import orchestration and asynchronous processing

Sources: [LibraryService](../altitude/src/altitude/core/service/LibraryService.scala), [ImportPipelineService](../altitude/src/altitude/core/service/ImportPipelineService.scala), [pipeline flows](../altitude/src/altitude/core/pipeline/flows/). Evidence: [ImportPipelineServiceTests](../altitude/test/src/altitude/core/integration/ImportPipelineServiceTests.scala), [AssetImportServiceTests](../altitude/test/src/altitude/core/integration/AssetImportServiceTests.scala).

- ✅ Import multiple assets and assert their persisted pipeline-complete flags; a void sink returns an empty result sequence.
- ✅ An in-batch duplicate is returned as one `InvalidAsset` with `DuplicateException` while the other batch assets complete.
- ✅ An unsupported media type returns an `InvalidAsset` with `UnsupportedMediaTypeException`.
- ✅ Synchronous import rejects a duplicate, produces a triaged image, and persists extracted/public metadata, checksum, size, dimensions, and preview data.
- [ ] Inject failures in metadata/dimension extraction, indexing, recognition, original-file storage, preview storage, and final completion; assert failure results, persisted state, files, and statistics rather than allowing a silently partial import. [CRITICAL]
- [ ] Exercise a duplicate/error during recognition on both engine-specific flows and assert it reaches the caller as the intended invalid result. The PostgreSQL recognition flow has a distinct catch path. [MEDIUM]
- [ ] Feed one stream interleaved assets from two repositories/accounts; assert every database row, file, index entry, statistic, and notification belongs to its supplied pipeline context. [CRITICAL]
- [ ] Exercise `addToQueue` through eventual completion, including backpressure, queue closure/failure, and shutdown with pending work. Existing import tests use finite `run` streams. [MEDIUM]
- [ ] Submit the same asset concurrently, then verify only one completed record/file set and one statistics increment remain. [MEDIUM]
- [ ] Mix unsupported/corrupt inputs with valid inputs and assert the documented continuation policy; the unsupported-type test contains only one invalid input. [MEDIUM]
- [ ] Verify user-scoped success/error notifications identify the correct asset and recipient and are not duplicated. [MEDIUM]
- [ ] Assert the mapping from `ImportAsset` to `AssetWithData`, including supplied user metadata, user identity, filename, exact checksum/size, and detected type. Existing metadata fixtures mainly enter through `addAsset`; one type assertion compares a value to itself. [MEDIUM]
- [ ] Verify an empty finite source completes cleanly with no rows, files, statistics changes, or notifications. [EDGE]

## Extracted metadata and capture dates

Sources: [MetadataExtractionService](../altitude/src/altitude/core/service/MetadataExtractionService.scala), [ExtractMetadataFlow](../altitude/src/altitude/core/pipeline/flows/ExtractMetadataFlow.scala). Evidence: [MetadataParserTests](../altitude/test/src/altitude/core/integration/MetadataParserTests.scala), [AssetDateStorageTests](../altitude/test/src/altitude/core/integration/AssetDateStorageTests.scala), [ImportPipelineServiceTests](../altitude/test/src/altitude/core/integration/ImportPipelineServiceTests.scala), [CaptureDateResolverTests](../altitude/test/src/altitude/core/unit/CaptureDateResolverTests.scala), [GeoLocationResolverTests](../altitude/test/src/altitude/core/unit/GeoLocationResolverTests.scala).

- ✅ Detect JPEG and PNG media types and MIME values; extract concrete JPEG/EXIF tags.
- ✅ Retain XMP property paths and multiple PNG text chunks without overwriting earlier keys.
- ✅ Resolve/persist actual EXIF and PNG creation times with provenance; preserve upstream metadata while extracted values replace conflicting keys.
- ✅ Preserve camera wall-clock timestamps through storage, including a DST gap and reads under different JVM time zones; store import timestamps in UTC.
- ✅ Missing capture metadata stays null, public display metadata cannot supply a capture timestamp, and an undated imported image belongs to the No date group.
- ✅ Unit tests cover fallback-source priority, metadata/filename parsing, malformed dates, sentinel/future-date rejection, and stable provenance serialization. These supplement the narrower real-import fixtures.
- ✅ Resolve GPS coordinates from real JPEG fixtures (N/E, S/W, a sub-degree western longitude whose sign only the ref carries) and persist them through the import pipeline; a coordinate without a ref, and 0/0, resolve to nothing. Coordinates round-trip through storage on both engines and stay null when absent, on the typed and the row-map read paths.
- ✅ Unit tests cover the DMS description format, hemisphere refs overriding the description's sign, a comma decimal separator, partial/garbage/out-of-range input, and the null-island rejection.
- ✅ A tag whose description is null (a GPS coordinate without its ref) is skipped rather than stored as a null value.
- [ ] Verify corrupt/unsupported bytes yield empty extracted metadata under the service's error policy, without returning partially accumulated metadata. [MEDIUM]
- [ ] Exercise null-character sanitization with real metadata values and persist the result on both engines. [MEDIUM]
- [ ] Add real-import coverage for filename fallback and XMP/IPTC/GPS/EXIF-digitized fallbacks, including an invalid higher-priority date. Resolver-only tests do not verify every extractor-to-database mapping. [MEDIUM]
- [ ] Cover actual compressed/international PNG date text and malformed XMP packets; assert retained keys and fallback behavior. [EDGE]
- [ ] Verify type detection on empty/corrupt input and a supported non-JPEG/PNG image, with resources released on detection failure. [EDGE]

## Folder hierarchy and tree counts

Sources: [FolderService](../altitude/src/altitude/core/service/FolderService.scala), [LibraryService](../altitude/src/altitude/core/service/LibraryService.scala). Evidence: [FolderServiceTests](../altitude/test/src/altitude/core/integration/FolderServiceTests.scala), [FolderControllerTests](../altitude/test/src/altitude/core/controller/FolderControllerTests.scala), [FolderActionControllerTests](../altitude/test/src/altitude/core/controller/FolderActionControllerTests.scala).

- ✅ Reject blank folder names and trim names on creation.
- ✅ Traverse immediate children, descendants, and ancestors; exclude the root from its own children and return expected child counts/order.
- ✅ Move folders between parents or to root; reject self/descendant moves, sibling-name conflicts including casing, and nonexistent destinations.
- ✅ Rename folders, change casing, and reject duplicate names or root renaming.
- ✅ Recycle a subtree; reject deleting root or an unknown folder.
- ✅ Assemble a tree with sorted children, recursive asset counts, and empty-folder zeroes; omit recycled folders, triaged assets, and unfinished imports from counts.
- ✅ Counts follow asset/folder moves, recycling/restoration, subtree deletion, and repository context; marking an already recycled asset for purge leaves folder counts unchanged.
- ✅ HTTP tree serialization carries recursive counts, and blank add/rename submissions render validation errors (indirect service coverage).
- [ ] Reject moving the root folder, with its parent/tree unchanged. Root rename/delete coverage does not cover the separate move guard. [MEDIUM]
- [ ] Exercise creation/moves into recycled parents and define the behavior; ensure an accepted operation cannot make live assets disappear from the visible tree. [MEDIUM]
- [ ] Verify adding duplicate sibling names fails while the same name under another parent is allowed; test invalid parent IDs at creation. [MEDIUM]
- [ ] Assert subtree moves preserve every descendant link and asset assignment after both success and name-conflict failure. Existing move tests emphasize child/count changes. [MEDIUM]
- [ ] Assert exact ancestor order, since restoration depends on top-down traversal; existing hierarchy assertions primarily check membership. [MEDIUM]
- [ ] Cover a missing root, orphaned descendants, and a deeply nested tree; define failure behavior rather than returning misleading counts. [EDGE]
- [ ] Verify whitespace handling on direct service rename and direct folder-delete API guards. [MINOR]

## Albums and membership

Sources: [AlbumService](../altitude/src/altitude/core/service/AlbumService.scala). Evidence: [AlbumServiceTests](../altitude/test/src/altitude/core/integration/AlbumServiceTests.scala), [AlbumControllerTests](../altitude/test/src/altitude/core/controller/AlbumControllerTests.scala), [AlbumActionControllerTests](../altitude/test/src/altitude/core/controller/AlbumActionControllerTests.scala).

- ✅ Trim names, reject blank names, enforce case-insensitive uniqueness per repository, and permit casing-only renames.
- ✅ List albums by name with empty/nonempty counts; create identically named albums in different repositories without listing/count leakage.
- ✅ Add membership idempotently, return insertion counts, and allow an asset in multiple albums.
- ✅ Ignore unknown/recycled asset IDs when adding to an album.
- ✅ Remove memberships or delete an album while preserving assets and other albums' memberships.
- ✅ Recycling removes all memberships; restoration does not reinstate them; folder recycling and asset-row deletion also remove affected memberships.
- ✅ Album-filtered searches return only matching active assets, including across folders and for an empty album; HTTP mutations verify persisted memberships.
- [ ] Exercise foreign album IDs for read/rename/delete/add/remove and foreign asset IDs in an otherwise local batch; assert isolation of membership rows and return counts. [CRITICAL]
- [ ] Test unknown album IDs for rename/delete/add and define the read/remove contract for a missing album. [MEDIUM]
- [ ] Run concurrent insertion of the same membership and verify idempotency and accurate counts under the unique constraint. [MEDIUM]
- [ ] Verify album operations preserve the complete asset state, binary files, statistics, and people counts, including deleting the last album membership. Current assertions cover only selected asset fields. [MEDIUM]
- [ ] Verify empty ID sets return zero without writes, removing an absent membership is a no-op, and triaged assets can be album members. [EDGE]

## Locations, categories and membership

Sources: [LocationService](../altitude/src/altitude/core/service/LocationService.scala), [Location](../altitude/src/altitude/core/models/Location.scala). Evidence: [LocationServiceTests](../altitude/test/src/altitude/core/integration/LocationServiceTests.scala), [LocationControllerTests](../altitude/test/src/altitude/core/controller/LocationControllerTests.scala), [LocationActionControllerTests](../altitude/test/src/altitude/core/controller/LocationActionControllerTests.scala).

- ✅ Trim names, reject blank names for both kinds, enforce one case-insensitive name pool per repository across categories and Locations on add and rename, and permit casing-only renames.
- ✅ Reject a pin out of range (including NaN); store the edges of the range; the model refuses a pinless Location, a pinned category and a nested category.
- ✅ Add a Location under a category only: a Location as the category is an `IllegalOperationException`, an unknown category is `NotFoundException`; `getAll` fills `categoryName`.
- ✅ Move a Location between categories and back to the top level; refuse moving a category, moving under a Location or under itself; unknown IDs on either side are `NotFoundException`.
- ✅ Delete a category: its Locations move to the top level and keep their memberships. Delete a Location: memberships go, assets stay, other Locations keep theirs; a repeated delete is `NotFoundException`.
- ✅ Add membership idempotently with insertion counts, an empty set is a no-op, unknown and recycled assets are dropped, a category refuses assets, an unknown Location is `NotFoundException`.
- ✅ Remove memberships (an absent membership is a no-op); recycling removes all memberships and restoring does not reinstate them; folder deletion and asset-row deletion also remove affected memberships.
- ✅ `getAll` path order (category before its Locations regardless of name), counts, and category names.
- ✅ Repository isolation: same names in another repository, no listing or count leakage, and every read and mutation by a foreign Location or category ID is `NotFoundException` and changes nothing; a foreign asset in a local batch is dropped.
- ✅ HTTP: camelCase list shape/path order/counts, persisted add/remove membership counts, all dialogs and mutation routes, duplicate/decimal/category validation with form replacement (a missing pin is one `PIN_REQUIRED` error with the name kept), the Add dialog's hidden coordinate inputs, readout and map host, the dialog membership's `App-Success-Detail` added count (zero on a repeat), authentication, invalid hidden IDs and foreign Location IDs.
- [ ] Verify a failure after `moveChildrenToRoot` inside a category delete rolls the re-categorying back. [MEDIUM]

## Moving and recycling library assets

Sources: [LibraryService](../altitude/src/altitude/core/service/LibraryService.scala). Evidence: [LibraryServiceTests](../altitude/test/src/altitude/core/integration/LibraryServiceTests.scala), [LibraryServiceRecycleTests](../altitude/test/src/altitude/core/integration/LibraryServiceRecycleTests.scala), [StatsServiceTests](../altitude/test/src/altitude/core/integration/StatsServiceTests.scala), [PersonServiceTests](../altitude/test/src/altitude/core/integration/PersonServiceTests.scala), [AssetControllerTests](../altitude/test/src/altitude/core/controller/AssetControllerTests.scala).

- ✅ Move an asset between folders and verify source/destination query results; HTTP tests also verify a two-asset move.
- ✅ Move an asset from triage into a folder, update folder/count state, and leave existing person face counts unchanged.
- ✅ Recycle several assets while preserving unselected assets; repeating a batch consisting entirely of already recycled assets leaves counts unchanged.
- ✅ Recycle sorted and triaged assets together, and recycle folder descendants; adjust global statistics and person face counts while retaining face records until purge.
- [ ] Recycle a batch mixing eligible, already recycled, and unknown IDs; assert statistics and person counts change only for eligible rows. The implementation uses the original ID set for some accounting. [MEDIUM]
- [ ] Move a batch mixing sorted, triaged, and recycled assets; verify each flag, destination, byte/count transition, and restored person count. In particular, face restoration currently depends on every selected asset being recycled. [MEDIUM]
- [ ] Move a recycled asset originally from triage into a folder and assert recycled statistics decrease while sorted statistics increase. Both original flags can be true. [MEDIUM]
- [ ] Reject null, nonexistent, or otherwise invalid destination folders without altering assets or statistics; cover an invalid ID mixed with valid asset IDs. [MEDIUM]
- [ ] Move assets to their current folder repeatedly and verify no count or face drift; cover an empty selection. [EDGE]
- [ ] Verify a failure during recycling rolls back asset flags, album removals, statistics, and person counts together. [CRITICAL]

## Restoring recycled assets

Sources: [LibraryService](../altitude/src/altitude/core/service/LibraryService.scala). Evidence: [LibraryServiceRestoreTests](../altitude/test/src/altitude/core/integration/LibraryServiceRestoreTests.scala), [PersonServiceTests](../altitude/test/src/altitude/core/integration/PersonServiceTests.scala), [AlbumServiceTests](../altitude/test/src/altitude/core/integration/AlbumServiceTests.scala).

- ✅ Restore a recycled asset directly or by moving it to a folder.
- ✅ Reject direct restoration when an active duplicate was imported after recycling.
- ✅ Restore the recycled destination folder and its full ancestor chain.
- ✅ Restore triage-origin assets to triage with no folder; restore sorted assets to sorted state and update the appropriate asset-count statistics.
- ✅ Restore person face counts and leave former album memberships absent.
- [ ] Test the duplicate-content safeguard on restoration by move as well as direct restore; verify assets/counters remain unchanged on conflict. [MEDIUM]
- [ ] Define/test repeated restoration and attempts to restore an already active or unknown ID. The checksum check precedes the recycled-state guard. [MEDIUM]
- [ ] Restore multiple assets where one conflicts or fails; assert the intended partial-success/atomicity contract and counter consistency. Each asset currently has its own transaction. [MEDIUM]
- [ ] Race restoration against queued purge, and verify an asset marked for permanent deletion cannot become visible again while its files/row are removed. [CRITICAL]
- [ ] Cover restoration into an ancestor path whose name now conflicts with a live folder, including rollback of already restored ancestors. [MEDIUM]

## Permanent deletion and purge queue

Sources: [LibraryService](../altitude/src/altitude/core/service/LibraryService.scala), [PurgePipelineService](../altitude/src/altitude/core/service/PurgePipelineService.scala), [purge flows](../altitude/src/altitude/core/pipeline/flows/). Evidence: [PurgePipelineServiceTests](../altitude/test/src/altitude/core/integration/PurgePipelineServiceTests.scala), [StatsServiceTests](../altitude/test/src/altitude/core/integration/StatsServiceTests.scala), [FolderServiceTests](../altitude/test/src/altitude/core/integration/FolderServiceTests.scala).

- ✅ A finite purge stream removes asset rows, original files, and previews; removes non-cover face records and their binary variants.
- ✅ Preserve cover-face files after the face row is deleted, and keep other assets' face files accessible in the exercised scenario.
- ✅ Repeating a finite purge stream completes without throwing (smoke coverage only).
- ✅ `purgeRecycleBin` synchronously zeros recycled statistics; unselected active assets remain readable in its existing test.
- ✅ Calling `purgeSelectedAssets` for one recycled asset leaves folder counts unchanged; this does not assert eventual deletion or recycle-bin accounting.
- [ ] Await actual completion of `purgeRecycleBin` and `purgeSelectedAssets`, then assert selected rows/files/faces are gone and every unselected asset survives. Most deletion assertions bypass the production queue via `run`. [CRITICAL]
- [ ] Select only part of the recycle bin and verify exact remaining asset/byte statistics; include active, unknown, empty, and already queued IDs. [MEDIUM]
- [ ] Repeat a purge request before the first queued purge finishes and verify no double decrement, negative counts, or duplicate destructive side effects. [MEDIUM]
- [ ] Inject file deletion failure and database deletion failure at each stage; verify retained state supports the documented retry/recovery policy instead of reporting success with orphaned files or missing originals. [CRITICAL]
- [ ] Reject or otherwise safely handle non-recycled assets submitted to the purge service boundary; explicitly test the prerequisite contract for direct `run`/queue callers. [CRITICAL]
- [ ] Purge assets containing hidden/bad-match people and people with multiple faces per asset; verify non-cover files are removed and cover files survive even when normal people queries filter those people out. [MEDIUM]
- [ ] Interleave repositories in the purge queue and verify only the supplied context's files/rows are affected. [CRITICAL]
- [ ] Test queue closure/failure, pending work at shutdown, and recovery of marked-but-not-deleted assets after an interrupted process. [CRITICAL]
- [ ] Force a recycle-stat mismatch during purge marking and assert the transaction leaves flags/statistics unchanged on failure. [MEDIUM]

## Pruning unfinished imports and iterating repositories

Sources: [LibraryService](../altitude/src/altitude/core/service/LibraryService.scala), [AssetService](../altitude/src/altitude/core/service/AssetService.scala). Evidence: [LibraryServicePruneTests](../altitude/test/src/altitude/core/integration/LibraryServicePruneTests.scala).

- ✅ Mark imported assets unfinished, prune them, and assert their rows and discoverable search results disappear.
- [ ] Prune multiple populated repositories and assert completed assets survive in every repository. The current fixture only verifies unfinished assets in one repository. [CRITICAL]
- [ ] Assert `forEachRepository` visits every repository once and restores the caller's repository, including an initially empty context and a callback that throws. The existing test manually resets context afterward. [MEDIUM]
- [ ] Define/test cleanup of original/preview/face files and people counts left by partially completed imports; the existing pruning test checks rows and search visibility only. [MEDIUM]
- [ ] Race pruning against an in-flight import and verify the lifecycle boundary prevents removal of work that is still progressing. [CRITICAL]

## Global statistics

Sources: [StatsService](../altitude/src/altitude/core/service/StatsService.scala). Evidence: [StatsServiceTests](../altitude/test/src/altitude/core/integration/StatsServiceTests.scala), [LibraryServiceRecycleTests](../altitude/test/src/altitude/core/integration/LibraryServiceRecycleTests.scala), [LibraryServiceRestoreTests](../altitude/test/src/altitude/core/integration/LibraryServiceRestoreTests.scala).

- ✅ Assemble totals from sorted, triaged, and recycled dimensions and verify initial byte totals.
- ✅ Update counts/bytes for sorted-asset recycling and folder recycling; prevent changes on an entirely repeated recycle selection.
- ✅ Update asset counts for triage-to-folder moves and direct restoration to triage/sorted state; zero recycled counts/bytes when marking the full bin for purge.
- ✅ Read zero total assets/bytes from a new repository while the previous repository has data.
- [ ] Re-read and assert byte counters after every move/restore using assets of different sizes. `Test totals` checks several post-move bytes against the old `stats` snapshot, not `stats2`. [MEDIUM]
- [ ] Verify all six dimensions in both populated repositories after mutations; one triage assertion in the second-repository block also reads `stats2` rather than `stats3`. [MEDIUM]
- [ ] Directly exercise public `moveAsset` and `recycleAsset` branches, including recycled-triage assets and ordinary moves. Library bulk operations perform their own accounting and do not test these methods. [MEDIUM]
- [ ] Verify rollback and concurrent increments/decrements preserve totals with no lost updates. [MEDIUM]
- [ ] Cover byte totals above `Int.MaxValue`, zero-sized assets, missing statistics dimensions, and invalid decrements according to the chosen contract. [EDGE]

## Metadata field definitions and value editing

Sources: [UserMetadataService](../altitude/src/altitude/core/service/UserMetadataService.scala). Evidence: [UserMetadataServiceTests](../altitude/test/src/altitude/core/integration/UserMetadataServiceTests.scala), [SearchServiceTests](../altitude/test/src/altitude/core/integration/SearchServiceTests.scala).

- ✅ Add/retrieve/delete a metadata field; reject an exact duplicate field name.
- ✅ Fields are shared by users in the same repository; reject supplied metadata field IDs that are not configured there.
- ✅ Store keyword/number fields, omit empty value sets, merge partial updates, and delete fields whose supplied value set becomes empty.
- ✅ Reject nonnumeric input, invalid Boolean strings, and multiple Boolean values; accept the exercised true/false casing variants.
- ✅ Reject blank keyword/text additions and blank updates; replace Boolean values without accumulating multiple stored values.
- ✅ Generate value IDs, preserve existing IDs when adding another field, and update values by ID including casing-only and unchanged updates.
- ✅ Delete individual values; verify normal keyword/number edits feed search indexing.
- [ ] Verify case-insensitive field-name collisions and independent same-name fields in two repositories. The current all-fields test switches users, not repositories. [MEDIUM]
- [ ] Verify deleting a field already used by assets cleans or safely handles stored metadata and search entries; call `toJson` afterward. The deletion fixture uses an unused field. [MEDIUM]
- [ ] Assert keyword whitespace compaction, text newline preservation, trimming, and duplicates that become equal after cleaning; reject duplicate additions and edits to another existing value. [MEDIUM]
- [ ] Define/test missing asset IDs, missing value IDs, and a value ID belonging to a different asset for update/delete; assert unrelated data survives. [MEDIUM]
- [ ] Assert exact stored values after set/update, including preservation of omitted fields. Some current removal assertions compare `Set[UserMetadataValue]` with raw strings and can succeed without establishing value removal. [MEDIUM]
- [ ] Exercise `0`/`1` Booleans, the numeric examples that are assigned but never submitted in `Number field type can be added`, non-finite numbers, and numeric overflow. [EDGE]
- [ ] Define and test valid/invalid `DATETIME` values; `collectInvalidTypeValues` currently accepts every datetime string. [MEDIUM]
- [ ] Inject an index-write failure during a value edit and assert metadata/index changes roll back together. [CRITICAL]

## Metadata JSON and search-index consistency

Sources: [UserMetadataService](../altitude/src/altitude/core/service/UserMetadataService.scala), [SearchService](../altitude/src/altitude/core/service/SearchService.scala). Evidence: [SearchServiceTests](../altitude/test/src/altitude/core/integration/SearchServiceTests.scala).

- ✅ Updating a keyword value makes the new value searchable; deleting a value removes the exercised text match.
- ✅ Keyword/number additions participate in combined metadata filters.
- [ ] Verify `toJson` includes configured empty fields, sorts names case-insensitively, preserves value IDs, omits internal timestamps/lowercase names, and honors a supplied field lookup. No direct assertions cover this transformer. [MINOR]
- [ ] After Boolean replacement, require the new Boolean filter to match and the old one not to match; current tests only count stored values. [MEDIUM]
- [ ] After keyword/number update or deletion, verify both the old text term and old faceted value disappear and unrelated terms remain. [MEDIUM]
- [ ] Add/edit a TEXT value through the public mutation API and verify the intended full-text behavior while excluding it from faceted indexing. The service skips TEXT in `addMetadataValue(s)`. [MEDIUM]
- [ ] Replace or bulk-update metadata on an already indexed asset and assert search results reflect the new values; define/test who owns reindexing for `setMetadata`/`updateMetadata`. [MEDIUM]
- [ ] Verify repeated reindexing does not duplicate rows/counts, and indexing an unpersisted asset fails without creating index data. [EDGE]

## Ordinary search and folder scope

Sources: [SearchService](../altitude/src/altitude/core/service/SearchService.scala), [LibraryService](../altitude/src/altitude/core/service/LibraryService.scala). Evidence: [SearchServiceTests](../altitude/test/src/altitude/core/integration/SearchServiceTests.scala), [LibraryServiceTests](../altitude/test/src/altitude/core/integration/LibraryServiceTests.scala), [AlbumServiceTests](../altitude/test/src/altitude/core/integration/AlbumServiceTests.scala), [SearchGroupingTests](../altitude/test/src/altitude/core/integration/SearchGroupingTests.scala), [SearchSqlTests](../altitude/test/src/altitude/core/unit/SearchSqlTests.scala), [SearchQueryModelTests](../altitude/test/src/altitude/core/unit/SearchQueryModelTests.scala).

- ✅ Search keyword terms case-insensitively; apply keyword, number, and Boolean filters together; return no match for the exercised wrong-type filter.
- ✅ Filter by Location (`locationIds`) on the flat search, a grouped search and the bare count; recycling drops the asset from the Location's results and counts.
- ✅ Filter by bounding box: an asset's own point, its Locations' pins when it has none (never the pin when it has a point), a box across the antimeridian versus the same edges the other way round, the world box; `BoundingBox.parse` arity, ranges and NaN.
- ✅ Unit tests check the Location and bounding-box filters are bound semi-joins, and that `count` renders one `COUNT` over the matching relation with no ordering or page.
- ✅ Include descendant folders, treat root search as unrestricted folder scope including triage, filter by one/multiple people, and filter by album.
- ✅ Paginate results with totals and page counts; handle an oversized page and a page beyond the end.
- ✅ Return sort metadata and hide unfinished imports from search.
- ✅ Unit tests check generated SQL for text, metadata, folder, and sort predicates on the engine-specific builders.
- [ ] Assert ordinary ascending/descending result order. Both existing creation-date tests compute `.forall(...)` and discard the Boolean; their comparisons also point opposite to the test names. [MEDIUM]
- [ ] Verify ordering by filename, size, area, and capture time with unequal values and ties; assert page traversal contains no unexpected duplicates/omissions for a stable dataset. [MEDIUM]
- [ ] Fill in `Purging assets should remove them from the search index`, which currently contains only `TODO`; assert both search invisibility and removal of the relevant index records. [MEDIUM]
- [ ] Verify multiple-folder input is rejected by both library search paths; define/test nonexistent and recycled folder scopes. [MEDIUM]
- [ ] Test punctuation, quotes, Unicode, and SQL-like text as literal user queries on both engines, including combinations with metadata filters. [MEDIUM]
- [ ] Cover multiple album IDs and combined album/person/folder filters with overlapping membership, asserting deduplication and totals. [MEDIUM]

## Grouped search and cursor continuation

Sources: [SearchService](../altitude/src/altitude/core/service/SearchService.scala), [LibraryService](../altitude/src/altitude/core/service/LibraryService.scala). Evidence: [SearchGroupingTests](../altitude/test/src/altitude/core/integration/SearchGroupingTests.scala), [SearchCursorTests](../altitude/test/src/altitude/core/integration/SearchCursorTests.scala), [SearchResultsControllerTests](../altitude/test/src/altitude/core/controller/SearchResultsControllerTests.scala).

- ✅ Group by capture day with full matching day counts; split large days across pages and report continuation without repeating headers in HTTP output.
- ✅ Include the No date group, obey each engine's native null placement, and continue through null groups and SQLite legacy null import timestamps.
- ✅ Traverse both grouping directions and all supported secondary sort fields/directions, with ties and one-item pages; round-trip cursor encoding.
- ✅ End the cursor on the final page, including an exactly full final page; return zero totals for an empty first page and omit overall totals on continuation.
- ✅ Continue after the anchor leaves results or a new asset is inserted before it without skipping/repeating the tested remaining images.
- ✅ Reject malformed/unsupported-version cursors and changes to text, folder scope, grouping direction, sort field, or sort direction; allow a changed page size.
- ✅ Apply text/metadata/folder/album/person filters to group counts and rows; verify root scope, recycle view, repository isolation, timezone independence, and one statement per unscoped grouped page.
- ✅ HTTP tests reject invalid grouped parameters and unsupported JSON negotiation; unauthenticated HTML/API-style requests receive redirect/401, and empty continuations return 204.
- ✅ Group by Location: path order (a category's Locations at the category's name, by their own), an asset under each of its Locations, group counts against a total that counts assets, `Category › Location` data on the keys, the trailing "No location" group, the fixed direction, the sort within a group, an empty first page, one statement per page.
- ✅ Location groups span pages with one count, continue into and inside "No location", and cross from the last Location into it; every filter bounds the groups and their counts, and a `locationIds` scope leaves no trailing group.
- ✅ Location cursor traversal matches the complete order for every sort field and direction at page sizes 1, 5 and 6, including overlapping memberships and a Location exactly a page long; a first page counts assets once; deleting the anchor's Location continues into the trailing group its members joined.
- ✅ A cursor is rejected for a changed `locationIds` or `bbox`, from a day grouping against a Location grouping, and for a version-3 token.
- ✅ HTTP: the Group dropdown selects the Location option and the fragment carries `data-results-group-by` / `data-results-group-direction` for each grouping (no direction for Location); a Location group's cells are `asset-<id>-in-<locationId>` under a header whose category is a `.category` span, while "No location" keeps plain cell IDs; a first page renders `result-group` headers and a continuation of the same group renders none; a Location page whose last asset also has a cell under an earlier Location puts the cursor and `last-cell` on its last cell only.
- ✅ Unit tests pin the Location statement's shape on both dialects: the located/unlocated slices and the guard between them, the joins, the located-first page order, no `NULLS FIRST/LAST`, every value bound, the same filters in every branch, the path-key cursor comparison, and the ORDER BY with SQLite's planner hint.
- [ ] Reject reuse of a cursor across repositories or database engines, or after changing album/person/metadata/view filters. Existing scope tests do not cover every fingerprint component. [CRITICAL]
- [ ] Continue a folder-scoped cursor after adding, moving, or recycling descendants; assert the fingerprint stays tied to the requested folder and current descendants are resolved afresh. [MEDIUM]
- [ ] Traverse size/area sorts using different numeric values. Current cursor fixtures give equal size/area values, so those cases primarily exercise the ID tiebreaker. [MEDIUM]
- [ ] Check remaining-day counts after inserts/deletes and test mutations to an anchor's sort/day value; define the expected live-result semantics explicitly. [MEDIUM]
- [ ] Reject malformed cursor field types, invalid dates, oversized tokens, and mismatched sort-value types with a domain error rather than leaking an internal exception. [EDGE]
- [ ] Issue an authenticated request for another user's repository and assert the intended access rule. The test named `Grouped requests require authentication and a repository the user can see` only sends unauthenticated requests. [CRITICAL]

## Map cells, bounds and the geocoder

Sources: [SearchQueries](../altitude/src/altitude/core/dao/sql/search/SearchQueries.scala), [SearchService](../altitude/src/altitude/core/service/SearchService.scala), [GeocoderService](../altitude/src/altitude/core/service/GeocoderService.scala). Evidence: [SearchMapTests](../altitude/test/src/altitude/core/integration/SearchMapTests.scala), [SearchSqlTests](../altitude/test/src/altitude/core/unit/SearchSqlTests.scala), [GeocoderServiceTests](../altitude/test/src/altitude/core/unit/GeocoderServiceTests.scala), [MapControllerTests](../altitude/test/src/altitude/core/controller/MapControllerTests.scala), [SearchResultsControllerTests](../altitude/test/src/altitude/core/controller/SearchResultsControllerTests.scala).

- ✅ Cells aggregate the plotted points on both engines: an asset at its own point, an asset without one at the pin of each Location it is in (counted once per Location), assets at one coordinate merged into one cell with the mean centroid, a Location listed with its matching count and its category's name, and absent without matching assets.
- ✅ The representative asset of a cell is the newest capture, then the lowest ID, and follows the matching set when the newest is recycled.
- ✅ Cell size follows the zoom (one cell at zoom 0, two at zoom 10 for points a degree apart) and the zoom is clamped to 0..20.
- ✅ Cells and Locations are clipped to the viewport, including a box across the antimeridian.
- ✅ Bounds cover both point sources, count plotted points, follow the search's filters, and are absent when nothing is plotted.
- ✅ The map reads the same matching set as the grid: recycled assets only in the trash view, a folder scope narrows cells, Location counts and bounds, the root folder is the whole repository, another repository's geotagged asset is invisible.
- ✅ The cells and bounds statements render on both dialects with every placeholder bound and both point sources carrying the search's filters; the Locations query is repository- and kind-scoped, antimeridian-aware, and counts over the matching relation.
- ✅ Geocoder: disabled by config refuses with `IllegalOperationException` and sends nothing; enabled, against a local stub, it sends `format=json`, `limit=5`, the URL-encoded query and an identifying `User-Agent`, maps the places, skips one without coordinates, asks nothing for blank text, and turns a non-200 answer or a non-list body into `GeocoderException`.
- ✅ HTTP: cells/bounds JSON shapes, plotted-point counts, Location/text/bbox/trash scope, Location membership counts preserved across pans, ignored toolbar sort, empty bounds, invalid bbox/zoom JSON 400s, disabled-geocoder JSON 404, and authentication. HTML search covers Location grouping/cursor continuation, Location/bbox filters, a map shell without `#assets`, disabled Group, totals and replacement URL scope; the pressed layout toggle button and `data-results-layout` in each layout, the hidden `#mapPanel` in map layout, and the `#bboxScope` chip present with a `bbox` and absent without one.
- [ ] Enabled geocoder success and upstream failure are covered at the service level against a local stub; HTTP serialization and the 502 mapping still need an enabled-config controller fixture. [MINOR]
- ✅ A cells query reads the geotagged assets through the partial `asset_geo` index on both engines (`EXPLAIN QUERY PLAN` on SQLite; `EXPLAIN` on Postgres over a few thousand analyzed rows, where its costing no longer ties every index).
- [ ] Bounds for a result whose points straddle the antimeridian could be the narrower box across it rather than the whole longitude range. [EDGE]

## People, face ownership, and merges

Sources: [PersonService](../altitude/src/altitude/core/service/PersonService.scala). Evidence: [PersonServiceTests](../altitude/test/src/altitude/core/integration/PersonServiceTests.scala), [PurgePipelineServiceTests](../altitude/test/src/altitude/core/integration/PurgePipelineServiceTests.scala).

- ✅ Persist faces/people from a real import, associate faces with assets, increment face counts, and assign the first cover face.
- ✅ Rename a person, mark it named, and update the persisted sort name; cross the minimum face-count threshold.
- ✅ Merge people and merge chains, transfer face ownership, zero source counts, preserve/inherit the tested names, and allow reuse of a merged source's name.
- ✅ Recount a merge when two people share one asset; separately recognize multiple occurrences of one person in a single image.
- ✅ Hide a person from above-threshold and per-asset retrieval; recycle/restore assets without deleting/restoring the wrong face-record set in the tested normal cases.
- ✅ Preserve explicitly selected cover-face binaries through purge.
- [ ] Reject self-merge, missing/deleted participants, and foreign-repository participants without changing faces or either person. [CRITICAL]
- [ ] Inject a failure midway through merge and assert source status, target name/count, and face ownership roll back together. [CRITICAL]
- [ ] Merge a named source into an unnamed target and assert `isNamed`, sort name, cover selection, and list placement as well as the inherited display name. [MEDIUM]
- [ ] Verify source deletion/exclusion explicitly after merge, including attempts to merge the same source again. [MEDIUM]
- [ ] Cover `markAsBadMatch`, unhide, and every bulk-list variant with named/unnamed, hidden, deleted, bad-match, zero-face, and threshold-boundary people. [MEDIUM]
- [ ] Verify `getPersonFaces` detection-score ordering and limits with more than a default query page; the current fixtures remain below the normal 50-row limit. [MEDIUM]
- [ ] Recycle/restore an asset containing multiple faces for a person after a merge; assert the intended distinct-asset versus raw-face count contract remains consistent. [MEDIUM]
- [ ] Reject assigning a cover face belonging to another person/repository or a nonexistent face; preserve the previous cover. [MEDIUM]
- [ ] Test unsaved-person/asset guards in `addFace` and the multi-face guard in `addPerson`; assert failed operations create no rows or count changes. [EDGE]

## Face detection and embedding generation

Sources: [FaceDetectionService](../altitude/src/altitude/core/service/FaceDetectionService.scala). Evidence: [FaceDetectionTests](../altitude/test/src/altitude/core/integration/FaceDetectionTests.scala), [FaceRecognitionServiceTests](../altitude/test/src/altitude/core/integration/FaceRecognitionServiceTests.scala), [PersonServiceTests](../altitude/test/src/altitude/core/integration/PersonServiceTests.scala).

- ✅ Detect one face in the supplied small-face and large-portrait fixtures and two faces in the exercised two-person image.
- ✅ Produce nonempty detected, display, aligned-color, and aligned-grayscale image buffers for a recognized face.
- [ ] Assert zero detections for a no-face image, empty image, and image smaller than the configured minimum; define corrupt-byte behavior. [MEDIUM]
- [ ] Verify minimum-size/confidence boundaries, downscaled bounding boxes/landmarks mapped back to the original image, and preservation of confidence scores during scaling. [MEDIUM]
- [ ] Test clamping for negative/out-of-bounds/zero-size face boxes and confirm crops stay inside the image. [MEDIUM]
- [ ] Verify landmark order and a known similarity transform, reject insufficient/degenerate landmarks, and assert aligned output is 112×112. [MEDIUM]
- [ ] Assert each embedding has 512 finite values and unit L2 norm for a normal fixture; cover the zero-norm branch and distinguish aligned color from grayscale output. [MEDIUM]
- [ ] Process images concurrently and verify the thread-local detector/network instances do not corrupt results. [MEDIUM]
- [ ] Exercise debug-output enablement and missing model/resource failures, with temporary output paths and native resources cleaned up. [EDGE]

## Face recognition and nearest-match selection

Sources: [FaceRecognitionService](../altitude/src/altitude/core/service/FaceRecognitionService.scala). Evidence: [FaceRecognitionServiceTests](../altitude/test/src/altitude/core/integration/FaceRecognitionServiceTests.scala), [PersonServiceTests](../altitude/test/src/altitude/core/integration/PersonServiceTests.scala).

- ✅ Two photographs of the same person resolve to the same person ID and a persisted face count of two.
- ✅ Importing the exercised two-person image creates two people; the multi-occurrence fixture resolves to one person with two faces.
- [ ] Construct controlled nearest-neighbor candidates and assert majority selection, tie behavior, fewer-than-configured matches, and no-match behavior. The comment promises a clear majority, while the implementation selects the largest vote group even on a tie. [MEDIUM]
- [ ] Test distance-threshold boundaries and configured match counts on both database engines with known vectors. [MEDIUM]
- [ ] Verify matching never selects a face from another repository and define/test eligibility of recycled, merged, hidden, and bad-match people. [CRITICAL]
- [ ] Reject already persisted or already associated face objects without adding a new person/face. [EDGE]
- [ ] Fail persistence of a later face or its binary variants and verify the transaction/recovery contract for all faces and people created by that asset. [CRITICAL]

## File-system storage

Sources: [FileStoreService](../altitude/src/altitude/core/service/filestore/FileStoreService.scala), [FileSystemStoreService](../altitude/src/altitude/core/service/filestore/FileSystemStoreService.scala). Evidence: [FileStoreServiceTests](../altitude/test/src/altitude/core/integration/FileStoreServiceTests.scala), [PurgePipelineServiceTests](../altitude/test/src/altitude/core/integration/PurgePipelineServiceTests.scala), [ContentViewControllerTests](../altitude/test/src/altitude/core/controller/ContentViewControllerTests.scala).

- ✅ Imported originals/previews can be read as nonempty bytes; asset purge makes both reads fail with `NotFoundException`.
- ✅ Purge tests exercise retrieval/removal of all four face-image variants and preservation of cover-face variants.
- ✅ HTTP content routes return the exercised preview, original, and face MIME types (indirect coverage).
- [ ] Assert byte-for-byte original/preview/face round-trips and matching IDs; nonempty buffers cannot detect swapped or truncated content. [MEDIUM]
- [ ] Use identical object IDs under two repository contexts and verify reads/deletes cannot cross repository directories. [CRITICAL]
- [ ] Test IDs containing traversal/absolute-path syntax and verify no read/write/delete escapes the intended storage root. [CRITICAL]
- [ ] Force original/preview/face write and read failures and assert `StorageException`, preserving any prior valid content under the chosen overwrite policy. [CRITICAL]
- [ ] Exercise deletion returning `false` as well as throwing; verify purge failure is observable and does not silently strand files. [MEDIUM]
- [ ] Test partial failure while writing four face variants, including retry and removal of partial artifacts. [MEDIUM]
- [ ] Cover unknown well-formed IDs, IDs shorter than two characters, repeated deletion, and empty data according to the chosen storage contract. [EDGE]

## Browser URL generation

Sources: [UrlService](../altitude/src/altitude/core/service/UrlService.scala). Evidence: [UrlServiceTests](../altitude/test/src/altitude/core/unit/UrlServiceTests.scala).

- ✅ Preserve query-parameter order and an existing fragment; omit fragments for a URL without one or a null URL; encode spaces and ampersands in values.
- [ ] Verify an empty trailing `#` produces no fragment, including an empty query-parameter sequence. [EDGE]
- [ ] Test Unicode/reserved characters, repeated parameter keys, and a fragment containing additional `#` characters. [EDGE]
- [ ] Verify the repository path comes from the current request context rather than a different repository embedded in `browserUrl`; isolate the fixture's request context explicitly. [MINOR]
