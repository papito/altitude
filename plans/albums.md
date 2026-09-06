# Albums

## Original goals

- The Albums tab exists (`altitude/views/index.scala.html`, `#albumsTab`) but its body only says
  "ALBUMS" (`altitude/views/htmx/albums.scala.html`).
- With no albums, a single centered button in the explorer says "Add your first album" (Font
  Awesome plus icon). Otherwise an "Add album" button sits at the top of the explorer.
- Model the behavior after folders, except:
  - the only menu actions are Rename and Delete;
  - albums are a flat list, no nesting;
  - drag and drop works between assets and albums, but albums only point at assets. Removing an
    asset from an album, or deleting an album, does nothing to the asset;
  - an asset can be in many albums;
  - recycling or purging an asset removes it from every album.
- A new database model is required.

## Status

Planned and implemented on 2026-09-06 in one pass (the user asked for the plan to be executed
immediately, choosing the best option wherever several existed). See "Implementation status" at
the end for what was built and verified.

## Decisions (options considered, choice made)

1. **Where the album list is rendered.** (a) Server-rendered Twirl like the People tab, or (b)
   client-rendered from a JSON endpoint like the folder tree. **Chosen: (b).** The folder tree's
   popover menu is built in JS with Alpine attributes; rendering albums the same way lets one
   shared menu builder serve both tabs instead of duplicating the popover markup in Twirl, and the
   reload-with-focus-restore pattern (`reloadFolderTree`) carries over unchanged.

2. **The Rename/Delete menu.** (a) Duplicate the folder popover for albums, or (b) generalize the
   folder menu into a shared context menu (component, close helper, CSS) used by both.
   **Chosen: (b).** `folderMenu` becomes `contextMenu` (`alpine/components/context-menu.js`),
   `common/folder-menu.js` becomes `common/context-menu.js`, the popover CSS moves from
   `htmx/folders.scala.html` to `core.css` under `.context-menu`, and the menu cell builder moves to
   `common/context-menu.js` (`buildContextMenuCtrl`). Stable IDs keep their entity prefix
   (`folderMenuCtrl-<id>` / `albumMenuCtrl-<id>`, `menu-<id>` panels are unique per entity id).

3. **The Add album dialog.** (a) Inline in a popover like Add folder (there is no menu to anchor it
   to, so the button itself opens a panel and loads the dialog), or (b) a modal dialog into
   `#modalContent` like the people dialogs. First built as (b); **changed to (a) at the user's
   request**: each add button is a `contextMenu` component whose panel holds only the dialog
   (`buildDialogTriggerCtrl`), opened right below the button (first centered in the explorer, then
   moved under the button at the user's request), submitted with Return, and handing focus back to
   the button with no focus ring. Rename and Delete stay inline in the album's menu,
   exactly like folders.

4. **Deleting an album.** (a) Soft delete with `is_recycled` like folders, or (b) hard delete.
   **Chosen: (b).** Folders are soft-deleted because their assets are recycled with them and a
   restore must bring the folder back. An album holds nothing, so there is nothing to restore;
   `ON DELETE CASCADE` on the membership table removes the pointers.

5. **Removing an asset from an album.** (a) A footer batch action ("Remove from album (n)") shown
   only while an album's results are displayed, or (b) an action in asset detail. **Chosen: (a).**
   It fits the existing batch-ops footer and works for many assets at once; the selection store and
   grid removal code already exist.

6. **Membership on recycle/purge.** Recycling is a flag, so `LibraryService.recycleAssets`
   deletes the asset's memberships explicitly in the same transaction (this also covers folder
   deletion, which recycles the subtree's assets). Purge deletes the asset row and the
   `ON DELETE CASCADE` foreign key removes any membership (SQLite write connections run with
   `PRAGMA foreign_keys=ON`). Restoring a recycled asset does not put it back into albums.

7. **Asset counts.** Each album row shows its asset count in the same dimmed `(n)` cell the folder
   rows use, computed on read with one `GROUP BY album_id` query. Only non-recycled assets ever
   hold a membership, so a plain count matches what clicking the album shows.

8. **Viewing an album.** A new search parameter `albumId`, handled like `personId`: the
   `searchParams` store carries it (choosing an album clears folder and person and vice versa),
   `SearchResultsController` accepts it, `SearchQuery` gets `albumIds`, and the query builder adds
   `asset.id IN (SELECT asset_id FROM album_asset WHERE album_id IN (...))`. The results fragment
   carries `data-results-album-id` so the album list can mark the viewed album green, as the
   folder tree marks the viewed folder.

9. **Schema migration.** `schemaVersion` goes to 2 with `postgres/2.sql` and `sqlite/2.sql`, and
   the new tables are added to both `all.sql`. `MigrationService` had two defects that this
   exposes: in dev and test it re-ran `all.sql` for every version (a second run fails on SQLite
   and drops the dev schema on Postgres), and a fresh Postgres install ended one version ahead
   (`all.sql` inserts version 1 and `versionUp` then made it 2). It is changed to: a fresh
   database (version 0) runs `all.sql` once and is stamped with `CURRENT_VERSION`; anything else
   runs `N.sql` incrementally in every environment. The Postgres `all.sql` now inserts version 0
   like SQLite. A database left one version ahead by the old defect would need its recorded
   version corrected by hand before a later `<version>.sql` could apply; see "Implementation
   status" for the dev database.

## Data model

```sql
-- album: one row per album, repository-scoped, unique name per repository (case-insensitive)
CREATE TABLE album (
  id CHAR(36) PRIMARY KEY,
  repository_id CHAR(36) REFERENCES repository (id) ON DELETE CASCADE,
  name VARCHAR(255) NOT NULL,
  name_lc VARCHAR(255) NOT NULL
) -- + created_at / updated_at per engine convention
CREATE UNIQUE INDEX album_01 ON album (repository_id, name_lc);

-- album_asset: the pointers; both foreign keys cascade
CREATE TABLE album_asset (
  repository_id CHAR(36) REFERENCES repository (id) ON DELETE CASCADE,
  album_id CHAR(36) NOT NULL REFERENCES album (id) ON DELETE CASCADE,
  asset_id CHAR(36) NOT NULL REFERENCES asset (id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX album_asset_01 ON album_asset (album_id, asset_id);
CREATE INDEX album_asset_02 ON album_asset (asset_id);
```

## Backend

- `models/Album.scala`: `Album(id, name, numOfAssets = 0)`, `NoDates`, empty-name validation and
  `nameLowercase` like `Folder`.
- `dao/AlbumDao.scala` + `dao/jdbc/AlbumDao.scala` (`tableName = "album"`): `add`, `getAll`
  (sorted by `name_lc`, with `num_of_assets` from a correlated count), `addAssets(albumId,
  assetIds)` (skips pairs already present), `removeAssets(albumId, assetIds)`,
  `removeAssetsFromAllAlbums(assetIds)`, `getAssetIds(albumId)`. The membership table has no
  model of its own; it is written only through `AlbumDao`, as `SearchDao` writes
  `metadata_parameter`.
- `service/AlbumService.scala`: `add(name)`, `getAll`, `rename(id, name)`, `deleteById(id)`,
  `addAssets`, `removeAssets`, `removeAssetsFromAllAlbums`, `getAssetIds`. `addAssets` only adds
  assets that exist in the repository and are not recycled. Duplicate names surface as
  `DuplicateException` through `BaseService.add` / `updateById`.
- `LibraryService.recycleAssets`: calls `album.removeAssetsFromAllAlbums` inside the transaction.
- Search: `SearchQuery.albumIds`, `SearchQueryBuilder.albumFilter`, `SearchResultsController`
  `albumId` parameter (also in the pushed browser URL), `search_results.scala.html` gets
  `albumId` and emits `data-results-album-id`.
- Routes:
  - `routes/api/AlbumController.scala` (`api/album`): `GET /r/:repoId/list` (JSON `[{id, name,
    numOfAssets}]`), `PUT /r/:repoId/assets` and `DELETE /r/:repoId/assets` (JSON `{albumId,
    assetIds}`).
  - `routes/web/partial/AlbumActionController.scala` (`htmx/album`): `tab`, `dialogs/add-album`,
    `dialogs/rename-album`, `dialogs/delete-album`, `POST add`, `PUT rename`, `DELETE /` (`id`),
    with the same validation-replacement flow as folders.
- Constants: `FieldConst.Album`, `Api.Field.Album` (`ALBUM_ID`, `NAME`), `Api.Constraints`
  album name lengths (same as folders), `Const.UI` album dialog titles.
- `Altitude.scala`: `DAO.album`, `service.album`, `schemaVersion = 2`. `App.scala`: register
  `AlbumController`.

## Frontend

- `common/context-menu.js`: `buildContextMenuCtrl({ id, triggerId, ariaLabel, actions })`
  (moved from `folder-tree.js`), `getContextMenuTrigger(panel)` (the `[popovertarget]` button in
  the component root, no ID convention), `closeContextMenu`, `closeOpenContextMenu`.
- `alpine/components/context-menu.js`: the former `folderMenu`, registered as `contextMenu`.
- `core.css`: the popover styles under `.context-menu`, moved from `folders.scala.html`.
- `common/album-list.js`: `reloadAlbumList(repoId)` (seq guard, focus snapshot/restore,
  `htmx.process`, `bindSearchTriggers`, viewed-album marker), row builder (menu | count | icon |
  name; the name is a `data-app-search-album-id` trigger), empty-state toggling of
  `#addAlbum` / `#noAlbums`, `setViewedAlbum(albumId)` / `applyViewedAlbum()`.
- `htmx/albums.scala.html`: styles, top "Add album" button, centered empty-state button (both
  `hx-get` the add dialog into `#modalContent`), `#albumList` host, module script calling
  `reloadAlbumList`.
- Dialogs: `add_album_dialog.scala.html` (modal, `data-app-fragment="modal"`),
  `rename_album_dialog.scala.html` and `delete_album_dialog.scala.html` (inline dialogs, the
  album's `albumMenuCtrl-<id>` as return focus; delete returns focus to `#addAlbum`).
- `stores/search-params.js`: `albumId` parameter and its scope rules. `context.js`:
  `getCurrentAlbumId()`.
- `dragdrop/albums.js`: `#albumList .dropzone` accepts `#assets .drag-drop, #batchOps .drag-drop`
  and dispatches `assetsAddedToAlbum` / `batchAssetsAddedToAlbum`; `listeners/albums.js` handles
  them plus `albumAdded` / `albumRenamed` / `albumDeleted` (reload the list) and
  `batchAssetsRemovedFromAlbum`.
- `assets/asset-actions.js`: `addAssetsToAlbum({ albumId, assetIds })` and
  `removeAssetsFromAlbum({ albumId, assetIds })` (the latter removes the cells from the grid);
  both refresh the album counts. `refreshCounts()` also patches album counts, since recycling
  removes memberships.
- `includes/batch_ops.scala.html`: a "Remove from album (n)" button shown while
  `$store.searchParams.albumId` is set.
- `fragments/search-results.js`: sets the viewed album from `data-results-album-id`.
- `fragments/inline-dialog.js`, `global.js`, `models/folder.js`, `common/modal.js`,
  `folder-tree.js`: import the renamed context-menu helpers.

## Tests (red-green-refactor)

- `integration/AlbumServiceTests.scala` (both engines): add/trim/validation, duplicate name (add
  and rename, case-insensitive), `getAll` sorted with counts, rename, delete (memberships gone,
  assets untouched), add assets (idempotent, several albums per asset, recycled assets skipped),
  remove assets, recycle removes from every album, folder deletion removes from albums, purge
  cascades (`purgePipeline.run`), restore does not re-add, repository scoping, search by album
  (with folder/person cleared, pagination).
- `controller/AlbumControllerTests.scala` and `controller/AlbumActionControllerTests.scala`:
  list JSON, add/remove membership endpoints, dialogs render, validation replacement headers.
- Existing suites keep passing (`make test-sqlite`, `make test-controllers`).

## Docs (anti-drift rule)

`altitude/AGENTS.md`, `altitude/views/AGENTS.md`, `docs/agents/alpine-components/{README,
dropdown,headless-popover}.md`: the shared context menu, the album list module, the `albumId`
search parameter, the migration rules.

## Verification

1. `make compile`, `make test-sqlite`, `make test-controllers`, `make lint`.
2. Browser: empty state, add first album, add more, rename, delete, drag single and multiple
   assets onto an album (counts update), click an album (results and green marker), remove from
   album via the footer, recycle an asset that is in two albums (both counts drop), purge from
   trash, folders tab still works (menus, dialogs, drag/drop, Escape).

## Implementation status

Implemented on 2026-09-06; nothing is committed.

What was built, beyond the plan above:

- The dev Postgres database was recorded at version 1 with the version-1 schema when the new
  server started, so `2.sql` applied through the corrected `MigrationService` and the database is
  now at version 2 with `album` and `album_asset`. (An earlier query during planning had shown
  version 2, the value the old `versionUp` defect produces on a fresh install; no manual correction
  was made.)
- Refactors shared with folders: `js/common/context-menu.js` (menu cell builder and close helpers,
  formerly `folder-menu.js`), `js/alpine/components/context-menu.js` (formerly `folder-menu.js`),
  `js/common/asset-count.js`, `dropzoneListeners` in `js/common/dragon-drop.js` (used by the
  folder, people, trash, and album drop zones), `js/listeners/htmx-explorer.js` (formerly
  `htmx-folders.js`), and the menu / count / dialog-title CSS moved from
  `views/htmx/folders.scala.html` to `core.css`.
- Album membership counts follow every asset mutation (`refreshCounts` in `asset-actions.js`),
  since recycling drops memberships.

Validation:

- `make test-sqlite`: 164 tests, all passed. The 13 new `AlbumServiceTests` also passed on
  Postgres (`make test-focused-psql` against the test container). `make test-controllers`: 24
  tests, all passed. `make lint`: clean.
- Browser (Chrome, dev server on :8080, Postgres dev database), driven through DOM APIs because the
  automation tab is hidden:

| Scenario | Result |
|---|---|
| Albums tab with no albums | Only the centered "Add your first album" button; the top button hidden |
| Add first album (empty name, then a name) | Validation error replaces the form in place; on success the modal closes, the list shows the album, the top "Add album" button appears and takes focus |
| Add a second album | Listed in name order |
| Album ⋯ menu | Rename and Delete only; panel placed under the trigger; Rename loads inline with the name selected; submitting renames, closes the panel, and returns focus to the trigger |
| Drop one asset on an album | Count becomes (1); dropping it again warns "Already in album" |
| Drop a selected asset with two selected | Escalates to the batch: both added, selection cleared, grid unchanged |
| Click an album | Results show only its assets, the row is marked (green icon), the URL carries `albumId`, the footer offers "Remove from album (n)" once assets are selected |
| Remove from album | Cell leaves the grid, count drops, assets untouched |
| Recycle an asset that is in an album | Trash count rises, album count drops |
| Escape with an album menu open | Menu closes, focus returns to its trigger |
| Delete the viewed album | Dialog names the album; list reloads, snackbar, results return to the whole repository, focus on "Add album" |
| Folders tab afterwards | Tree renders with counts, menus open and place correctly, Rename loads inline, Escape closes |

The dev asset recycled during verification (`DSC_0092.JPG`) was restored afterwards, and the test
albums were deleted, so the dev data is as it was apart from the schema migration.

Follow-up on 2026-09-06 (user feedback): Add album became an inline popover dialog shown right
below the Add album button, centered on it (no submit button, Return submits), the add button is centered at the top of the explorer,
and it shows no focus ring when the dialog hands focus back. Verified in the browser as below.
