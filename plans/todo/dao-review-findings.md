# DAO layer: defects to fix, by priority

## Scope

Everything under `altitude/src/altitude/core/dao/` (the JDBC base, the engine overrides, the typed tables, the search
queries and dialects), and what it rests on: both `all.sql` schemas, `TransactionManager`, `Db`, `MigrationService`.
Service callers are in scope only where a DAO statement's correctness depends on them: the lock a caller takes first,
the row count it checks, the values it can pass.

Each item says how it is known:

- **Reproduced**: a throwaway test showed the behaviour on this branch, on the engine named. The tests are not in the
  tree; each item names the test to write.
- **Read**: it follows from the code as written and was not run.

Priorities:

- **P1**: corrupts the folder tree or People, hides assets from a result, makes an operation fail for good, or fails an
  import in ordinary use.
- **P2**: a raw 500 or wrong state on a path that needs a race, a rare input or an Admin's mistake; a security or
  data-safety gap with a low likelihood; a cost every statement pays.
- **P3**: latent (no route reaches it), cosmetic, or a text that contradicts the code.

No statement reads or writes another Library's rows, and no SQL is built from request values: repository scoping and
binding hold everywhere they were checked. Most P1 and P2 items are PostgreSQL-only, because SQLite's one write
connection serializes what PostgreSQL's `READ COMMITTED` writers interleave.

## P1

### 1. Two opposite folder moves commit a cycle, and every recursive folder statement then never ends

`FolderService.move` (`service/FolderService.scala:163-181`) checks `getAncestors(dest).contains(moved)` and then
writes `parent_id`. Its only lock is `lockName(dest, nameKey)`, a different key for each of two moves, and it never
takes `lockTree`. The recursive statements of `FolderDao` (`dao/jdbc/FolderDao.scala:132-146`, `:177-193`) are
`UNION ALL` with no depth bound and no cycle guard.

- A and B are siblings. `move(A, B)` and `move(B, A)` overlap: each ancestor check reads the tree before the other's
  write, both commit, and `A.parent = B`, `B.parent = A`. Both subtrees leave the tree while their assets stay live.
  `getAncestors`, `getPath` and `getChildrenRecursive` on either then recurse until the read statement timeout, and in a
  write transaction (`restoreRecycledAsset`, `deleteFolderById`) without a limit. **Reproduced** (PostgreSQL). SQLite
  serializes the two moves, check included.

Fix: `dao.lockTree()` first in `move`'s transaction, before the ancestor check, as `deleteFolderById` and `ensurePath`
take it. Make the statements safe against a cycle already stored: PostgreSQL's `CYCLE id SET is_cycle USING path`
clause, and on SQLite a depth column bounded in the recursive arm (or `UNION` instead of `UNION ALL`, which ends on a
repeated row). Test: `FolderServiceTests`, PostgreSQL: hold `move(a, b)` open on one thread, run `move(b, a)` on
another, release; the second is refused with `IllegalOperationException`. `contend` fits once `lockTree` makes the
second wait.

### 2. An excluded Search word that names a folder hides every asset with no folder

`SearchQueries.textFilter` (`dao/sql/search/SearchQueries.scala:1259-1273`) negates a folder source as
`!isIn(...)`, which renders `NOT (asset.folder_id = ANY(?))` on PostgreSQL and `NOT (folder_id IN (SELECT value FROM
json_each(?)))` on SQLite. A triaged asset's `folder_id` is NULL, so the test is NULL and the row is dropped. Every
other source negates an `EXISTS` or a `NOT IN` over a `NOT NULL` column.

- A folder "Rome trip" exists and Triage holds one asset with no "rome" in its document. Search text `-rome` in the
  Triage view returns nothing. The count, the groups and the map agree with the wrong answer, since they share
  `matching`. **Reproduced** (both engines).

Fix: make the folder membership two-valued in `folderFilter` for the text-source case: `folder_id IS NOT NULL AND
folder_id IN (...)`, so both the single-term exclusion and the alternatives branch negate a boolean. Test:
`SearchServiceTests`: a folder named "Rome trip", `persistAsset(isTriaged = true)`, search `-rome` with
`SearchQuery.TRIAGE_VIEW`; a second assertion with `-rome OR nothing` covers the alternatives branch.

### 3. A generic `IN` binds one parameter per ID, so Empty Trash and large folder deletes fail on PostgreSQL

`Query.IN` compiles through `DynamicFilter` to `Columns.isIn` (`dao/sql/Columns.scala:62-64`): `column IN (?, ?, …)`.
The single-parameter form (`Columns.isInSet`, `$inIdSet`) exists and the locking selects use it, but the write that
follows is `updateByQuery(Query.IN(<the rows found>))`. pgjdbc refuses a statement with more than 65,535 parameters.
The bundled SQLite takes 250,000, so the same Library works there.

- `LibraryService.markForPurging` (`:413`): a Trash of 65,535 or more assets cannot be emptied, and fails the same way
  on every retry. `deleteFolderById` (`:262-270`) for a tree holding that many assets or folders, `recycleAssets`
  (`:378`), `moveAssetsToFolder` (`:346`) and `requireAssets` (`:311`) fail the same way, as does
  `PersonService.moveCoversOff` (`:341`, `:347`).
- `BaseDao.getByIds` with 70,000 unknown IDs throws `PreparedStatement can have at most 65,535 parameters`, and so does
  an `updateByQuery` by 70,000 IDs. **Reproduced** (PostgreSQL; both pass on SQLite).
- `WatchFolderDao.targeting` (`dao/jdbc/WatchFolderDao.scala:104-115`) builds its own `IN (?, …)` over a folder's
  whole subtree, reached from the folder Delete dialog. **Reproduced** (PostgreSQL, 70,000 IDs).
- `LibraryServiceRecycleTests` "A selection larger than a statement's parameter limit…" passes because 2 of its 70,000
  IDs exist, so the second statement binds 2.

Fix, one of:

- **In `DynamicFilter`** (recommended): an `IN` whose values are all strings goes through `Columns.isInSet`; `isIn`
  stays for small non-string sets. Every `Query.IN` over IDs is fixed at once, and `getByIds` with it.
- Hand-written `UPDATE … WHERE id $inIdSet` for the four library writes, leaving the generic path as it is.

`targeting` takes `AND dest_folder_id $inIdSet` with `idSet(folderIds)` either way. `deleteFolderById` and
`requeuePurgePending` also read whole `Asset` rows, `extracted_metadata` included, only for their IDs: an ID-only read
goes with this change. Test: `BaseDaoTests` on both engines, `getByIds(amongManyUnknownIds(Set(asset)))` returns the
one asset; `LibraryServiceRecycleTests`, a `Slow` test seeding 66,000 recycled rows and calling `purgeRecycleBin()`.

### 4. A deleted folder keeps its name for good

`folder_02` is unique on `(repository_id, parent_id, name_key)` whatever `is_recycled` says (`postgres/all.sql:165`,
`sqlite/all.sql:398`), and a folder row is never deleted: `deleteFolderById` sets `is_recycled`, and no purge touches
`folder`. Only `ensureChild` (imports) finds and restores a recycled namesake. `FolderService.add`, `rename` and `move`
go straight to the write.

- Add "Trips", delete it, add "Trips" again: `DuplicateException`, "Folder name already exists at this level", though
  the tree shows no such folder. It stays so after the Trash is emptied. Renaming a sibling to the name, or moving a
  folder of that name there, fails the same way. **Reproduced** (both engines).

Fix, one of (a decision):

- **Reuse the recycled namesake** (recommended): `add` restores it as `ensureChild` does; `rename` and `move` onto a
  recycled sibling's name first rename that sibling out of the way (a suffix outside what `validName` accepts), under
  `lockName`. Restores keep working, since the row survives.
- A partial unique index over live folders (`WHERE NOT is_recycled`), plus a rule for a restore that then clashes with
  a live namesake.
- Hard-delete recycled folders that hold nothing when the Trash is emptied; it frees a name only after a purge.

Test: `FolderServiceTests`: `add("Trips")`, `deleteFolderById`, `add("Trips")` returns a live folder.

### 5. A merge into a person merged away moves the Faces onto a deleted person

`PersonService.mergeLocked` (`service/PersonService.scala:167-234`) reads `liveIds` from `lockAndGetLive` and uses it
only to decide the name (`:196`). It never requires the destination to be live. `mergeFacePerson` does, through
`lockedNamelessFacePerson`.

- People X, Y, Z with a Face each. `merge(dest = Y, source = X)`, then `merge(dest = X, source = Z)` from a stale modal
  or a second account: Z's Faces move to X, which is `is_deleted`, and Z is deleted too. Both vanish from the People
  tab, and the Faces stay candidates of the nearest-face search, so later imports vote for the dead person.
  **Reproduced** (both engines).

Fix: in `mergeLocked`, `NotFoundException` when `liveIds` lacks the destination. Add `person.is_deleted = FALSE` to both
nearest-face statements (`dao/postgres/FaceDao.scala`, `dao/sqlite/FaceDao.scala`) as a backstop. Test:
`PersonServiceTests`: the three merges above; the second is refused and no Face belongs to a deleted person.

### 6. Recognition gives a Face to a person it neither locks nor re-reads

`FaceRecognitionService.recognizeFace` (`service/FaceRecognitionService.scala:196-208`) takes the vote's person through
`getPersonById`, which has no liveness filter, and `PersonService.addFace` (`:111-135`) inserts the Face and increments
the count without the person's lock.

- With the person the vote returned, a merge of that person, and then `addFace`: the Face is stored on the deleted
  person and its count becomes 1. **Reproduced** (both engines, in sequence). On PostgreSQL the same happens when a
  merge commits between an import's search and its insert, which is any recognition of the source while a merge of it
  is in flight. **Read**.
- When the concurrent transaction deletes the person instead (`deleteFaceless` after their last Face goes,
  `discardImport`), the insert fails its foreign key and a good file's import is dropped with a raw `PSQLException`.
  **Read**.

Fix: after the vote, lock the person (`lockAndGetLive`) and search again when it is gone or no longer live; the merge
has repointed the Faces by then. Item 7's fix takes the same locks. Test: `FaceRecognitionServiceTests`, PostgreSQL:
thread 1 recognizes and waits, thread 2 merges the person away, thread 1 stores; every Face's person is live.

### 7. An import storing two people's Faces deadlocks with a recycle of an asset both are on

`lockAssetPeople` and `lockAndGetLive` lock people `ORDER BY id FOR UPDATE` (`dao/jdbc/PersonDao.scala:210-237`). An
import's one transaction takes people in detection order: a `FOR KEY SHARE` from each Face insert's foreign key and a
row lock from `increment`, held until the asset's last Face is written.

- People A < B by ID share an asset. An import stores B's Face, then A's; meanwhile `recycleAssets` of the shared asset
  locks A and waits on B. PostgreSQL aborts the import: `ERROR: deadlock detected`, and the file is dropped as failed.
  **Reproduced** (PostgreSQL). A merge or move between the two, `discardImport` and the purge's `moveCoversOff` take
  the same locks.

Fix: `recognizeAndStore` resolves the asset's people first and locks them in ID order before the first insert, which
also closes item 6. The alternative, retrying the asset on SQLState 40P01, leaves the user's request to fail when
PostgreSQL picks it as the victim. Test: `FaceRecognitionServiceTests`, PostgreSQL: the interleaving above with two
latches; neither thread fails.

## P2

### 8. Setup has no guard in its transaction

`SystemService.initializeSystem` (`service/SystemService.scala:51-84`) never reads `system.is_initialized`, and
`SystemMetadataDao.setInitialized` (`dao/jdbc/SystemMetadataDao.scala:39-43`) is an unconditional update whose row
count nobody checks. The only check is the in-memory `App.altitude.isInitialized`, read by the route before the
transaction. `app.setIsInitializedState()` (`:78`) also runs before the commit, so a failed commit leaves the flag set
with no Admin stored, and Setup is refused until a restart.

- On an instance already set up, a second `initializeSystem` with other values creates a second Admin and a second
  Library. **Reproduced** (both engines, at the service). Two concurrent posts to `/htmx/setup`, which needs no login,
  both pass the in-memory check: the second submitter becomes an Admin the first never sees. **Read**.

Fix: the transaction's first statement is `UPDATE system SET is_initialized = TRUE WHERE id = 1 AND is_initialized =
FALSE`, refused on 0 rows (as `setSigningKey` already does for its column); the in-memory flag is set in
`afterCommit`. Test: `SystemServiceTests`: a second `initializeSystem` throws and adds no account.

### 9. A large read statement timeout breaks every read transaction on PostgreSQL

`TransactionManager.beginPostgresRead` (`transactions/TransactionManager.scala:156`) renders
`dbPostgresReadStatementTimeout * 1000` in `Int`, and the Setting is `Integer(1)` with no upper bound
(`models/Settings.scala:460-466`).

- Saved as 2147484, every `asReadOnly` fails: `-2147483296 ms is outside the valid range for parameter
  "statement_timeout"`. The login lookup and the Settings page are reads, so nobody can log in and the page that would
  undo it does not load. **Reproduced** (PostgreSQL). A value that wraps positive (4,294,968 s becomes 704 ms) turns
  searches into 503s. **Read**.

Fix: `Integer(1, 2147483)` on the Setting and the product computed as `Long`. Test: `SettingsServiceTests`: the value
above is a `ValidationException`; `TransactionManagerTests`: the maximum reads back from `SHOW statement_timeout`.

### 10. Moving a recycled asset whose content is live again is a raw 500

`LibraryService.moveAssetsToFolder` (`:336-352`) restores recycled assets in one `updateByQuery` and never looks for a
live copy. `restoreRecycledAssets` does (`getByChecksum`, `:289`), because `asset_01` is unique on
`(repository_id, checksum)` over live assets.

- Recycle an asset, import the same file again, move the recycled one from the Trash into a folder: PostgreSQL 23505
  on `asset_01`, SQLite `SQLITE_CONSTRAINT_UNIQUE`, as a bare `SQLException` out of the service. **Reproduced** (both
  engines).

Fix: the move treats its recycled rows as the restore does, one at a time, and reports the duplicates it skipped (or
refuses the move with an `IllegalOperationException` naming them). Test: `LibraryServiceRestoreTests`: the setup of
"Restore skips an asset whose content is live again…", then `moveAssetsToFolder`.

### 11. A purge leaves people with no Face, and the Change combobox lists them

The purge moves covers off and the asset's delete cascades its Faces. Nothing calls `deletePeopleWithoutFaces`;
`deleteFaceless` is reached only from `discardImport` and `settleAfterFaceLeft`. `PersonDao.getAssignable`
(`dao/jdbc/PersonDao.scala:178-186`) has no Face test.

- An unnamed person whose only asset is recycled and purged stays `is_deleted = FALSE` with no Face, and
  `getAssignable` returns it. Each such "Unknown N" stays in the combobox; a named one keeps its name under
  `person_01`. **Reproduced** (both engines).

Fix: the purge's asset delete calls `deletePeopleWithoutFaces` for the asset's people (the unnamed ones, if a named
person is meant to keep its name: a decision). Test: `PurgePipelineServiceTests`: one unnamed person, one Face, recycle
and purge; the person row is gone.

### 12. A folder can be added or moved under a recycled folder

`FolderService.add` (`:26`) and `move` (`:168`) read the parent with `getById`, which returns recycled folders, and
check nothing. Neither takes `lockTree`, so on PostgreSQL a `deleteFolderById` that has read the subtree does not
recycle a folder that arrives meanwhile.

- Delete folder A, then move B into A, or add a child to A, from a stale dialog: both succeed. B is live, its assets
  are counted, and no tree, picker or folder-scoped navigation reaches it. **Reproduced** (both engines).

Fix: with item 1's `lockTree`, refuse a recycled parent or destination in `add` and `move`. Test: `FolderServiceTests`:
both calls throw `IllegalOperationException`.

### 13. Two concurrent adds of one asset to an album fail on PostgreSQL

`AlbumDao.addAssets` (`dao/jdbc/AlbumDao.scala:64-76`) and `LocationDao.addAssets` (`dao/jdbc/LocationDao.scala:93-105`)
skip members with `NOT EXISTS`, which cannot see another transaction's uncommitted row, and have no `ON CONFLICT`.

- A double submit: the second insert waits on `album_asset_01`, then fails with 23505 as a raw `SQLException`, and adds
  none of its other assets. **Reproduced** (PostgreSQL, album). The Location statement has the same shape. **Read**.

Fix: `ON CONFLICT DO NOTHING` on both insert-selects; both engines accept it after the `WHERE`, and the returned count
stays the number that were new. Test: `AlbumServiceTests` and `LocationServiceTests`: `contend` two adds of one asset;
neither throws.

### 14. Four more pairs of paths take the same rows in opposite orders

Each is the shape of item 7 and aborts one side with 40P01 on PostgreSQL. All **Read**.

- **Sequence writers against library operations.** The dated candidates are locked `FOR UPDATE` with no `ORDER BY`
  (`dao/jdbc/AssetDao.scala:369-378`), `repointSequences` (`:592-600`) locks members in scan order, and
  `CurationService` locks in two passes. Recycle, move, restore and purge lock assets in ID order and do not take
  `lockSequences`. Only `liveSequenceMembersStatement` (`:567`) is ID-ordered, as its scaladoc says. An import
  completing a burst's frame while two earlier frames are recycled from Triage is the case.
- **An account write against an Edit save.** `UserService` takes `FOR UPDATE` on the active Admins and the target, then
  updates assets (`rewriteImporterRecords`, `reassignOwner`). An Edit save locks the asset, then its
  `updated_by_account_id` key needs `FOR KEY SHARE` on the editor's account.
- **An account write against a Watch folder edit by another Admin.** `pauseOwnedBy` and `reassignOwner` follow the
  Admins' locks; `WatchFolderService.update` and `resume` write the `watch_folder` row first, and a changed
  `account_id` then needs the editor's account.
- **A scan against an edit or removal of its Watch folder.** `WatchFolderService.remember` (`:751-789`) writes
  `watch_folder_file` rows first and the `watch_folder` row last (`dao.scanned`, count ignored); `update` and `remove`
  lock the `watch_folder` row first. On both engines, a Watch folder removed while its scan walks makes `addAll` fail
  its foreign key, and the scan ends as a failure with a stack trace instead of `Blocked(REMOVED)`.

Fix: one order per pair. The candidates statement gets `ORDER BY id` and a merge locks its members through an
ID-ordered select; the Edit save and the Watch folder edit take `UserService.lockImporter` on the acting account before
their first write, as the import does; `remember` runs `dao.scanned(id, now)` first and treats 0 rows as removed.
`lockActiveAdmins` as `FOR NO KEY UPDATE` still serializes Admin writes and stops blocking every foreign key check
against an Admin. Tests: one two-thread PostgreSQL test per pair, in the suite of the service that changes.

### 15. PostgreSQL's schema script drops `public` whenever `system` is not visible

`MigrationService.version` (`service/MigrationService.scala:56-60`) reads any 42P01 on `system` as a fresh database,
and `postgres/all.sql:1` is `DROP SCHEMA IF EXISTS public CASCADE`. **Read**.

- A URL pointing at another application's database, a role whose `search_path` no longer leads with `public`, or a
  restore that lost `system`: the next start destroys everything in `public`. SQLite's script drops nothing.

Fix: remove the `DROP SCHEMA` and `CREATE SCHEMA` lines (`TestDatabase.wipe` drops the schema itself), so a `public`
that holds tables fails the first `CREATE TABLE`; or ask `to_regclass('public.system')` and refuse a non-empty
`public`. Record the item in `docs/READINESS.md`. Test: `StartupTests`, PostgreSQL: a foreign table in `public` is
still there after a start that refuses.

### 16. A failed statement's exception carries every bind value, password hashes included

`QueryRunner` rethrows with ` Query: … Parameters: [...]`, and `collectBySqlQuery` (`dao/jdbc/BaseDao.scala:319`) does
the same by hand. `addRecord` and `updateByBySql` also log `with values: $values` at TRACE (`:274`, `:346`).

- A second `UserDao.addUser` of one account throws an `SQLException` whose message holds the bcrypt hash, the email
  address and the username. **Reproduced** (both engines). Any account write that fails for another reason (a deadlock
  of item 14, a dropped connection) reaches cask's stderr or an ERROR line with it; `ALTITUDE_LOG_LEVEL=TRACE` writes
  every new hash. `setSigningKey` already keeps its key out of the TRACE line. **Read**.

Fix: the raw helpers of `BaseDao` rethrow with the statement, SQLState and error code but no parameters, and the TRACE
lines print the parameter count. A caller that reads the driver's message (`whileKeysFree`) keeps `getNextException`.
The trace lines also build their string with TRACE off, an allocation per statement for a large `idSet` or an
embedding: a guard (`isTraceEnabled`) goes with this. Test: `UserServiceTests` with `LoggedLine`: a failed insert's
message and log lines hold no hash.

### 17. The sqlite-vector path is spliced into SQL unescaped

`TransactionManager.setUpSqliteConnection` (`:368`) runs `s"SELECT load_extension('$vectorExtensionPath')"`. **Read**.

- A native jar unpacks into the per-user cache under the home folder. A home such as `C:\Users\O'Brien` makes every
  SQLite connection's setup a syntax error, and the instance does not start.

Fix: `SELECT load_extension(?)` on a `PreparedStatement`. Test: `TransactionManagerTests`: the extension copied into a
scratch folder named `o'brien`, `altitude.native.dir` set to it, a connection opens.

### 18. A SQLite writer waiting 30 seconds for the write connection fails

`poolConfig` (`transactions/TransactionManager.scala:311-316`) sets no `connectionTimeout`, so HikariCP's 30 seconds
applies to the write pool of one. The guide says the single write connection queues writers instead of failing them.
**Read**.

- A long write (a folder delete over tens of thousands of assets, an account rename's `rewriteImporterRecords`) while
  import stages queue behind it: a stage waiting past 30 seconds drops its asset with a connection-timeout cause, and a
  request is a 500. A pool-exhausted request on PostgreSQL fails the same way.

Fix: a deliberate `connectionTimeout` on the SQLite write pool, tied to the longest legitimate write (a config key with
a default of minutes), and the guide's sentence states the bound. Test: `TransactionManagerTests`, `Slow`: a writer
held past 30 seconds, a second writer still gets the connection.

### 19. Every hand-written statement costs a second round trip on PostgreSQL

`new QueryRunner()` (`dao/jdbc/BaseDao.scala:167`) leaves `pmdKnownBroken` false, so commons-dbutils' `fillStatement`
calls `getParameterMetaData` on every statement, which pgjdbc answers with a describe-only query. Known from both
libraries' code; the cost is not measured. **Read**.

Fix: measure first (count `getParameterMetaData` calls per DAO call through a connection proxy, as
`ExplainingConnection` wraps one). If it holds, one bind helper in `BaseDao`: `setObject` for values and
`setNull(i, Types.NULL)` for nulls. `new QueryRunner(true)` is no drop-in: it binds every NULL as `VARCHAR`, which
PostgreSQL refuses for a timestamp, integer or vector column.

## P3

### 20. The schema and its version are two commits, and the engines seed different versions

`MigrationService.migrate` (`:76-80`) commits `all.sql`, then stamps the version in a second transaction. The seed is 1
on PostgreSQL (`all.sql:27`) and 0 on SQLite (`all.sql:14`); the current version is 2. A stop between the commits
leaves PostgreSQL at 1 (the next start asks for a `2.sql` that does not exist and throws a `NullPointerException`) and
SQLite at 0 (the script runs again and fails on `table system already exists`). **Read**. Fix: the script and the stamp
under one `withTransaction` (DDL is transactional on both engines), one seed value, and a clear failure for a missing
`<version>.sql`.

### 21. A taken email address is reported as a taken username on PostgreSQL

`UserService.whileKeysFree` (`:640-651`) tests the driver's message for `username_key` or `account_02`, and
PostgreSQL's message quotes the offending value. An email address containing either text puts the error on the
username. **Reproduced** (PostgreSQL, `username_key…@example.com`). Fix: read
`PSQLException.getServerErrorMessage.getConstraint`; match SQLite's `account.<column>` text exactly.

### 22. An ID padded with spaces finds its row on PostgreSQL

IDs are `CHAR(36)`, and a `varchar` parameter compares as `bpchar`, which ignores trailing spaces.
`RepositoryDao.getById(id + " ")` returns the Library. **Reproduced** (PostgreSQL; SQLite finds nothing).
`repoContext` runs before the login, so each padded `/r/<id>%20/` costs a query and an entry in the never-cleared
`repositoriesById`. No access is gained: the gate compares the stored ID. Fix: `setContextFromRequest` refuses a
`repoId` that fails `Validators.isUuid` before any lookup.

### 23. `area_size` overflows for an image over 2.1 gigapixels

`asset.width * asset.height` in `Int` (`dao/jdbc/AssetDao.scala:194`) into an `INT` column, a sort field. A 50,000 ×
50,000 image is stored as -1,794,967,296. **Reproduced** (both engines). Fix: the product as `Long`, the column
`BIGINT` on PostgreSQL.

### 24. Two folder reads count wrong

- `FolderDao.getChildrenRecursive` (`:178-182`): the anchor has no `id <> parent_id`, and the root is its own parent,
  so for the root it returns the root itself and every folder twice. **Reproduced** (both engines). Callers build
  sets; `LibraryService.query` with the root as `folder_id` reaches it.
- `FolderDao.getChildren` (`:108-112`) counts recycled children in `num_of_children`; `getById` does not.
  **Reproduced** (both engines). No production caller reads it.

Fix: `AND id <> parent_id` in the anchor; the `is_recycled` filter in the count, or the unused read removed.

### 25. `updated_at` is written where the guide says it is not, and not where a model reads it

- `UserDao.rememberLastActiveRepository` (`:173-180`) sets `account.updated_at`, which the guide calls the stamp of an
  account change and `recordLogin` leaves alone on purpose. **Reproduced** (both engines). Fix: drop the column from
  the statement.
- `Album.updatedAt` is carried by the model and both mappers and never written: a rename writes the name alone. On
  every table but `account`, `watch_folder` and `asset_edit` the column stays NULL. **Read**. Fix: stamp it in the
  rename, or drop the field.

### 26. The user-metadata write path, which no route calls yet

Nothing under `routes/` calls `UserMetadataService`'s writes or sets `metadataFilters`; tests do. The defects become
live with a metadata editor.

- A BOOL value set again leaves the old `metadata_parameter` row: after "true" then "false" a filter by true still
  finds the asset, and two rows remain. **Reproduced** (both engines). `SearchDao.addMetadataValue`
  (`dao/jdbc/SearchDao.scala:356-358`) only inserts.
- `metadataFilter` (`dao/sql/search/SearchQueries.scala:1313-1322`) counts surviving rows, not distinct fields, so two
  rows of one field satisfy a two-filter search. **Read**.
- Deleting a field leaves its values in `asset.user_metadata` and their words in the search document;
  `toJson(getMetadata(asset))` then throws `NoSuchElementException`. **Reproduced** (both engines).
- `addMetadataValue` indexes the raw value, not the cleaned one that is stored; a BOOL `" true "` passes validation
  and then throws in `asBoolean`. `setMetadata` and `updateMetadata` write the column without touching the index,
  against the guide's sentence on `writeDocument`. **Read**.
- `AssetDao.updateMetadata` (`:619-626`) merges in Scala from an unlocked read, so two concurrent edits of one asset
  lose a value on PostgreSQL while its parameter row stays. **Read**.
- `addField` lets a racing duplicate out as a raw `SQLException`; NUMBER values go through `Double`, which accepts
  `NaN` and rounds past 15 digits; a value has no length cap, and PostgreSQL's tsvector fails past 1 MB. **Read**.

Fix, when the editor is built: single-valued types replace their rows, the filter counts `DISTINCT field_id`, a field
delete strips its key from the assets and reindexes them (or is refused while it holds values), the index is written
from the stored value, the column is read `FOR NO KEY UPDATE`, and `clean` caps a value's length.

### 27. Person and Face statements without `repository_id`

`PersonDao.addFacesOfAssets` (`:197-207`: the `UPDATE person` and both subqueries), `deleteFaceless`' `DELETE` (`:253`),
the `lockAssetPeople` subquery (`:217`) and `FaceDao`'s `mine`/`theirs` subqueries (`:117-118`). The guide allows
exactly six unscoped statements. Every current caller passes IDs read scoped, so nothing crosses a Library today.
**Read**. Fix: `repository_id = ?` in each; `RepositoryIsolationTests` calls `recycleFacesForAssets` with another
Library's asset and its counts stay.

### 28. Prepared statements never closed

`FaceDao.add` on both engines (`dao/postgres/FaceDao.scala:69-91`, `dao/sqlite/FaceDao.scala:34-56`), which also skips
the write-query count, and `SearchDao.addParameters` (`dao/jdbc/SearchDao.scala:367`). Each stays open until the
connection returns to the pool. `MigrationService.executeCommand` closes outside a `finally`. **Read**. Fix: `try` /
`finally close()`, or `FaceDao.add` through `addRecord` with `vectorPlaceholder`, which removes the two near-identical
methods.

### 29. Unnamed people stop sorting numerically at 10,000

`getPersonSortName` pads the label with `%04d` (`dao/jdbc/PersonDao.scala:87-89`), and the label sequence is
instance-wide and burnt by discarded imports. "unknown 10000" sorts before "unknown 2000". **Read**. Fix: ten digits.

### 30. Watch folder and Import edges

All **Read**.

- `is_paused` and `status_reason` are written from reads made in an earlier transaction: a scan's `setStatusReason` has
  no `is_paused` guard and can clear "its owner was deactivated"; `update` re-pauses over a concurrent `resume`;
  `pauseFor` pauses for an owner an Admin has since replaced.
- `WatchFolderFileDao.addAll` (`:74-98`) is one multi-row insert against `watch_folder_file_01`. Two files whose names
  decode to one string (bytes invalid in the JVM's file-name encoding) fail the whole scan, every round. Fix: one
  `Found` per `relPath`, or `ON CONFLICT DO NOTHING`.
- A `rel_path` or Watch folder `path` over about 2,700 bytes passes no check and exceeds PostgreSQL's btree row limit;
  that scan's insert fails every round. Fix: a byte cap, skipped and reported.
- A NUL in an upload's `relPath` or an Import's name is a 500 on PostgreSQL and is stored on SQLite. Fix:
  `RelPath.segments` and the name check refuse control characters.
- A scan materializes a Watch folder's whole memory through `manyBySqlQuery` and writes one `UPDATE` per changed file
  in one transaction. Fix: `collectBySqlQuery` straight into the map.

### 31. Search edges

All **Read**.

- A cursor whose group key or sort value is edited reaches `LocalDate.parse` (`SearchQueries.scala:252`, `:578`) or
  the statement unvalidated: a 500, where a bad cursor is a 400. Fix: parse in one place that throws
  `SearchCursorException`, and check the sort value's type against the sort.
- The map's Location-member subqueries (`:1489-1495`) are filtered by the box alone, so their cost follows the
  instance, not the Library. Results are right, since the outer asset is scoped. Fix: `location.repository_id = ?`.
- `SearchWords` lower-cases with `Locale.ROOT`, which keeps a word-final capital sigma as `ς`; FTS5 folds it to `σ` and
  PostgreSQL's `simple` does not. A two-engine test settles it.

### 32. Text orders by the database locale on PostgreSQL and by bytes on SQLite

Only ID and checksum columns are `COLLATE "C"`. On the test database (`en_US.utf8`), `'Zebra.jpg' < 'apple.jpg'` is
false; SQLite's byte order says true. A filename sort and every name-ordered list (folders, albums, Locations, People,
Watch folders) can differ between engines, and a glibc upgrade that changes a locale's order invalidates the btree
indexes on the key columns. **Read**, with the comparison run on the test database. Fix: `COLLATE "C"` on the key
columns in PostgreSQL's `all.sql` (they are case-folded already), and one rule for the filename sort, such as a stored
filename key.

### 33. Schema gaps between the engines

All **Read**.

| Object | PostgreSQL | SQLite |
|---|---|---|
| `metadata_parameter.asset_id`, `field_id` | nullable, so a NULL skips the composite key | `NOT NULL`, as `MetadataParameterRow` types them |
| `repository.file_store_config` | nullable | `NOT NULL` |
| `face_01` | `INCLUDE (person_id)` | no `person_id`, so the per-asset person probe reads the table |
| `created_at` defaults | microseconds, transaction start | whole seconds, statement time, nullable |
| `VARCHAR(n)` lengths, boolean `CHECK`s | enforced | not enforced |

- `import.account_id` (`ON DELETE SET NULL`) has no index, so an account delete scans `import` under the Admins' row
  locks; `watch_folder.account_id` and `account.created_by_account_id` have none either, on small tables.
- `UserDao.now` and `AssetEditDao.now` do not truncate to microseconds as the other DAOs do, so SQLite holds nine
  digits where PostgreSQL holds six; `ImportDao.add` and `AuditEventDao.add` return a `createdAt` with nanoseconds that
  a later read does not have.
- `RowColumnTests` compares column names only, and `TestContext.SCOPED_TABLES` is kept by hand.

Fix: `NOT NULL` on the two PostgreSQL columns, one nullability for `file_store_config`, `person_id` as a trailing
column of SQLite's `face_01`, a partial index on `import (account_id)`, truncation in the two `now`s, and
`RowColumnTests` extended to nullability and index names.

### 34. An instant read on PostgreSQL is an hour off in the autumn overlap hour

A `timestamptz` becomes a zone-less `LocalDateTime` in the JVM zone (`dao/postgres/PostgresOverrides.scala:49-50`, `:90`), and
`instantOf` (`:52`) resolves the repeated hour to the earlier offset. With the JVM in a zone that has DST, an account
created or a file first seen in that hour comes back an hour early; a Watch folder file then counts as rested at once.
The image runs in UTC and SQLite stores UTC. **Read**. Fix: models carry instants as `Instant` or `OffsetDateTime`, or
the PostgreSQL conversion is pinned to UTC as SQLite's is.

### 35. Latent traps with no current trigger

All **Read**, except where noted.

- `RequestContext`'s repository, account, permissions and grants are `DynamicVariable`s, which pooled threads inherit
  from whichever request thread created them. Work on such a thread that forgets to bind reads a stale Library instead
  of throwing. `conn` is a `ThreadVariable` for this reason; `withContext` does not bind `grants`.
- `BaseDao.queryRecords`' paging: a page past the end reports `total = 0`, a page without a sort has no order, and no
  sort has an ID tiebreaker. `QueryResult.empty` has `total = 1`; `loadCsv[T]` casts strings to `T`. No caller pages
  or uses the helpers: remove them, or make paging require a sort and append the ID.
- SQLite's `nativeBool` binds the texts "1" and "0", correct only against a column with numeric affinity, and never
  usable by a partial index; `getBooleanField` answers false for anything but an Integer 0 or 1 instead of throwing.
- `AssetService.markAsCompleted` (`:94`) drops the row count the guide says `requireFound` checks.
- `RepositoryService.grantNewLibrary` locks the creator's row and re-checks neither that it is active nor that it may
  still create Libraries; `UserService.create` does both.
- `PersonService.updateName` takes no `lockName`, so a move-to-name racing a rename can fail on `person_01` as a raw
  `SQLException`.
- An add to an album or Location racing a recycle of the asset can leave the recycled asset a member, and the count one
  above the grid.
- SQLite connection setup: `PRAGMA optimize` runs on read connections before `query_only`, outside the one write
  connection, and a read connection opened while a fresh database's schema is created can miss `vector_init` for up to
  half an hour. Neither was run.
- `TransactionManager.afterCommit` outside a transaction runs the action inline, and its exception reaches the caller;
  the scaladoc says a throwing action never does. **Reproduced**.

### 36. Text that contradicts the code

- `altitude/AGENTS.md`, **DAO / Service Layer**: `$inIdSet` and `Columns.isInSet` are said to cover any set size, while
  `Query.IN` and `getByIds` bind per ID (item 3); "exactly six statements" reach a table without its Library (item 27).
- `altitude/AGENTS.md`, **Connections and transactions**: the write connection "queues writers … instead of failing
  them" (item 18).
- `altitude/AGENTS.md`, the search document's writers name `UserMetadataService`'s edits, of which two do not index
  (item 26).
- `PersonService.scala:308` and `docs/faces.md` on who is left faceless and until when (item 11).
- `dao/SearchDao.scala:26`: "A counted page also says where the weaker matches begin"; any page that crosses the line
  does.

Each is corrected with the item it belongs to, or the text alone where the code is right.

## Checked and cleared

- SQLite's `DEFAULT (datetime('now', 'utc'))` on the `created_at` columns: on the bundled SQLite 3.51.2 it equals
  `datetime('now')` with the JVM in a zone five hours off UTC, so the defaults are UTC. (SQLite 3.45 shifts the value
  by the zone; the modifier is redundant and can go.)
- SQLite's parameter limit: the bundled build takes a statement of 70,000 parameters.
- Location groups' path key (`category || U+0001 || location`) orders as the pair on the test database's
  `en_US.utf8`.
- Repository scoping of every hand-written statement but those of item 27, both arms of each recursive statement, and
  the unscoped subqueries of the search, each intersected with a scoped asset.
- Bind order and count of every hand-written statement; `makeModel`, `toModel`, the Row tables and both schemas column
  by column (the one mismatch is `MetadataParameterRow`, item 33); the schema guards of the guide on all 17 scoped
  tables; index spellings against `jsonText`, `dayOf` and the partial-index predicates.
- Keyset paging of every search order, for both null placements and directions; totals against rows; the FTS5 triggers.
- `TransactionManager`: the connection binding and after-commit list on every exit path, a failed setup closing its
  connection, a write nested in a read failing on both engines, the vector-search settings nested.
- The Import counters (`addPending`, `fileSettled`, `markComplete`, `settle`) under concurrent settles, the stats
  writes, the Edit's insert-or-update under the asset lock, the account lock helpers' ID order, the grant bound under
  the account's row lock.

## Decisions to make

- **Item 3**: the fix in `DynamicFilter` (recommended) or hand-written updates for the library writes.
- **Item 4**: reuse the recycled namesake (recommended), a partial unique index, or hard-deleting empty recycled
  folders at purge.
- **Item 7 and 14**: lock ordering (recommended), or a retry of the whole transaction on SQLState 40P01 in
  `TransactionManager`, which covers pairs not yet found but reruns service code that must then be free of side effects
  outside the database.
- **Item 11**: whether a named person with no Face survives a purge.
- **Item 15**: remove the `DROP SCHEMA`, or keep it behind an explicit emptiness check.

## Verification

Each fix starts from its red test, on the engine named: `make test-focused-sqlite`, and `make test-focused-psql`
against the test container for every PostgreSQL item, since SQLite's one write connection hides each of them. A
concurrency guard is shown red on PostgreSQL before its fix. Then `make test`; `make test-with-slow` once for item 3's
seeded test. Items 3, 14, 15, 18, 26 and 27 change `altitude/AGENTS.md` in the same change, item 11 `docs/faces.md`,
and items 8, 15 and 16 `docs/READINESS.md`.
