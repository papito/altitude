# Repository isolation: every read and write by ID stays in the context repository

## Goals

- A row of a repository-scoped table (`asset`, `folder`, `album`, `album_asset`, `location`, `location_asset`, `person`,
  `face`, `metadata_field`, `metadata_parameter`, `stats`, `search_document`) is read, changed or deleted only through its own
  repository's context. An ID of another repository's row behaves exactly as an ID that does not exist: not found, a 404 at the
  route, nothing changed.
- Scoping is the default: a query with no repository is something a DAO opts out of (the tables that have no `repository_id`),
  never something each caller has to remember.
- A reference written into a row (a folder's parent, an asset's folder, a person's cover Face) points into the same repository.
- Tests prove both, on both engines, for every ID-taking path a route reaches.

Out of scope: share links, roles per repository, a second account type, the WebSocket, and login gating of routes (the
table-driven route test in D checks it, but it fixes nothing there).

## How it works now

**The mechanism.** `Query` (`util/Query.scala`) carries no repository. `Query.withRepository()` adds `repository_id` from
`RequestContext.getRepository` and throws when no repository is in the context. Each caller opts in:

| Path | Scoped |
|---|---|
| `BaseService.query`, `updateByQuery`, `deleteByQuery` | Yes, through `withRepository()` |
| `BaseService.getById`, `getOneByQuery`, `updateById`, `deleteById`, `increment` | No |
| `BaseDao.getById`, `getByIds`, `getOneByQuery`, `query`, `updateById`, `updateByQuery`, `deleteById`, `deleteByQuery`, `increment` | No: each builds or runs a `Query` as given (`BaseDao.scala:208-218, 307`) |
| `AssetDao.queryAll`, `queryNotRecycled`, `queryRecycled` | Yes; `AssetDao.query` throws |
| `AlbumService.getById`, `LocationService.getById` | Yes (overrides with `withRepository()`) |
| `FolderService.getInRepository`, `PersonService.getInRepository`, `getLiveInRepository`, `FaceDao.getInRepository`, `AssetService.isInRepository`, `detailOf` | Yes |
| Search, counts and map (`SearchQueries.matching`) | Yes: the SQL builder filters by `repositoryId` whatever the `SearchQuery` holds |
| `StatsService`, `UserMetadataService.addField`, `getAllFields`, `CurationService.liveAmong`, `LocationDao.moveChildrenToRoot` | Yes |
| The locking reads of the library operations (`AssetDao.lockAssets` and the Sequence statements) | Yes (hand-written SQL with `repository_id = ?`) |

The repository context is set by `decorators.repoContext` for a request, by `PipelineUtils` for a pipeline stage, by
`LibraryService.forEachRepository` for startup work, and by `RepositoryService.switchContextToRepository` and
`SystemService` elsewhere. `requireLogin` answers 404 for a repository in the path that the user does not own.
`BaseController.answering` is the one place that turns `NotFoundException` into a 404; a route outside it does not.

**Paths that change another repository's rows**, given its IDs:

- Albums: `AlbumActionController.htmxDeleteAlbum` → `AlbumService.deleteById` → `super.deleteById` → `BaseDao.deleteById`
  hard-deletes the album. `AlbumController.removeAssetsFromAlbum` → `AlbumService.removeAssets` (no album check) →
  `AlbumDao.removeAssets` (no repository predicate) deletes its memberships. `AlbumDao.getAssetIds` has no repository
  predicate either.
- Folders: `FolderService.rename` and `move` read through `getById` (`FolderDao.getById`, hand-written, by ID alone) and write
  through `updateById`. `isRootFolder` compares with the context repository's root only, so another repository's root folder
  can be moved, and a root that is no longer its own parent breaks the stop condition of `FolderDao.getAncestors` and
  `getChildrenRecursive`. `getChildren`, `getChildrenRecursive` and `getAncestors` have no repository predicate.
- People: `PeopleActionController` reads the Person through `BaseService.getById` for `cover-image`, `hide`, `show`, the
  bad-match `delete`, and `mergePeople`; `PersonService.setFaceAsCover`, `setVisibility`, `markAsBadMatch` and `merge` write
  through `updateById`. `merge` also repoints the source's Faces through `faceDao.updateByQuery`, called on the DAO directly,
  so `BaseService`'s scoping never applies, and soft-deletes the source.

**References written without a check** (the row is the context repository's, the ID it stores is not checked):

- `AssetController.moveAssets` → `LibraryService.moveAssetsToFolder` writes `destFolderId` into `asset.folder_id`.
- `FolderActionController.htmxAddFolder` → `FolderService.add` writes `parentId` into `folder.parent_id`.
- `PeopleActionController.setCoverImage` reads the Face through `PersonService.getFaceById` (`BaseDao.getById`, unscoped) and
  never checks that it is the Person's.
- In the schema (`all.sql`, both engines), `asset.folder_id` and `folder.parent_id` have no foreign key, no foreign key
  includes `repository_id`, and `repository_id` is nullable everywhere but `face`, `stats` and `search_document`.

**Reads of another repository's rows:**

- `AssetActionController.showAssetDetailModal` returns the asset's JSON (`AssetService.getById`).
- The rename and delete folder dialogs (`FolderActionController`), the search header's `personOf` and `folderPathOf`
  (`SearchResultsController`), the Person's name view, the merge modal and the choose-cover modal (`FaceDao.getTopFaces`, by
  Person ID alone) show its names and Faces.
- `ContentViewController` reads the asset through `getById` before streaming. Files live in a per-repository directory, so the
  stream fails and answers 404: no bytes leave, but the read is unscoped.
- `LibraryService.deleteFolderById` reads another repository's folder, then changes nothing (its `updateByQuery` and
  `queryAll` are scoped), and the route answers 200.

**Unscoped, with no route reaching them:** `UserMetadataService.getFieldById` and `deleteFieldById`,
`AssetDao.getUserMetadata` (and so `updateMetadata`), `AssetService.rename`, and `PersonService.peopleByIds`, which calls
`dao.query` directly. Its IDs come from Faces already read with the scope. `SearchQuery` inherits `withRepository()`, which
would return a plain `Query` without the search fields. `UserService` and `RepositoryService` inherit `updateByQuery` and
`deleteByQuery`, which would throw on tables with no `repository_id`.

**Tests.** `AlbumServiceTests` ("Another repository's album is not found by ID, and nothing is added to it"),
`LocationServiceTests`, `TriageControllerTests` ("Every Triage route is a 404 for an unknown ID and for another
repository's"), `IndexControllerTests`, `AssetActionControllerTests` (Detail panel) and `PeopleActionControllerTests`
(results title, rename) cover foreign IDs. Nothing covers album deletion, the folder or people writes, membership removal,
merge, or the asset-detail JSON. `docs/READINESS.md` (Permissions) and `docs/test-coverage.md` (Repository isolation) list
these as open items.

## Decisions

Proposed, pending the open questions below.

| Concern | Decision |
|---|---|
| Where scoping lives | `BaseDao`, by default. Scoping only in `BaseService` misses the DAO calls services make directly (`PersonService.merge`, `peopleByIds`) |
| Tables with no `repository_id` | `RepositoryDao`, `UserDao`, `SettingDao` and `SystemMetadataDao` opt out (`repositoryScoped = false`) |
| No repository in the context on a scoped DAO | An exception, as `withRepository()` throws now: refusing is the safe failure |
| `Query.withRepository()` | Removed once every DAO scopes itself; no caller still needs it |
| Another repository's ID | `NotFoundException`, a 404 at the route through `answering`, the same answer as an unknown ID, naming nothing of the other repository |
| A route that reads by ID outside `answering` | Moved into `answering`, so a foreign or unknown ID is a 404 and not a server error |
| Hand-written SQL by ID on a scoped table | Carries `repository_id = ?`, as `AssetDao` and `FaceDao.getInRepository` do |
| A destination or parent folder | Must be a folder of the context repository (`FolderService.getInRepository`), checked in the same transaction as the write |
| A cover Face | Must be a Face of the context repository (`FaceDao.getInRepository`) and the Person's |

## Constraints

- TDD: each change starts from a failing integration or controller test. The service tests run on both engines
  (`make test-sqlite`, `make test-psql`), and the controller tests on SQLite.
- Schema changes go into `all.sql` (both engines) only, with no versioned migration and no `schemaVersion` bump. The database
  is recreated from scratch.
- No change for a caller that uses its own repository's IDs: every existing suite passes unchanged.
- Subagents edit disjoint files and never run mill; the parent compiles and tests.

## Changes

### A. Scoped by default in `BaseDao`

1. **Red: the generic paths with two repositories.** In a new `RepositoryIsolationTests` (integration, both engines), with a
   row of each scoped table in a second repository: `getById`, `getByIds`, `getOneByQuery`, `query`, `updateById`,
   `updateByQuery`, `deleteById`, `deleteByQuery` and `increment`, called from the first repository with the second's IDs, find
   nothing and change nothing (re-read from the second repository: same values, same counters, same memberships). An empty
   `updateByQuery` or `deleteByQuery` is refused and changes no row. Each scoped DAO without a repository in the context
   throws.
2. **Green: `BaseDao` scopes itself.** `repositoryScoped: Boolean` (true by default, false in the four DAOs above). When
   true, `matching`, the `deleteByQuery` and `updateByQuery` filters, and `increment` add `repository_id` from the context.
   `FolderDao.getById`'s hand-written statement gains `AND f.repository_id = ?`.
3. **Refactor.** Remove `Query.withRepository()` and its callers (`BaseService`, `AssetDao`, `AlbumService`,
   `LocationService`, `FolderService.getAll`, `LocationDao`, `UserMetadataService`, `StatsService`). The `getById` overrides in
   `AlbumService` and `LocationService` become plain `BaseService.getById`. `UserService.query` and `RepositoryService.query`
   lose their comments about scoping.

### B. The paths outside the generic methods

4. **Red: albums.** Another repository's album: delete is `NotFoundException`, and its row and memberships stay; removing
   assets from it is `NotFoundException`, and its memberships stay; `getAssetIds` returns nothing.
5. **Green: albums.** `AlbumService.removeAssets` reads the album through `getById` first; `AlbumDao.removeAssets` and
   `getAssetIds` carry `repository_id = ?`. `AlbumService.deleteById` is already covered by A2; the test from 4 proves it.
6. **Red: folders.** Another repository's folder can be neither renamed nor moved, its root folder cannot be moved, neither
   can be the destination of a move, and `getChildren`, `getChildrenRecursive` and `getAncestors` return nothing for it.
   Deleting it is `NotFoundException`.
7. **Green: folders.** The three recursive statements of `FolderDao` carry `repository_id = ?` in both arms of the
   recursion. `LibraryService.deleteFolderById` reads through `getInRepository`. `isRootFolder` stays as it is: with the reads
   scoped, a foreign root is not found before it is compared.
8. **Red: people and Faces.** Another repository's Person: read by ID is `NotFoundException`; hide, show, mark as bad
   match, set cover and rename, given their Person, change nothing; merging with them, as source or as destination, is
   `NotFoundException`, and their row and Faces stay. `getPersonFaces` returns nothing for them. Setting a cover to a Face
   of another Person, of either repository, is refused.
9. **Green: people and Faces.** `FaceDao.getTopFaces` filters by repository. The Face update in `PersonService.merge` is
   scoped by A2. `setFaceAsCover` checks that the Face is the Person's (`ValidationException`, or `NotFoundException` for one
   not in the repository).
10. **Red, then green: metadata and the rest.** `UserMetadataService.getFieldById` and `deleteFieldById` are scoped by A2;
    `AssetDao.getUserMetadata` (and so `updateMetadata`) carries `repository_id = ?`. Tests as in A1.

### C. Routes: foreign IDs answer 404, references are checked

11. **Red: route by route.** Controller tests: each route that takes an ID, given another repository's, answers 404, names
    nothing of the other repository, and changes nothing. The routes are: the asset-detail modal (HTML and JSON), the rename
    and delete folder dialogs, folder rename, move and delete, album delete and membership removal, the people routes in
    `PeopleActionController` that read through `getById` (cover modal, cover image, name view, merge modal, merge, hide,
    show, discard), the
    search header with a foreign `personId` or `folderId`, and the content route for an original.
12. **Green: `answering`.** Those routes run inside `answering`. The search header's `personOf` and `folderPathOf` read
    through `getInRepository`.
13. **Red, then green: references.** Moving assets into another repository's folder, or one that does not exist, and adding
    a folder under another repository's parent, or one that does not exist, are refused (404, or the dialog's field error for
    the add dialog), and nothing is written. `LibraryService.moveAssetsToFolder` and `FolderService.add` read the folder
    through `getInRepository` in their transaction.

### D. Guards and documentation

14. **One table-driven route test.** Over `App.allRoutes`: an anonymous request answers 302 or 401, and every route under
    `/r/:repoId` that takes an entity ID answers 404 for another repository's ID. A route with an ID parameter the table does
    not list fails the test, so a new route has to be added to it.
15. **Schema guards** (subject to the open questions). `repository_id NOT NULL` on every scoped table. Composite keys
    `UNIQUE (repository_id, id)` on `folder`, `person`, `face`, `album`, `location` and `asset`, so that foreign keys over
    `(repository_id, <ref>_id)` hold `asset.folder_id`, `folder.parent_id`, `face.asset_id`, `face.person_id`,
    `album_asset`, `location_asset`, `location.category_id` and `person.cover_face_id` in one repository. Both engines'
    `all.sql`.
16. **Documentation.** `altitude/AGENTS.md`, DAO / Service Layer section: scoping is `BaseDao`'s default, the four DAOs that
    opt out, the rule for hand-written SQL, and a foreign ID being a 404 through `answering`. In `docs/READINESS.md`
    (Permissions), the item on entities fetched by ID and the note on the content route are closed. The permission-matrix
    item is narrowed to what D14 leaves open. In `docs/test-coverage.md` (Repository isolation), the foreign-ID, route-table
    and empty-query items are closed.

## Verification

- `make test` is green: every new test red before its change and green after, on both engines where it is an integration test.
- `make lint` is clean.
- On a second app instance over a copied `data/`, with two Libraries of one account: a folder, album, person and asset ID of
  one Library, sent through the other's URLs by `curl`, answers 404 and leaves the first Library's rows unchanged (compare
  with the database before and after).
- `grep -rn "withRepository" altitude/src` finds nothing.

## Open questions

1. **Where scoping lives.** Scope in `BaseDao` by default (proposed, closes the direct DAO calls too), or only in
   `BaseService`, which is what `docs/READINESS.md` names as the fix? The latter is a smaller change but leaves
   `PersonService.merge`'s Face update and `peopleByIds` to be fixed one by one.
2. **Schema guards (D15).** Add composite foreign keys and `NOT NULL`, which make cross-repository references impossible at
   the database whatever the code does? Or keep the schema as it is and rely on the code and tests? The composite keys add
   a unique index per referenced table, and `person.cover_face_id` and `folder.parent_id` (the root folder is its own parent)
   need checking for insert order.
3. **PostgreSQL row-level security.** Add it as defense in depth: a policy per scoped table over
   `current_setting('app.repository_id')`, set with `SET LOCAL` as each transaction begins in `TransactionManager`? SQLite has
   no equivalent, so it would protect only PostgreSQL deployments, and startup work across repositories (`forEachRepository`)
   would set it per repository. Proposed: not now.
4. **Hand-written SQL audit.** This plan covers the hand-written statements the `Query` paths led to (`FolderDao`,
   `AlbumDao`, `FaceDao.getTopFaces`, `AssetDao.getUserMetadata`). Should a full pass over every `manyBySqlQuery`,
   `collectBySqlQuery`, `updateByBySql`, `getOneBySql` and `executeAndGetOne` be part of this plan, or a separate one?
5. **A Scalameta guard.** Add a rule to the unit suite, like `TransactionBoundaryTests`, that flags hand-written SQL on a
   scoped table without `repository_id`? It can only match strings, so it would be approximate. Proposed: no, D14 covers the
   routes.
6. **A move into a recycled folder.** Should `moveAssetsToFolder` and `FolderService.add` also refuse a destination or parent
   in the trash, or only one outside the repository?
7. **A write by ID that matches nothing.** Once scoped, `updateById` on another repository's row changes 0 rows and returns
   0, and services that take a model (`setVisibility`, `markAsBadMatch`, `setFaceAsCover`, `updateName`) return it as if
   updated. The routes read the row first, so they answer 404 before writing. Should `BaseService.updateById` and
   `deleteById` also throw `NotFoundException` on 0 rows, as `AlbumService.deleteById` and `LocationService.deleteById` do
   now?
8. **The repository cache.** `RepositoryService.getById` caches repositories with no invalidation (`docs/READINESS.md`,
   LOW). Leave it out of this plan?
