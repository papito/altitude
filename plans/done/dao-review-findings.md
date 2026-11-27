# DAO layer: correctness under concurrency, at scale and across engines, by priority

## Scope

Everything under `altitude/src/altitude/core/dao/` (the JDBC base, the engine overrides, the typed tables, the search
queries and dialects), and what it rests on: both `all.sql` schemas, `TransactionManager`, `Db`, `MigrationService`.
Service callers are in scope where a DAO statement's correctness depends on them: the lock a caller takes first, the row
count it checks, the values it can pass. Paths are relative to `altitude/src/altitude/core/` unless they start with
`altitude/` or `docs/`.

Each item says what the code does, where, and the test that covers it. A test runs on both engines unless it is marked
PostgreSQL; a PostgreSQL test guards a behaviour that SQLite's one write connection serializes away.

Priorities:

- **P1**: the folder tree or People, assets hidden from a result, an operation that would fail for good, an import in
  ordinary use.
- **P2**: a path that needs a race, a rare input or an Admin's mistake; a security or data-safety gap of low likelihood;
  a cost every statement pays.
- **P3**: latent paths, cosmetics, and text that must agree with the code.

No statement reads or writes another Library's rows, and no SQL is built from request values. Every lock is taken in the
one order of **Lock order** in `altitude/AGENTS.md` (**DAO / Service Layer**): turns (advisory locks), then accounts,
assets, people and their Faces, then the rows that hang off those, the rows of each kind in ID order. A deadlock
(`40P01`) is a bug in the order and is never retried; `TransactionManager` has no retry.

## P1

### 1. The folder tree stays acyclic

`FolderService.move` (`service/FolderService.scala:268-299`) takes `dao.lockTree()` (`dao/jdbc/FolderDao.scala:130`, an
advisory lock per Library) first in its transaction (`:277`), before it reads, then checks the destination's ancestors
(`:286-287`), so of two opposite moves the second sees what the first committed and is refused with
`IllegalOperationException`. `add`, `rename`, `LibraryService.deleteFolderById`, `restoreRecycledAssets` and
`moveAssetsToFolder` take the same turn. The recursive reads `pathFrom` (`getAncestors`, `getPath`, `:86-112`) and
`getChildrenRecursive` (`:133-160`) end on a cycle already stored, through `FolderDao.Acyclic` (`:165-192`): PostgreSQL's
`CYCLE id SET is_cycle USING visited` with `NOT is_cycle` outside, and on SQLite a `,id,` visited text checked with
`instr` in the recursive arm.

Tests: `FolderServiceTests` "Two opposite moves of sibling folders at once leave no cycle: the second is refused"
(`contend`) and "The recursive folder reads end on a cycle stored in the tree".

### 2. An excluded Search word that names a folder keeps the assets in no folder

The text sources test folder membership through `SearchQueries.inFolders` (`dao/sql/search/SearchQueries.scala:1395-1397`,
used at `:1271`): `(folder_id IS NOT NULL AND <folderFilter>)`, two-valued for an asset in no folder, so both negating
branches of `textFilter` (`:1295-1318`) negate a boolean. `folderFilter` (`:1388-1389`) binds the folder set as one
parameter.

Test: `SearchServiceTests` "Search names: an excluded folder name keeps the assets in no folder": in the Triage view
`-rome`, `-rome OR nothing` and `three -rome`, with the count, the grouped page and the map in agreement, and the
repository view as an edge case.

### 3. An ID set binds as one parameter, whatever its size

`DynamicFilter.predicate` (`dao/sql/DynamicFilter.scala:38-44`) sends a `Query.IN` whose values are all strings through
`Columns.isInSet` (`dao/sql/Columns.scala:71-81`): `= ANY(?)` with a `varchar[]` on PostgreSQL, `IN (SELECT value FROM
json_each(?))` on SQLite. A `Query.IN` of other values stays on `Columns.isIn` (`:62-64`), one parameter per value, for
small sets. So `BaseDao.getByIds` (`dao/jdbc/BaseDao.scala:475-481`), every `updateByQuery` and `queryAll` by IDs,
`LibraryService.markForPurging`, `recycleAssets`, `moveAssetsToFolder`, `requireAssets` and `PersonService.moveCoversOff`
take a set of any size. `WatchFolderDao.targeting` (`dao/jdbc/WatchFolderDao.scala:110-121`) binds `dest_folder_id
$inIdSet` with `idSet(folderIds)`. `deleteFolderById` (`service/LibraryService.scala:260-276`) reads asset IDs alone
(`AssetDao.getIdsInFolders`, `dao/jdbc/AssetDao.scala:293-305`); `requeuePurgePending` reads its rows without the bulky
metadata (`AssetDao.getPurgePending`, `:308-316`, `$assetWithoutBulkyMetadata`), since they go to the purge queue.

Tests: `DynamicFilterTests` (unit) "An IN parameter of strings binds its set as one value" and "An IN parameter of other
values binds every value"; `Slow`: `BaseDaoTests` "A read by more IDs than a statement takes parameters returns the
assets among them" (70,000 unknown IDs) and "A write by more IDs than a statement takes parameters changes the rows among
them"; `LibraryServiceRecycleTests` (66,000 seeded copies) "A selection larger than a statement's parameter limit is
recycled and moved back", "A folder holding more assets than a statement takes parameters is deleted" and "A Trash
larger than a statement's parameter limit is emptied"; `WatchFolderServiceTests` "The Watch folders targeting a set of
more folders than a statement takes parameters are found".

### 4. A deleted folder's name is free again

`folder_02` is unique on `(repository_id, parent_id, name_key)` over every row (`postgres/all.sql:176`), and a folder row
is never deleted, so a recycled folder holds its `name_key` until a live folder takes it. `FolderService.add` (`:38-53`)
takes `lockName`, then `getChild`: a recycled namesake is restored in place under the name as given (`restore`,
`:95-102`); a live one is a `DuplicateException`. `rename` (`:307-324`) and `move` call `takeName` (`:136-141`) under
`lockName`, which moves a recycled sibling holding that key aside by setting its `name_key` to `"Recycled <id>"` (no
folded name equals it, since folded keys are lower case); the sibling keeps its `name`, so the Trash shows it under its name.
`restoreRecycled` (`:112-129`), called by `LibraryService.restoreRecycledAsset` (`:350-361`) for each ancestor and the
asset's folder, re-reads the folder under its name lock and takes its name back when no live sibling holds it (moving a
recycled holder aside in turn); otherwise it comes back as the first free "name (2)", "name (3)" (`freeNumberedName`,
`:147-158`, locking each candidate key as it reads it; `numbered`, `:164-172`, shortens the name by grapheme clusters
until `validName` accepts it). `ensureChild` (imports, `:76-90`) restores a recycled namesake under its own name.

Tests, `FolderServiceTests`: "A folder added under the name of a deleted sibling is that sibling, restored", "A folder
renamed or moved to the name of a deleted sibling takes it, and the sibling keeps its name in the Trash", "A folder that
gave its name away comes back from the Trash under it when free, and numbered when not", "Two restores at once of assets
in a folder that gave its name away number it once" (`contend`), "A folder that gave its name away comes back numbered
even when its name's key is near its limit".

### 5. A merge needs a live destination, and no Face matches a deleted person

`PersonService.mergeLocked` (`service/PersonService.scala:173-175`) locks both people with `lockAndGetLive` and throws
`NotFoundException` when the destination is not live; the Face-move path (`:502-503`) and `mergeFacePerson` (`:590`) do
the same. Both nearest-face statements filter `person.is_deleted = FALSE` beside `is_bad_match = FALSE`
(`dao/postgres/FaceDao.scala:36`, `closestMatchesSql`, behind `CLOSEST_MATCHES_SQL` and `CLOSEST_OF_OTHERS_SQL`;
`dao/sqlite/FaceDao.scala:49`).

Tests: `PersonServiceTests` "A merge into a Person merged away is refused, and leaves no Face on a Person merged away";
`FaceRecognitionServiceTests` "A Face of a Person merged away is not a match candidate".

### 6. Recognition gives a Face only to a person it holds locked and live

`FaceRecognitionService.lockedOwners` (`service/FaceRecognitionService.scala:254-275`), used by `recognizeAndStore`
(`:134-154`) and `recognizeFace` (`:237-243`), searches every detection, runs the vote (`assign`), then locks the winners
at once in ID order (`PersonService.lockLive` → `PersonDao.lockAndGetLive`, `dao/jdbc/PersonDao.scala:235-250`, `ORDER BY
id FOR UPDATE`) under a savepoint (`TransactionManager.undoingIf`, `transactions/TransactionManager.scala:253-260`). A
winner not live once locked (merged away, deleted, discarded, a bad match) rolls the savepoint back: the detections that
matched it are searched again and the vote and locks retaken over the new set. A person deleted meanwhile is absent from
the locked rows, so no Face insert meets a foreign-key failure. `PersonService.addFace` (`:114-134`) runs under its
caller's locks.

Tests: `FaceRecognitionServiceTests` "A Face recognized while its Person is merged away goes to the Person they were
merged into" (`contend`); `PurgePipelineServiceTests` "A Face recognized while a purge deletes its Person goes to a live
Person" (`contend`).

### 7. An import storing several people's Faces takes them in the order every other writer does

`recognizeAndStore` locks every person it gives a Face to in ID order before the first Face insert (item 6), the order of
recycle, restore, correction, merge, discard and purge (`PersonService.lockAssetPeople`, about `:360-378`, which re-reads
under a savepoint and relocks when Faces change hands), with assets before people (**Lock order** rule 3 before 4).

Tests, PostgreSQL (`whileHolding`): `FaceRecognitionServiceTests` "An import storing two people's Faces and a recycle of
an asset both are on both finish" and "A recognition that searches again gives its locks back and takes them again in ID
order"; `LibraryServicePruneTests` "A discard, a rename of the importer and a recycle of an asset sharing a Person with
it at once all commit"; `PurgePipelineServiceTests` "A purge of an asset while a merge gives one of its Faces away
finishes, and so does the merge".

## P2

### 8. Setup is guarded in its transaction

The first statement of `SystemService.initializeSystem` (`service/SystemService.scala:58-89`, guard at `:64`) is
`SystemMetadataDao.setInitialized()` (`dao/jdbc/SystemMetadataDao.scala:37-42`): `UPDATE system SET is_initialized = ?
WHERE id = ? AND is_initialized = ?`, true on exactly one row; on none `initializeSystem` throws
`AlreadyInitializedException` (`Exceptions.scala:27`) and adds nothing. Of two setups at once the second waits on the row
and is refused. The in-memory flag is set in `txManager.afterCommit` (`:84`). `SetupFormController`
(`routes/web/partial/SetupFormController.scala:149`) catches the exception, sets the flag and answers
`alreadyInitialized`. Recorded done in `docs/READINESS.md:82`.

Tests: `SystemServiceTests` "A second setup is refused and adds nothing", "Of two setups at once, one sets the instance up
and the other is refused" (`contend`), "A setup whose transaction fails leaves the instance not set up";
`SetupFormControllerTests` "Setup is refused once the database says the instance is set up, though the server's flag does
not yet".

### 9. The read statement timeout stays within PostgreSQL's range

The Setting is `Integer(1, 2147483)`, PostgreSQL only (`models/Settings.scala:459-467`), and
`TransactionManager.beginPostgresRead` (`transactions/TransactionManager.scala:177-178`) computes
`dbPostgresReadStatementTimeout * 1000L`.

Tests: `SettingsServiceTests` "The read statement timeout is at most 2,147,483 seconds, the most PostgreSQL's limit holds
in milliseconds"; `TransactionManagerTests` "A PostgreSQL read transaction takes the largest read statement timeout the
Setting allows" (PostgreSQL, `SHOW statement_timeout` = `2147483s`).

### 10. A move out of the Trash skips and reports the assets whose content is live

`LibraryService.moveAssetsToFolder` (`service/LibraryService.scala:371-410`) takes `lockTree`, refuses a destination in
the Trash (`DESTINATION_IN_TRASH`), splits the assets into recycled and live, and passes the recycled ones through
`leavingTheTrash` (`:317-326`), the rule `restoreRecycledAssets` (`:284-305`) applies: `liveChecksums` reads the live
contents in one statement, and an asset stays in the Trash when its content is live or an earlier asset of the same
operation (ID order) takes that content out. It returns `RestoreResult(restored, duplicates)`;
`refusingAContentImportedMeanwhile` (`:334-339`) turns a violation of `AssetDao.LIVE_CHECKSUM_INDEX` by a concurrent
import into `IllegalOperationException(CONTENT_IMPORTED_MEANWHILE)`. The PUT route answers `{restored, duplicates}`
(`routes/api/AssetController.scala:27`, `BaseController.restoreResponse`), and `static/js/assets/asset-actions.js`
(`:142-177`) reports the duplicates and leaves them selected.

Tests: `LibraryServiceRestoreTests` "A move out of the trash skips an asset whose content is live again, or comes back in
the same move", "Two moves out of the trash of copies of one content take turns, and the second leaves its copy", "A
restore and a move out of the trash of copies of one content take turns, in either order", "A copy imported while a move
or a restore takes its content out of the trash refuses that operation" (PostgreSQL); `AssetControllerTests` "Moving
assets out of the trash reports the restored ones and the ones whose content is live again".

### 11. A purge deletes the unnamed people it leaves with no Face

The purge's last step, `LibraryService.deletePurgedAsset` (`service/LibraryService.scala:476-483`), locks the asset
(`lockPurged`), then the people of its Faces (`lockAssetPeople`), deletes the asset, and calls
`PersonService.deleteUnnamedPeopleWithoutFaces` (`service/PersonService.scala:324`) on those people:
`deleteFaceless(keepNamed = true)` (`:331-337`), which locks them in ID order and runs `PersonDao.deleteFaceless`
(`dao/jdbc/PersonDao.scala:252-271`, `is_named = FALSE` with `keepNamed`, scoped). A named person keeps their row and
name with no Face and stays assignable (`PersonDao.getAssignable`, `:177-185`). A correction or a discard deletes faceless
people named or not (`deletePeopleWithoutFaces`, `:318`, from `discardImport` and `settleAfterFaceLeft`). The rule is in
the `PersonService` scaladoc (`:315-330`) and `docs/faces.md` (`:197-200`, `:382-384`).

Test: `PurgePipelineServiceTests` "A purge deletes an unnamed Person it leaves with no Face, and keeps a named one with
their name".

### 12. No folder is added or moved under a folder in the Trash

`FolderService.requireLive` (`:175-177`) throws `IllegalOperationException` for a folder in the Trash. After `lockTree`,
`add` refuses a parent in it (`:40-43`, `PARENT_FOLDER_IN_TRASH`), `move` a destination or a moved folder in it
(`:282-284`), `rename` a folder in it (`:312`), and `LibraryService.moveAssetsToFolder` a destination in it
(`:376-378`, `DESTINATION_IN_TRASH`). `deleteFolderById` takes the same turn, so an add or move racing a delete sees the
delete done.

Tests, `FolderServiceTests`: "A folder is neither added nor moved under a folder in the Trash", "A folder added or moved
under a folder deleted at the same time is refused" (`contend`, twice), "A folder in the Trash is neither renamed nor
moved".

### 13. Concurrent adds of one asset to an album or a Location both succeed

`AlbumDao.addAssets` (`dao/jdbc/AlbumDao.scala:60-79`) and `LocationDao.addAssets` (`dao/jdbc/LocationDao.scala:94-113`)
are scoped insert-selects from live assets, the IDs bound as one `$inIdSet`, written in ID order, ending `ON CONFLICT DO
NOTHING` (the `WHERE` ahead of the `ORDER BY` for SQLite); the count is the rows that were new. `AlbumService` and
`LocationService` call `asset.shareLockLive(assetIds)` before the insert, so a racing recycle is waited for (the asset is
then left out) or waits for the add.

Tests, `AlbumServiceTests` and `LocationServiceTests`: "Two adds of one asset to an album|a Location at once both
succeed, the second counting only what it added" (`contend`), "Two adds of the same assets … at once, read in opposite
orders, both succeed" (PostgreSQL), "An add racing a recycle of the asset leaves it out of the album|the Location".

### 14. Sequences, accounts, Edits and Watch folders follow the one lock order

- **Sequence writers against library operations.** `SequenceService.assign` (`service/SequenceService.scala:36-44`)
  takes `lockSequences`, reads the candidates unlocked (`dao/jdbc/AssetDao.scala:403-467`), then re-reads its matches
  locked through `SequenceService.locked` (`:66-74`) → `AssetDao.lockSequenceFrames` (`:360-369`), which locks them and,
  for a merge, every member `repointSequences` (`:663-671`) writes, in one pass (`lockWhere`, `:383-400`, `ORDER BY id
  FOR UPDATE`). A Curation resolve takes `lockSequences`, then `lockLiveFrames` (`CurationService.scala:102-115`;
  `liveSequenceMembersStatement`, `AssetDao.scala:626-640`); a reopen `lockSequences`, `lockTree`, then `lockCompleted`
  (`:147-151`). Library operations take no `lockSequences`. Tests (PostgreSQL): `SequenceServiceTests` "An assignment
  and a recycle of its candidates at once both commit", "A merge and a recycle of a member outside the frame's window at
  once both commit", "A completing frame and a rename of its importer at once both commit"; `CurationServiceTests` "A
  resolve and a recycle of frames of its set at once both commit", "A reopen and a move of its frames at once both
  commit".
- **An account write against an Edit save.** `AssetEditService.save` (`service/AssetEditService.scala:93`) share-locks
  the saver (`UserService.lockActor`, `:470-476`, `FOR KEY SHARE`, refusing an inactive account) before `lockAt` /
  `lockUnpurged`; `reassignOwner` and `rewriteImporterRecords` lock through `AssetDao.inIdOrder` (`:704-705`, an
  ID-ordered `FOR NO KEY UPDATE` subquery). `UserDao.lockActiveAdmins` (`dao/jdbc/UserDao.scala:72-76`) is `ORDER BY id
  FOR NO KEY UPDATE`, so a foreign-key check against an Admin does not wait on it. Tests (PostgreSQL):
  `AssetEditServiceTests` "An Edit save and an Admin's change of the saving account at once both commit";
  `AccountManagementTests` "A rename rewriting the importer records and a recycle of the assets at once both commit", "An
  Admin's import does not wait for another Admin's change to a third account".
- **An account write against a Watch folder edit.** `WatchFolderService.update` (`:281`) and `resume` (`:335`) call
  `lockEditor` (`:354-355`, `lockActor(mustBeAdmin = true)`) after `lockPaths` and before the Watch folder's row, and
  refuse an editor deactivated, deleted or demoted by then (`ForbiddenException`). Tests (PostgreSQL):
  `WatchFolderServiceTests` "An edit of a Watch folder and another Admin's delete of its owner at once both commit", "An
  edit of a Watch folder by an Admin being deleted is refused once the delete commits", "An edit of a Watch folder by an
  Admin being demoted is refused once the demotion commits".
- **A scan against an edit or removal of its Watch folder.** `WatchFolderService.remember` (`:874-930`) writes the Watch
  folder's row first (`WatchFolderDao.scanned`, `dao/jdbc/WatchFolderDao.scala:196-205`, `WHERE path` = the walked path);
  on 0 rows the scan ends `REMOVED` or `PATH_CHANGED` and remembers nothing. `update` and `remove` write the row first;
  `retryFailed` and `forgetAbsent` lock it first (`WatchFolderDao.lock`, `:89-90`, `FOR NO KEY UPDATE`). Tests
  (PostgreSQL): `WatchFolderServiceTests` "A scan and an edit of its Watch folder at once both end, the edit as made", "A
  Watch folder removed while its scan walks ends the scan as removed", "A scan whose Watch folder is moved to another
  directory meanwhile remembers nothing of the old one", "Reading failed files again and forgetting absent ones wait for
  the Watch folder's row, as a scan's memory write does".

### 15. A start never drops a schema, and refuses a non-empty one

Neither `all.sql` holds `DROP SCHEMA` or `CREATE SCHEMA`. `MigrationService.migrate` (`service/MigrationService.scala:127-149`)
runs `requireEmpty()` (`:67-91`) in the script's transaction, before it: an `IllegalStateException` naming up to five
tables when the schema it creates into holds a table or view (`current_schema()` on PostgreSQL, extension-owned relations
aside; `sqlite_%` and `_sqliteai_vector` aside on SQLite). On PostgreSQL `requireSearchPathToSchema` (`:93-108`) refuses a
search path that names no existing schema or leads past a schema holding a `system` table. `version` (`:114-118`) reads
only a missing table (42P01, "no such table") as version 0 and rethrows any other failure. The test wipe drops and
recreates `public` itself (`altitude/test/src/altitude/core/suites/TestDatabase.scala:39`). Recorded done in
`docs/READINESS.md:167` (Data safety).

Tests, `StartupTests`: "A start against a database holding another application's tables refuses, and leaves them as
they were"; PostgreSQL: "A fresh PostgreSQL database whose schema holds an extension's views gets the schema beside
them", "A start whose PostgreSQL search path names no schema that exists refuses, naming the search path", "A start whose
PostgreSQL search path leads past Altitude's schema refuses, naming the schema that holds it", "A failure to read the
schema version other than a missing table stops the migration".

### 16. No bound value reaches an exception or a log line

The raw helpers prepare, bind, run and close their statement in `BaseDao.runStatement` (`dao/jdbc/BaseDao.scala:275-297`).
A failure is rethrown as `BaseDao.statementFailed(sql, e)` (`:77-87`): the driver's message, the statement, the SQLState
and error code, the driver's exception as cause, never the values; a `BatchUpdateException` rethrows its
`getNextException`. TRACE lines go through `BaseDao.trace(message: => String)` (`:257-261`), built only when
`isTraceEnabled`, with the value count. `SqlExplainer.runnable` (`transactions/SqlExplainer.scala:45`, `:91-93`) masks
text values as `'…'` in a statement naming `password_hash` or `signing_key`. Recorded done in `docs/READINESS.md:73`.

Tests: `UserServiceTests` "A failed account insert names its statement, never the password hash it bound, in its
exception or its log"; `BaseDaoTests` "A failed batch names its statement, never the values bound to it", "A read by
query that finds nothing names the query's fields, never their values".

### 17. The sqlite-vector path is bound, not spliced

`TransactionManager.setUpSqliteConnection` (`transactions/TransactionManager.scala:397-415`, bind at `:401-405`) prepares
`SELECT load_extension(?)`, binds the path with `setString` and closes the statement in `finally`.

Test: `TransactionManagerTests` "sqlite-vector loads from the folder altitude.native.dir names, and from the classpath
when it names none" (edge case: a folder named `o'brien`).

### 18. A SQLite writer waits for the write connection up to a configured bound

`sqlitePool` (`transactions/TransactionManager.scala:363-390`) sets `connectionTimeout` on the write pool only (`:387`),
from `db.sqlite.write_connection_timeout` (`Const.Conf.SQLITE_WRITE_CONNECTION_TIMEOUT`, `Const.scala:24`), default `10m`
(`altitude/resources/reference.conf:58-62`); past it a writer fails with `SQLTransientConnectionException`. A wait over
`SLOW_CONNECTION_WAIT_MS` (30 s, `:46-47`) is logged at WARN when the connection comes. The read pool and the PostgreSQL
pool keep HikariCP's 30 seconds. `altitude/AGENTS.md:120` (**Connections and transactions**) states the bound and the
measured lengths of the longest writes.

Tests: `TransactionManagerTests` "A SQLite writer waits for the write connection past HikariCP's 30 seconds" (`Slow`,
SQLite), "A SQLite writer waits for the write connection as long as the configured timeout, and no longer".

### 19. A hand-written statement binds without asking the driver for parameter types

No DAO uses `QueryRunner`. `BaseDao.bind` (`dao/jdbc/BaseDao.scala:66-75`) binds a value with `setObject` and a NULL with
`setNull(i, Types.NULL)`; `requireValueCount` (`:263-273`) calls `getParameterMetaData` only on SQLite, where the driver
counts placeholders locally, and PostgreSQL's driver refuses a wrong count itself; a statement with no values runs as a
plain `Statement`. commons-dbutils stays for `BasicRowProcessor` and `RowProcessor`. The measured cost (about 300 µs a
short statement through `QueryRunner`, 120 to 190 µs without) is in `altitude/AGENTS.md:103`.

Tests: `BaseDaoTests` "A hand-written statement binds its values, NULL included, without asking the driver for their
types" (PostgreSQL, a connection proxy counting `getParameterMetaData` calls), "A NULL bound in a hand-written statement
fits a column of any type", "A hand-written statement given fewer or more values than its placeholders fails, as does a
batch row of another length".

## P3

### 20. The schema and its version commit together

Both `all.sql` seed version 0 (`postgres/all.sql:32`, `sqlite/all.sql:24`). For a fresh database `migrate` runs
`requireEmpty`, the script and `systemMetadataDao.updateVersion(currentVersion)` in one `withTransaction`
(`service/MigrationService.scala:133-138`); `updateVersion` (`dao/jdbc/SystemMetadataDao.scala:30-35`) throws unless
exactly one row is stamped. An upgrade reads every `<version>.sql` before the first runs, then runs each in its own
transaction with `migrateVersion` and its stamp (`:140-148`). A missing script fails in `script()` (`:40-50`) with an
`IllegalStateException` naming the path, the stored version and the build's (`Altitude.schemaVersion = 2`).

Tests, `StartupTests`: "A new instance against a fresh database creates the schema and seeds the Settings", "A fresh
database whose schema cannot be stamped with its version is left empty", "A fresh database whose schema seeds no system
row fails its version stamp and is left empty" (SQLite), "A database behind the code's version with no script to bring
it forward stops the migration, naming the script", "Every script an upgrade needs is read before the first runs, so a
missing one changes nothing" (fixtures under `altitude/test/resources/schema-scripts/`).

### 21. Unique violations are told apart by index

`BaseDao.violates(e, UniqueIndex)` (`dao/jdbc/BaseDao.scala:120-136`, `UniqueIndex(name, columns)` at `:94`) never reads
the message text: on PostgreSQL SQLState 23505 and `getServerErrorMessage.getConstraint` equal to the index name; on
SQLite `SQLITE_CONSTRAINT_UNIQUE` (`isUniqueViolation`, `:112-118`, extended result codes) with the reported columns
matched exactly, or the index name for an index on an expression. The indexes are declared beside their DAOs
(`UserDao.EMAIL_KEY_INDEX`, `USERNAME_KEY_INDEX` in `dao/UserDao.scala:8-9`, `RepositoryDao.NAME_KEY_INDEX`,
`AssetDao.LIVE_CHECKSUM_INDEX`); callers are `UserService.whileKeysFree` (`service/UserService.scala:667-673`),
`RepositoryService` (`:77`) and `LibraryService` (`:337`).

Tests: `BaseDaoTests` "A unique violation is told apart by the index it breaks, as the failed statement or the driver
reports it", "A failure that breaks no unique index is no unique violation"; `AccountManagementTests` "A taken email
address that holds the username key's name is refused on the email address", "An email address or a username another
account holds is refused on its field, on a create and on an update".

### 22. A Library ID from a request is a UUID or nothing

`RepositoryService.setContextFromRequest` (`service/RepositoryService.scala:257-270`) refuses a Library ID that fails
`Validators.isUuid` before any read, logs at DEBUG and sets no context, so it costs no query and no `repositoriesById`
entry; `decorators.repoContext` clears `RequestContext` before and after the request. Recorded done in
`docs/READINESS.md:107`; the other path IDs are under **Open**.

Tests: `RepositoryServiceTests` "A Library ID from a request that is no UUID sets no repository context and reads
nothing"; `IndexControllerTests` "A Library ID padded with a space is a Library not found, though the Library is the
user's".

### 23. `area_size` holds any image's area

`AssetDao` computes `asset.width.toLong * asset.height` (`dao/jdbc/AssetDao.scala:196`) into `AssetRow.areaSize: Long`,
`BIGINT NOT NULL` on PostgreSQL (`postgres/all.sql:199`), SQLite's 64-bit `INT` (`sqlite/all.sql:170`).

Test: `SearchServiceTests` "An image of more than 2^31 pixels is stored with its whole area and sorts by it, paged
through a cursor".

### 24. The recursive folder read returns each folder below once

`FolderDao.getChildrenRecursive` (`dao/jdbc/FolderDao.scala:132-160`) has `AND id <> parent_id` in the anchor and the
arm, so for the root it returns every other folder once and never the root. `FolderDao` has no `getChildren`; `getById`
(`:37-59`) counts live children only.

Test: `FolderServiceTests` "The root's folders all the way down are every other folder, each once".

### 25. `updated_at` exists where it is written

`UserDao.rememberLastActiveRepository` (`dao/jdbc/UserDao.scala:170-177`) sets `last_active_repo_id` alone, and only
when it `IS DISTINCT FROM` the stored one. `updated_at` is a column of `account`, `asset_edit` and `watch_folder` only
(`postgres/all.sql:104`, `:313`, `:530`; `sqlite/all.sql:97`, `:295`, `:571`); `BaseModel`, `NoDates` and `Album` carry
no `updatedAt`.

Tests: `UserServiceTests` "Remembering a Library writes the account only when it is not the one recorded, and never
stamps its update time"; `RowColumnTests` keeps the schemas and row classes in step.

### 26. The user-metadata write path, which no route calls yet (open)

Nothing under `routes/` calls `UserMetadataService`'s writes or sets `metadataFilters`; tests do. These defects become
live with a metadata editor:

- A BOOL value set again replaces the stored value but leaves the old `metadata_parameter` row: `SearchDao.addMetadataValue`
  (`dao/jdbc/SearchDao.scala:348`) goes through `addParameters` (`:353`), which only inserts.
- `metadataFilter` (`dao/sql/search/SearchQueries.scala:1345-1359`) counts surviving rows per asset, not distinct fields,
  so two rows of one field satisfy a two-filter search.
- `deleteFieldById` (`service/UserMetadataService.scala:55`) deletes the field row alone: its values stay in
  `asset.user_metadata` and their words in the search document, and `toJson(getMetadata(asset))` then throws
  `NoSuchElementException`.
- `addMetadataValue` (`:91-97`) indexes the raw value, not the cleaned one that is stored; `setMetadata` and
  `updateMetadata` write the column without touching the document, as `altitude/AGENTS.md:790` says.
- `AssetDao.updateMetadata` (`dao/jdbc/AssetDao.scala:708-715`) merges in Scala from an unlocked read, so two concurrent
  edits of one asset lose a value on PostgreSQL while its parameter row stays.
- `addField` (`:26`) checks, then inserts, so a racing duplicate leaves as a raw `SQLException`; NUMBER values go
  through `Double` (`:337`), which accepts `NaN` and rounds past 15 digits; a value has no length cap, and PostgreSQL's
  tsvector fails past 1 MB.

Fix, with the editor: single-valued types replace their rows, the filter counts `DISTINCT field_id`, a field delete strips
its key from the assets and reindexes them (or is refused while it holds values), the index is written from the stored
value, the column is read `FOR NO KEY UPDATE`, and `clean` caps a value's length.

### 27. Person and Face statements are scoped to their Library

`PersonDao.addFacesOfAssets` (`dao/jdbc/PersonDao.scala:196-209`), `lockAssetPeople` (`:212-224`), `getAssetPeople`
(`:229`), `deleteFaceless` (`:252-270`, its `NOT EXISTS` and `DELETE` included) and `FaceDao`'s `mine` / `theirs`
subqueries (`dao/jdbc/FaceDao.scala:154-155`) carry `repository_id = ?`. The six statements that reach a Library's table
without its Library are listed in `altitude/AGENTS.md:109`.

Test: `RepositoryIsolationTests` "Another repository's Person is neither read nor changed, and a merge with them is not
found" (with `recycleFacesForAssets` and `restoreFacesForAssets` on the other Library's asset).

### 28. Every prepared statement is closed

One `FaceDao.add` (`dao/jdbc/FaceDao.scala:60-91`) serves both engines, through `addRecord` with `vectorPlaceholder` and
`vectorBind` (`PostgresOverrides`, `SqliteOverrides`). `BaseDao.runStatement` (`dao/jdbc/BaseDao.scala:282-294`) and
`batchBySql` (`:415-432`) close in `finally`; `SearchDao.addParameters` (`dao/jdbc/SearchDao.scala:353-371`) goes through
`batchBySql`; `MigrationService` closes its statements and sources in `finally` (`:27-58`).

Tests: `BaseDaoTests` "A hand-written statement given fewer or more values than its placeholders fails, as does a batch
row of another length" and "A failed batch names its statement, never the values bound to it" run these paths; no test
observes a statement's closing.

### 29. Unnamed people sort by their number at any length

`PersonDao.add` binds `SortKey.of(personName).bytes` into `person.name_sort` (`dao/jdbc/PersonDao.scala:76-78`, declared
in `sortKeyColumns`, `:27`), and lists order `ORDER BY is_named DESC, name_sort, id` (`:135`, `:183`); ICU's numeric
collation (item 32) puts "Unknown 10000" after "Unknown 2000".

Tests: `PersonServiceTests` "People are listed in natural order on both engines, the named first"; `NaturalOrderTests`
"Numbers of any length compare by value, and the order stays total".

### 30. Watch folder and Import edges

- **A scan's pause and status reason follow the row.** `WatchFolderDao.setStatusReason`
  (`dao/jdbc/WatchFolderDao.scala:183-195`) writes only while `is_paused = false` and the stored reason differs, and
  `WatchFolderService.available` (`:780-792`) calls it only when the reason changed since the scan's read. `pauseFor`
  (`:765-776`) share-locks the owner and reads them again, pausing nothing when they may own again; otherwise
  `pauseForOwner` (`:157-169`) pauses only while that owner still owns it unpaused. `update` writes no pause; `resume`
  runs `lockEditor`, then locks the row. Tests, `WatchFolderServiceTests` (PostgreSQL): "A scan that finds its directory
  back keeps the reason of the pause its owner's deactivation made meanwhile", "A scan pauses no Watch folder for an
  owner an Admin's edit has replaced meanwhile", "A scan pauses no Watch folder for an owner reactivated meanwhile", "A
  resume that finds the owner gone keeps the owner a concurrent edit made"; "An edit of a paused Watch folder made while
  it is resumed leaves it resumed" (`contend`).
- **Two names that read as one path** are one file: `onePerPath` (`service/WatchFolderService.scala:852-856`) keeps the
  first by its name's bytes and names the others on the card, percent-encoded. Test: "Two files whose names read as one
  path are one file to a scan, which names the other on the card".
- **A path has a byte cap**, `Const.WatchFolder.MAX_PATH_BYTES = 2600` (`Const.scala:161`), checked by
  `WatchFolder.isPathTooLong` (`models/WatchFolder.scala:35-37`): a Watch folder path over it is refused
  (`WATCH_PATH_TOO_LONG`, `WatchFolderService.scala:523-524`); a file path over it is skipped in `walk` (`:824`) and named
  on the card (`PATH_TOO_LONG` / `PATHS_TOO_LONG`), and `WatchFolderFileDao.addAll` requires it (`:82`). Tests: "A file
  whose path under its Watch folder is too long to remember is skipped and named on the card", "A Watch folder's path is
  refused past a length in bytes, and its name with a control character".
- **Control characters.** An upload refuses only a NUL in a file's path: the file fails on arrival
  (`ImportService.failOnArrival` / `failedOnArrival`, `service/ImportService.scala:193-201`, `PATH_HOLDS_NUL`) and is
  recorded failed under its path made printable (`addFailed`, `:207-214`, `Util.printable`); `RelPath.segments`
  (`util/RelPath.scala:18-25`) keeps other control characters. An Import's name (`ImportService.open`, `:104`) and a
  folder name an import builds (`LibraryService.sortInto`, `service/LibraryService.scala:132-134`) have each control
  character replaced with U+FFFD (`Util.withControlCharactersReplaced`). A name typed into a dialog is refused:
  `Validators.validName` (`Validators.scala:23-42`) throws `CONTROL_CHARACTERS` by `Util.isControlCharacter`
  (`util/Util.scala:158-163`: ISO controls, surrogates, format characters but ZWJ and ZWNJ), for albums, folders,
  Locations, people, Libraries, metadata fields, Watch folders, accounts and Setup. Tests: `ImportControllerTests` "A file
  whose path holds a NUL is answered and recorded as failed, and an Import's name is stored clean"; `ImportServiceTests`
  "An upload's file whose path holds a NUL is recorded failed under its path made printable, and its name is shown
  clean"; `ImportPipelineServiceTests` "A file below a folder whose name holds control characters lands in a folder of
  the name with them replaced"; `FolderServiceTests` "A folder name with a control character, longer than 250 characters
  once in NFC, or of too long a name key, is refused".
- **A scan's memory streams.** `WatchFolderFileDao.memoryOf` (`dao/jdbc/WatchFolderFileDao.scala:69-77`) reads rows
  straight into the map (`foreachBySqlQuery`); `changed` (`:108-119`) writes the changed files in one `batchBySql`.

### 31. Search edges

- **An edited cursor is a 400.** `SearchQueries.requireCursorFits` (`dao/sql/search/SearchQueries.scala:857-861`)
  refuses a sort value that fails `SearchDialect.readsAs` (`dao/sql/search/SearchDialect.scala:91-99`), and under a
  scored sort the second value as a capture time; an unknown sort field is an `IllegalArgumentException`. The group key
  is parsed in `cursorKey` (`:867-873`), which turns a `DateTimeException` into a malformed cursor, for the
  `LocalDate.parse` (`:241`) and `LocalDateTime.parse` (`:566`). Tests: `SearchCursorTests` "A cursor whose values were
  edited is refused for the search it names"; `SearchSqlTests` "A cursor's sort value is checked for every sort field and
  both scored sorts, and an unknown field is refused".
- **The map's Location members are scoped.** `membersOfLocationsIn(engine, bbox, repositoryId)` (`:1540-1547`) and the
  outer map statement (`:805`) filter by `location.repository_id`. The outer asset is scoped too, so the results
  are the same without it and no test can tell; the filter bounds the cost by the Library.
- **One form per letter.** `SearchWords.folded` (`util/SearchWords.scala:54-66`) maps each letter to
  `toLowerCase(toUpperCase(cp))`, so ς and σ, µ and μ, ſ and s, ı and i come out alike on both engines. Tests:
  `SearchWordsTests` "A sigma is one letter at a word's end and inside it, typed in either case", "A letter with two
  lower-case forms comes out in one, whatever the case it is typed in"; `SearchServiceTests` "Search text: a sigma at a
  word's end matches in either form, typed in either case", "Search text: a letter with two lower-case forms matches in
  either".

### 32. One natural order of names on both engines and in memory

`util/SortKey.scala` (`SortKey.of`) defines it: ICU4J's root collator (pinned in `build.mill:97`) at tertiary strength
with numeric collation and canonical decomposition, one collator per thread, the key being ICU's sort key bytes with a
tie-break after them (the name's length, then its UTF-16 units), compared unsigned. Where SQL orders or pages a list the
key is stored, `BYTEA NOT NULL` on PostgreSQL and `BLOB NOT NULL` on SQLite, never indexed: `asset.filename_sort`,
`person.name_sort`, `album.name_sort`, `location.name_sort`, `watch_folder.name_sort`, `import_file.rel_path_sort`. A
DAO of such a table declares the column in `BaseDao.sortKeyColumns` (`dao/jdbc/BaseDao.scala:488-495`), so its insert
and its generic name write bind the key; `WatchFolderDao.add` and `update` bind it by hand. Location groups order by
`SearchDialect.pathKey` (`SearchDialect.scala:106`, the keys in the engine's hex); the file name cursor carries the name
and binds its key (`cursorSortValue`, `SearchQueries.scala:1144-1146`). Lists sorted in memory key each name once with
`SortKey.of`. Every PostgreSQL text column an index holds or a list orders by is `COLLATE "C"` (`postgres/all.sql:6-12`).
The rule and a stale key's effect are in `altitude/AGENTS.md` (**Names**, **Natural order**, `:709-713`) and the
`SortKey` scaladoc ("Stability").

Tests: `NaturalOrderTests` "Names order by their sort key, one order for a list sorted in memory and one sorted by a
database", "Letters sort with their base letters and any script's digits as numbers, as ICU's root collation has them"
and four more; a "… in natural order on both engines" test in `AlbumServiceTests`, `LocationServiceTests`,
`PersonServiceTests`, `ImportServiceTests` and `WatchFolderServiceTests`; `SearchCursorTests` "A file name sort walks
names in natural order on both engines, through a cursor"; `RowColumnTests` "Every PostgreSQL text column an index holds
or a list orders by is COLLATE \"C\"".

### 33. The engines' schemas agree

| Object | PostgreSQL | SQLite |
|---|---|---|
| `metadata_parameter.asset_id`, `field_id` | `NOT NULL` (`postgres/all.sql:477-478`) | `NOT NULL` |
| `repository.file_store_config` | `jsonb NOT NULL` (`:120`) | `TEXT NOT NULL` (`:113`) |
| the per-asset person probe | `face_01 … INCLUDE (person_id)` (`:394`) | `face_04 ON face (asset_id, repository_id, person_id)` (`:384`), beside `face_01` |
| `created_at` defaults | `now()`, microseconds | `NOT NULL DEFAULT (datetime('now'))`, whole seconds |

- The `sqlite/all.sql` header (`:6-13`) lists the differences that remain: the default's precision, the inserts that
  bind their own time (`AssetDao.add`, `ImportDao.add`, `AuditEventDao.add`), and no `VARCHAR` length or boolean `CHECK`
  on SQLite.
- Every column that references an account leads an index: `import_04 ON import (account_id) WHERE account_id IS NOT
  NULL`, `watch_folder_02 (account_id)`, `account_03 (created_by_account_id) WHERE … IS NOT NULL`, on both engines.
- A stamp a DAO binds is `Util.utcNow` (`util/Util.scala:28`), truncated to microseconds, in `UserDao`, `AssetEditDao`,
  `ImportDao.add` and `AuditEventDao.add`.
- `TestContext.SCOPED_TABLES` (`altitude/test/src/altitude/test/TestContext.scala:137`) is a list checked against the
  schema.

Tests, `RowColumnTests`: "Each column is nullable on both engines or on neither, as its row class types it", "Both
schemas name the same indexes on the same tables, but where an engine's index has no twin", "Every column that
references an account leads an index, so an account delete seeks its rows", "The scoped tables a test compares
repositories by are every table that carries repository_id"; `BaseDaoTests` "The stamps of an account write and of an
Edit save hold microseconds at most, as PostgreSQL keeps them".

### 34. Instants read as UTC, and pages show them in the viewer's zone

`PostgresOverrides.javaTimeRowProcessor` (`dao/postgres/PostgresOverrides.scala:29-35`) reads a `timestamptz` as an
`OffsetDateTime`; `getDateTimeField` (`:75-81`) and the typed reads convert it through `BaseDao.toLocalDateTime`
(`dao/jdbc/BaseDao.scala:234-235`), so a model holds the UTC wall clock on both engines whatever the JVM's zone, and
`Util.instantOf` (`util/Util.scala:37`) is `toInstant(UTC)`. A page shows a stored moment in the viewer's zone, as a
`<time>` of its UTC instant (`views/includes/local_time.scala.html`): the account info dialog and cards, the Import
history, the Watch folder cards, the album subtitle. An import time, the grid's "Imported:" (`views/htmx/result_cell.scala.html:60`)
and the Detail panel's "Date Imported" (`views/htmx/detail_panel.scala.html:56`), is shown in the server's zone
(`Util.inServerZone`, `:44`), the zone a Date Imported range cuts its days in.

Tests: `BaseDaoTests` "An instant in the autumn overlap hour reads back as itself, whatever the JVM's zone"; `UtilTests` "A
stored moment is shown in the server's zone, on the day a Date Imported range finds it"; `SearchResultsControllerTests`
"An import time shows in the server's zone, on the day a Date Imported search finds it, in the grid and Detail panel";
`UsersControllerTests` "An account card shows when the account was created as a time the viewer's browser shows in its
own zone".

### 35. Latent traps closed

- **A pooled thread works under no request's context.** `RequestContext` (`RequestContext.scala:12-43`) holds `conn` as a
  `ThreadVariable` (a `ThreadLocal`, not inherited) and the repository, account, permissions, grants, session expiry
  and read count as `RequestVariable`s the decorators set outright and `RequestContext.clear` (`:93`) clears, so work on a
  pooled thread that binds nothing fails `getRepository`. `PipelineUtils.withContext` (`pipeline/PipelineUtils.scala:43-52`)
  binds `grants` too. Tests (unit): `PipelineUtilsTests` "A pooled thread started under a request's context does not
  work under it", "A pipeline stage's work runs with the context's grants, not the thread's".
- **No generic paging.** `BaseDao.queryRecords` (`dao/jdbc/BaseDao.scala:401`) returns every match in no order (a page
  of a list is the search's); `QueryResult(records)` sets `total` to the records' count; `Query` carries no order or page
  (`util/Query.scala:60`); there is no `loadCsv`.
- **SQLite booleans are numbers.** `SqliteOverrides.nativeBool` (`dao/sqlite/SqliteOverrides.scala:51`) binds 1 and 0;
  `getBooleanField` (`:86-89`) accepts a Boolean or an Integer 0 or 1 and throws for anything else, as PostgreSQL's
  (`PostgresOverrides.scala:86`) does for a non-Boolean. Tests, `BaseDaoTests`: "A flag a hand-written statement binds is
  a number, which a partial index's predicate on the flag matches" (SQLite), "A stored flag that is neither of SQLite's
  two numbers fails its read rather than read as false" (SQLite), "A flag a hand-written read gets back as NULL, or as no
  flag of its engine, fails the read rather than read as false".
- **`AssetService.markAsCompleted`** (`service/AssetService.scala:100-104`) checks its count with `requireFound`. Test:
  `AssetServiceTests` "Completing an asset that is not in the Library is a not-found error, not a silent no-op".
- **`RepositoryService.grantNewLibrary`** (`service/RepositoryService.scala:117-130`) runs `lockActiveAdmins`, then
  `lockGrantees`, and refuses a creator not found active (`FORBIDDEN_INACTIVE`) or a non-Admin without
  `canCreateLibraries` (`FORBIDDEN_LIBRARY_CREATOR`). Tests: `RepositoryServiceTests` "A creator who is deactivated or may
  no longer create Libraries creates none", "A Library created while its creator is deactivated is refused once the
  deactivation commits" (PostgreSQL, `contend`); `LibraryActionControllerTests` "A creator whose Create Libraries flag is
  taken away after its login is refused the create with a 403".
- **`PersonService.updateName`** (`service/PersonService.scala:680-684`) takes `dao.lockName(nameKey(name))` first in its
  transaction, the lock a move-to-name takes; no test races the two.
- **An album or Location add racing a recycle** leaves the asset out (item 13's `shareLockLive`, `AssetDao.shareLockLive`,
  `dao/jdbc/AssetDao.scala:345`).
- **SQLite connection setup.** A read connection sets only `PRAGMA query_only=1` and writes no statistics; the write
  connection runs `refreshStatisticsAtOpen` (`transactions/TransactionManager.scala:446`: `busy_timeout=0`, `PRAGMA
  optimize=0x10002`, the timeout restored) and `wal_autocheckpoint=500`, and the hourly `optimize()` (`:313`) runs on the
  write pool. `registerVectorColumns` (`:423`) runs at setup (`:407`) and at each transaction's start (`:167`) until the
  `face` and `asset` tables exist. Tests, `TransactionManagerTests`: "A SQLite connection opened before the schema existed
  searches the vectors once the schema is created", "A SQLite read connection opens while another connection holds the
  write lock, and writes no statistics" (SQLite, a scratch database file).
- **`TransactionManager.afterCommit` outside a transaction** (`:268-271`) goes through `runAfterCommit` (`:297`), which
  logs a throwing action at WARN and keeps it from the caller. Test: `TransactionManagerTests` "An after-commit action
  runs once the outermost write commits, and never when it rolls back".

### 36. Text that agrees with the code

- `altitude/AGENTS.md:101` (**DAO / Service Layer**): `$inIdSet` / `idSet` and `Columns.isInSet` bind a set as one
  parameter, `DynamicFilter` sends a string `Query.IN` through `isInSet`, a non-string one binds per value (item 3).
- `altitude/AGENTS.md:109`: the six statements that reach a Library's table without its Library (item 27).
- `altitude/AGENTS.md:120` (**Connections and transactions**): the write connection's bound,
  `db.sqlite.write_connection_timeout` (item 18).
- `altitude/AGENTS.md:790`: the search document's writers among `UserMetadataService`'s edits, and the two that do not
  write it (item 26).
- `PersonService.scala:315-330` and `docs/faces.md:197-200`, `:382-384`: who is left faceless and who is deleted
  (item 11).
- `dao/SearchDao.scala:25-28`: the page that crosses the line, the first page or a later one, says where the weaker
  matches begin (`SearchDao.weakerMatches`, `dao/jdbc/SearchDao.scala:104-115`).

## Checked and cleared

- SQLite's `created_at` defaults, `datetime('now')`, are UTC.
- The search's Location groups' path key (`SearchDialect.pathKey`) orders as the (category, Location) pair on both
  engines: no key is the start of another (`SearchGroupingTests` "Location groups are in path order, hold an asset under
  each of its Locations, and end with No location").
- Repository scoping of every hand-written statement but the six of `altitude/AGENTS.md:109`, both arms of each
  recursive statement, and the unscoped subqueries of the search, each intersected with a scoped asset.
- Bind order and count of every hand-written statement; `makeModel`, `toModel`, the Row tables and both schemas column
  by column; the schema guards of the guide on every scoped table; index spellings against `jsonText`, `dayOf` and the
  partial-index predicates.
- Keyset paging of every search order, for both null placements and directions; totals against rows; the FTS5 triggers.
- `TransactionManager`: the connection binding and after-commit list on every exit path, a failed setup closing its
  connection, a write nested in a read failing on both engines, the vector-search settings nested.
- The Import counters (`addPending`, `fileSettled`, `markComplete`, `settle`) under concurrent settles, the stats
  writes, the Edit's insert-or-update under the asset lock, the account lock helpers' ID order, the grant bound under
  the account's row lock.

## Verification

Each item has the red test named with it, on the engine named: `make test-focused-sqlite`, and `make test-focused-psql`
against the test container for a PostgreSQL guard, which is shown red on PostgreSQL before its fix since SQLite's one
write connection hides it. A lock-order test is one two-thread PostgreSQL test per pair in the suite of the service that
locks, built on `IntegrationTestCore.contend` (two writers), `whileHolding` (an interleaving of three; it fails a writer
that ends without waiting and a holder never released), `ends` (a writer that must end) and `backendsWaitingOn` (the
recipe is "Lock-order tests" in `docs/testing-cookbook.md`). Then `make test`; the `Slow` tests of items 3 and 18 run
under `make test-with-slow`.

## Open

- **Item 26**, the user-metadata write path, above.
- **PostgreSQL's own error detail can quote a colliding key or a failing row.** `statementFailed` keeps the driver's
  message, and pgjdbc's `logServerErrorDetail=false` is not set on `postgresPool`. Recorded in `docs/READINESS.md:74-75`
  (**Login and sessions**, `[LOW]`).
- **A `CHAR(36)` ID from a route path parameter other than the Library's** (`:assetId`, `:personId`, `:faceId`,
  `:importId`, `:sequenceId` and others) matches a space-padded value on PostgreSQL, which compares it as
  `bpchar`. Recorded in `docs/READINESS.md:122` (**Permissions**, `[LOW]`).
- **A test that runs DDL against the shared SQLite test database breaks the rest of the bundle**; the cause is not
  found. Tests use TEMP tables (`BaseDaoTests` `withPairTable`) or scratch database files (`StartupTests`,
  `TransactionManagerTests`) instead. The only record in the tree is the edge-case note of `BaseDaoTests` "A unique index
  on an expression is told apart by its name…".
- **Stored sort keys need a database recreate when `SortKey` or the ICU version changes**: until every `*_sort` column is
  written again, a list the database orders disagrees with one ordered in memory. No key version is stored and nothing
  re-keys at startup. Recorded in `altitude/AGENTS.md:713` (**Names**, **Natural order**, "A stale key") and the
  `SortKey` scaladoc.
