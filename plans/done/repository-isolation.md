# Repository isolation: every read and write by ID stays in the context repository

## Goals

- A row of a repository-scoped table (`asset`, `folder`, `album`, `album_asset`, `location`, `location_asset`, `person`,
  `face`, `metadata_field`, `metadata_parameter`, `stats`, `search_document`) is read, changed or deleted only through its own
  repository's context. An ID of another repository's row behaves exactly as an ID that does not exist: not found, a 404 at the
  route, nothing changed.
- Scoping is the default: a query with no repository is something a DAO opts out of (the tables that have no `repository_id`),
  never something each caller has to remember.
- A reference written into a row (a folder's parent, an asset's folder, a person's cover Face) points into the same repository,
  and the database refuses one that does not.
- A write by ID that matches no row of the context repository is `NotFoundException`, never a silent no-op.
- Tests prove all of these, on both engines, for every ID-taking path a route reaches.

Out of scope: share links, roles per repository, a second account type, the WebSocket, and login gating of routes (the
table-driven route test in D checks it, but it fixes nothing there).

## How it works

`BaseDao` scopes itself (`repositoryScoped`, true by default): the generic reads and writes add `repository_id` from
`RequestContext.getRepository`, which throws when the context has no repository. The repository context is set by
`decorators.repoContext` for a request, by `PipelineUtils.withContext` for a pipeline stage, by
`LibraryService.forEachRepository` for startup work, and by `RepositoryService.switchContextToRepository` and `SystemService`
elsewhere; `requireLogin` answers 404 for a repository in the path that the user does not own. Hand-written SQL on a scoped
table carries `repository_id = ?` itself. `BaseService` turns a write by ID that changed nothing into `NotFoundException`, and
a route turns that into a 404 through `BaseController.answering` (plain text) or `answeringJson` (JSON). The schema backs it:
every reference between scoped rows but a person's cover is a foreign key over `(repository_id, <ref>_id)`.
`altitude/AGENTS.md` (DAO / Service Layer: "Repository scoping", "Schema guards") and `docs/faces.md` (covers) hold the
reference wording.

## Decisions

| Concern | Decision |
|---|---|
| Where scoping lives | `BaseDao`, by default: scoping only in `BaseService` would miss the DAO calls services make directly (`PersonService.merge`'s Face update, `peopleByIds`) |
| Tables with no `repository_id` | `RepositoryDao`, `UserDao`, `SettingDao` and `SystemMetadataDao` opt out (`repositoryScoped = false`); `UserService` and `RepositoryService` use the inherited `BaseService.query` |
| No repository in the context on a scoped DAO | An exception: refusing is the safe failure |
| `Query` | Carries no repository and has no `withRepository()`; no caller adds the repository |
| An empty `updateByQuery` or `deleteByQuery` | Refused in `BaseDao` for every DAO, scoped or not (`IllegalArgumentException`), since it would change every row |
| Another repository's ID | `NotFoundException`, a 404 at the route, the same answer as an unknown ID, naming nothing of the other repository |
| A route that reads by ID | Runs inside `answering`, or `answeringJson` for a JSON route (a JSON 404 for not found, a JSON 400 for `IllegalOperationException` and `ValidationException`; `whyInvalid` is shared with `answering`). The search route and the content route catch `NotFoundException` themselves (plain-text 404) |
| Reads by ID | The scoped `getById` serves everywhere: Person, Face and Folder have no `getInRepository` (Folder's `getById` also fills `numOfChildren`) |
| Hand-written SQL by ID on a scoped table | Carries `repository_id = ?`, in both arms of a recursive statement |
| A destination or parent folder | Read through the scoped `getById` in the transaction of the write. A foreign or unknown destination of an asset move is a 404, of a folder move a 400 (a destination that does not exist); a foreign or unknown parent in the add-folder dialog is a `PARENT_ID` field error (`PARENT_FOLDER_NOT_FOUND`) |
| A cover Face | `setFaceAsCover(personId, faceId)` locks the Person and answers them re-read: a Person or Face not in the repository is `NotFoundException`, another Person's Face or one on an asset marked for purging `ValidationException` |
| A cover whose Face is deleted | Never left dangling: every path that deletes a cover Face moves the cover first to the Person's best Face on an asset not marked for purging, or to none. `person.cover_face_id` is a single-column `REFERENCES face (id) ON DELETE SET NULL` that only backs that up: a composite key's `SET NULL` would clear `repository_id` too, so the cover's repository is checked in code. A Person with no cover shows a placeholder |
| A write by ID that matches nothing | `BaseService.updateById`, `deleteById` and `increment` throw `NotFoundException` on a count of 0. `BaseDao` writes return the count; a service calling one directly checks it through `BaseService.requireFound` |
| The schema | Every reference held in one repository, on both engines (D15) |
| A constraint violation on SQLite | Error code 19 for every constraint, which `Util.newDuplicateExceptionOrRethrow` reads as a duplicate, so a write is checked in code before the database would refuse it |

## Constraints

- TDD: each change starts from a failing integration or controller test. The service tests run on both engines
  (`make test-sqlite`, `make test-psql`), and the controller tests on SQLite.
- Schema changes go into `all.sql` (both engines) only, with no versioned migration and no `schemaVersion` bump. The database
  is recreated from scratch.
- No change for a caller that uses its own repository's IDs: every existing suite passes. A fixture changes only where it
  writes a reference to a row that does not exist, which the foreign keys of D15 refuse.
- Subagents edit disjoint files and never run mill; the parent compiles and tests.

## Changes

### A. Scoped by default in `BaseDao`

1. **Red: the generic paths with two repositories.** `RepositoryIsolationTests` (integration, both engines), with a row of
   each scoped table in a second repository: `getById`, `getByIds`, `getOneByQuery`, `query`, `updateById`, `updateByQuery`,
   `deleteById`, `deleteByQuery` and `increment`, called from the first repository with the second's IDs, find nothing and
   change nothing (`TestContext.rowsOf` over `TestContext.SCOPED_TABLES` compares whole repositories). An empty
   `updateByQuery` or `deleteByQuery` is refused and changes no row. Each scoped DAO without a repository in the context
   throws. Through `BaseService`, `updateById`, `deleteById` and `increment` with another repository's ID, or an unknown one,
   are `NotFoundException`.
2. **Green: `BaseDao` scopes itself.** `repositoryScoped: Boolean` (true by default, false in the four DAOs above). When
   true, `matching`, the `deleteByQuery` and `updateByQuery` filters, and `increment` add `repository_id` from the context;
   `updateByQuery` and `deleteByQuery` refuse a query with no parameters. `increment` returns the count it changed.
   `FolderDao.getById`'s hand-written statement carries `AND f.repository_id = ?`. `BaseService.updateById`, `deleteById` and
   `increment` throw `NotFoundException` on a count of 0; `BaseService.requireFound` (on `object BaseService`) does the same
   for a direct DAO write.
3. **Refactor.** `Query` has no `withRepository()`: `BaseService`, `AssetDao`, `FolderService.getAll`, `LocationDao`,
   `UserMetadataService` and `StatsService` pass their queries as they are. `AlbumService` and `LocationService` use the plain
   `BaseService.getById`; `AlbumService` has no `deleteById` override, and `LocationService.deleteById` only moves a
   category's Locations to the top level before `BaseService.deleteById`. `UserService` and `RepositoryService` have no
   `query` override. `DeletePurgedFromDBFlow` logs a `NotFoundException` (the row is already gone) at debug.

### B. The paths outside the generic methods

4. **Red: albums and Locations.** Another repository's album: delete is `NotFoundException`, and its row and memberships stay;
   removing assets from it is `NotFoundException`, and its memberships stay; `getAssetIds` returns nothing. Another
   repository's Location is not emptied.
5. **Green: albums and Locations.** `AlbumService.removeAssets` reads the album through `getById` first; `AlbumDao.removeAssets`
   and `getAssetIds`, and `LocationDao.removeAssets`, carry `repository_id = ?`.
6. **Red: folders.** Another repository's folder can be neither renamed nor moved, its root folder cannot be moved, neither
   can be the destination of a move, and `getChildren`, `getChildrenRecursive` and `getAncestors` return nothing for it.
   Deleting it is `NotFoundException`.
7. **Green: folders.** The three recursive statements of `FolderDao` carry `repository_id = ?` in both arms of the
   recursion. `LibraryService.deleteFolderById` reads through `getById`. `isRootFolder` compares with the context
   repository's root: a foreign root is not found before it is compared.
8. **Red: people and Faces.** Another repository's Person: read by ID is `NotFoundException`; hide, show, mark as bad
   match, set cover and rename are `NotFoundException` and change nothing; merging with them, as source or as destination, is
   `NotFoundException`, and their row and Faces stay. `getPersonFaces` returns nothing for them and leaves out Faces on an
   asset marked for purging. Setting a cover to another Person's Face, to another repository's Face, or to one on an asset
   marked for purging, is refused and the cover stays. On both engines:
   - Purging an asset that holds a Person's cover gives them their best Face on an asset not marked for purging, and the old
     cover's files go. With no such Face, their cover is NULL.
   - An asset whose covers cannot be moved stays whole and purge-pending, and is purged once queued again.
   - Discarding a cut-off import whose Face had become an existing Person's cover does the same as a purge.
   - A purge of several assets that hold a Person's best Faces repicks onto none of them.
   - A named destination with no cover takes the merged source's cover, or their best Face when that cover is about to go.
   - A Person with no cover renders a placeholder in the People tab, the results title and the merge modal (controller
     tests).
9. **Green: people and Faces.** `FaceDao.getTopFaces` and `getBestCoverCandidate(personId, excludedAssetIds)` share one
   statement: the Person's Faces in the context repository, on none of the excluded assets and on no asset marked for purging
   (`is_purged`), by detection score then ID. `setFaceAsCover(personId, faceId)` as in Decisions; `addFace` writes the first
   Face's cover directly. Covers off deleted Faces:
   - `PersonService.moveCoversOff(assetIds)`, in one transaction, locks the people with a Face on the assets
     (`PersonDao.lockAssetPeople`) and sets the cover of each Person, live or not, whose cover is a Face on one of them to
     `getBestCoverCandidate(person, assetIds)`, or NULL.
   - `DeletePersonFilesFlow` calls it first, then deletes every Face file of the asset; an asset whose covers cannot be moved
     is dropped from the rest of the purge, stays purge-pending, and `requeuePurgePending` retries it. `discardImport` calls it
     in its transaction, before the asset's row goes. `purgeFaceFiles(faces)` keeps no cover's files.
   - `settleAfterFaceLeft` (Remove, Move) picks with `getBestCoverCandidate`, or NULL; the merge's `keepOneFacePerVideo`
     moves a cover on a lost Face to the survivor. `removeFace` reads the Person before deleting the Face, since the cover's
     key clears a cover on it.
   - `mergeLocked` gives a destination with no cover the source's surviving cover, when it is the destination's and not on an
     asset marked for purging, or their best candidate, written after the source is soft-deleted (`person_02`).
   - `people_list`, `results_title_inner` and `merge_people_modal` render `.no-cover` (`fa-user`, `core.css`) when
     `coverFaceId` is empty; the style guide and `altitude/views/AGENTS.md` show it.
10. **Red, then green: metadata.** `UserMetadataService.getFieldById` and `deleteFieldById` are scoped by A2.
    `AssetDao.getUserMetadata` carries `repository_id = ?`, returns `UserMetadata` (empty when the asset has none) and throws
    `NotFoundException`; `setUserMetadata` and `updateMetadata` return their count, which `setMetadata`, `updateMetadata` and
    `deleteFieldById` check through `requireFound`. Tests as in A1, and for an unknown asset and field.

### C. Routes: foreign IDs answer 404, references are checked

11. **Red: route by route.** Controller tests: each route that takes an ID, given another repository's or an unknown one,
    answers 404, names nothing of the other repository, and changes nothing. The routes: the asset-detail modal (HTML and
    JSON); the rename and delete folder dialogs, folder rename, move and delete; album rename, its rename and delete dialogs,
    delete, add-to-album and membership removal; asset restore; the people routes of `PeopleActionController` (cover modal,
    cover image, name view and editor, merge modal, merge, hide, show, discard); the search route with a foreign `personId`,
    `folderId`, `albumId` or `locationId`; and the content route for an original, a preview and a Face.
12. **Green: `answering` and `answeringJson`.** The HTML routes run inside `answering`; the JSON album, asset, Location and
    Curation routes inside `answeringJson`. The search route catches `NotFoundException` itself (plain-text 404).
13. **Red, then green: references.** Moving assets into another repository's folder, or one that does not exist, is a JSON
    404; adding a folder under such a parent is the dialog's `PARENT_ID` field error; moving a folder into one is a 400.
    Nothing is written. `LibraryService.moveAssetsToFolder` and `FolderService.add` read the folder through `getById` in their
    transaction.

### D. Guards and documentation

14. **One table-driven route test.** `RouteTableTests` (controller suite) holds a row for every route of `App.allRoutes`, read
    from cask's metadata. A route missing from the table, a path or query ID parameter (`id`, `...Id`) that no probe sends, or
    a body ID field no probe sends fails the test; body fields are the ID-shaped `Api.Field` names the handler reads, found
    with Scalameta (`idFieldsOf`), and a row lists one it names without acting on (`ignoring`, with why). An anonymous request
    to a non-public route answers 302 or 401. Each probe runs against a second repository of the same user, every name in it
    carrying a marker no answer may show: another repository's IDs and unknown ones get the same answer, and
    `TestContext.rowsOf` of both repositories is unchanged. A row that answers other than 404 by design (a dialog's field
    error, a batch that leaves out IDs not of the repository) declares its status with the reason.
15. **Schema guards, red then green.** Red: in `RepositoryIsolationTests`, on both engines, rows written by hand-written
    SQL with another repository's reference are refused by a constraint violation, one per foreign key below, and a scoped
    row with a NULL `repository_id` is refused. Green, in both engines' `all.sql`:
    - `repository_id NOT NULL` on every scoped table.
    - `UNIQUE (id, repository_id)` on `asset`, `folder`, `person`, `album`, `location` and `metadata_field`, the tables a
      composite key names; led by the ID, so it never competes with the indexes that lead with the repository.
    - Foreign keys over `(repository_id, <ref>_id)`: `face.asset_id` and `face.person_id`, `album_asset.album_id` and
      `asset_id`, `location_asset.location_id` and `asset_id`, `metadata_parameter.asset_id` and `field_id`, and
      `search_document.asset_id` cascade; `location.category_id`, `asset.folder_id` and `folder.parent_id` have no delete
      action, as no folder is ever hard-deleted. The root folder is its own parent, which its insert satisfies. On PostgreSQL
      `folder` is declared above `asset`.
    - A NULL reference is not checked. A triaged asset stores a NULL `folder_id`: `AssetDao` writes NULL for the model's empty
      folder ID and reads `""` back (`AssetRow.folderId` is optional), and `SearchQueries.folderCounts` counts it under no
      folder.
    - `person.cover_face_id REFERENCES face (id) ON DELETE SET NULL`; on PostgreSQL `ALTER TABLE person ADD CONSTRAINT
      person_cover_face` after `face`, since `face` references `person`. `person_04 (cover_face_id)` serves its delete
      action, `metadata_parameter_02 (field_id)` a field's cascade; the other referencing indexes lead with the referencing
      ID (`face_01`, `album_asset_02`, `location_asset_02`, `metadata_parameter_01`, `search_document_01`, `asset_folder`,
      `folder_03`).
    - `repository.root_folder_id` has no key: the repository row is written before its root folder.
16. **Documentation.** `altitude/AGENTS.md`, DAO / Service Layer: "Repository scoping" (the `BaseDao` default, the four
    opt-outs, empty queries, hand-written SQL, `requireFound`, references, `answering` / `answeringJson`, `RouteTableTests`)
    and "Schema guards". `docs/READINESS.md` (Permissions): the item on entities fetched by ID is closed, the score line
    updated, and the permission-matrix item narrowed to what D14 leaves open. `docs/test-coverage.md` (Repository isolation):
    the foreign-ID, route-table and empty-query items closed. `docs/faces.md`: covers moved off on purge and discard, the
    placeholder, and the cover's foreign key.

## Verification

- `make test` is green on all four bundles (unit, SQLite, PostgreSQL, controller), `RepositoryIsolationTests` running on both
  engines.
- `RouteTableTests` passes: every route is in the table, every ID parameter and body ID field is probed, and another
  repository's IDs answer as unknown ones, naming nothing and changing nothing.
- The schema tests of `RepositoryIsolationTests` pass on both engines: a reference into another repository and a NULL
  `repository_id` are refused, a triaged asset is stored in no folder, and a cover that is no Face is refused.
- scalafmt `checkFormatAll` is clean.
- `grep -rn "withRepository\|getInRepository" altitude/src` finds nothing.
