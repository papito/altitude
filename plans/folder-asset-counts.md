# Folder asset counts in the sidebar tree

## Original goals

Add per-folder asset counts to the folder tree:

- Triaged and recycled assets do not count toward any folder's tally.
- Deleting, recycling, and restoring shall reflect new counts.
- Moving assets/folders in/out of folders shall reflect the counts.
- The counts are recursive. If a parent folder has no assets but combined
  children have 4, the parent count is also 4.
- The counts shall be displayed in the folder tree, *before* each folder's
  icon, in parentheses. The font should be subtle (`--font-color-dim`,
  `altitude/static/css/core.css:25`).

## Status

Design complete on 2026-09-06. Not yet implemented.

## Background

The folder tree (`#rootFolderList`) is rendered client-side by
`altitude/static/js/common/folder-tree.js` from
`GET /api/folder/r/:repoId/tree` (`FolderController.getFolderTree`), which
loads every non-recycled folder in one query and assembles the tree in memory.
No per-folder asset count exists in the model, schema, or API today.

A stored per-folder counter (`folder.num_of_assets`) existed once and was
removed in commit `5cf0eea8` as too error-prone. `FieldConst.Folder.NUM_OF_ASSETS`
(`FieldConst.scala:93`) and the commented-out tests in
`StatsServiceTests.scala:188-257` are its vestiges.

## Confirmed decisions

- **Computed on read.** One `GROUP BY folder_id` query over eligible assets
  when the tree is fetched, rolled up bottom-up in memory. No schema column,
  no stored counter, no migration.
- **Eligible asset predicate:** `repository_id = ? AND is_recycled = false AND
  is_triaged = false AND is_purged = false AND is_pipeline_processed = true`.
  `is_pipeline_processed` is included because `SearchQueryBuilder.scala:20`
  already excludes half-imported rows from every search, so a folder's count
  must match what clicking it shows. `is_purged` is redundant by construction
  (only recycled rows are flagged) but harmless.
- **Root row shows the repository total** (all sorted assets).
- **Zero counts are hidden.** No `(0)`.
- **After asset operations** (move/triage sort, recycle, restore, purge) the
  counts are **patched in place** in the DOM, with no tree teardown. Folder
  operations (add/move/rename/delete) keep the existing full
  `reloadFolderTree`, which renders counts naturally.
- **Tree assembly moves into `FolderService.getTree`** so the roll-up is
  testable at the service layer on both engines; the controller only
  serializes. The unused `Folder.children` field gets a purpose.
- **Count query lives on `AssetDao`** (it reads only the `asset` table; DAOs
  are one-per-table), exposed via `AssetService.countByFolder`, consumed by
  `FolderService.getTree`. Service-to-service calls are established practice.
- **JSON field `numOfAssets`** (camelCase, matching `numOfChildren`,
  `parentId`, `isRoot`).
- **No index now.** A repo-scoped scan plus hash group is fine at
  personal-library scale. See Follow-ups.

## Backend (strict red-green-refactor per root AGENTS.md)

### Step 1 - RED: service tests

`altitude/test/src/altitude/core/integration/FolderServiceTests.scala`,
reusing `folderHierarchyFixture`. Add a private helper flattening a tree into
`Map[folderId, numOfAssets]`:

```scala
private def assetCounts(tree: Folder): Map[String, Int] =
  tree.children.flatMap(assetCounts).toMap + (tree.persistedId -> tree.numOfAssets)
```

Tests (tag with `Focused` while iterating, remove when green):

1. **Tree assembly** - `folder.getTree` root id is `rootFolderId`; children
   sorted by name (`folder1`, `folder2`; `folder1_1`, `folder1_2`);
   `numOfChildren == children.length` on each node; a folder recycled via
   `library.deleteFolderById` is absent.
2. **Recursive roll-up** - 2 assets in `folder1_1_1_1`, 1 in `folder1_1_1_2`,
   1 in `folder1_2`, 3 in `folder2_1`, 1 at root (`persistAsset()` defaults
   to root). Expect `folder1_1_1 -> 3`, `folder1_1 -> 3`, `folder1 -> 4`,
   `folder2 -> 3`, `folder2_1 -> 3`, root `-> 8`; an added empty
   `folder3 -> 0`.
3. **Triaged excluded** - `persistAsset(isTriaged = true)` x2 leaves root
   unchanged.
4. **Recycle / restore** - asset in `folder1_1`;
   `library.recycleAssets(Set(id))` -> `folder1_1`, `folder1`, root all 0;
   `library.restoreRecycledAssets(Set(id))` -> all back to 1. Do **not** use
   `persistAsset(isRecycled = true)`: `AssetDao.add` never writes
   `is_recycled`, so the flag is silently dropped.
5. **Move asset** - asset in `folder1_2`;
   `library.moveAssetsToFolder(Set(id), folder2_1.persistedId)` ->
   `folder1_2`/`folder1` 0, `folder2_1`/`folder2` 1, root still 1.
6. **Sort from triage** - triaged asset, root 0; `moveAssetsToFolder` into
   `folder1` -> `folder1` 1, root 1.
7. **Move folder subtree** - 3 assets under `folder1_1`, 1 in `folder2`;
   `folder.move(folder1_1.persistedId, folder2.persistedId)` -> `folder1` 0,
   `folder2` 4, root 4.
8. **Delete folder** - 3 under `folder1_1`, 1 in `folder1_2`;
   `library.deleteFolderById(folder1_1)` -> `folder1_1` gone, `folder1` 1,
   root 1.
9. **Purge leaves counts unchanged** - recycle, then
   `library.purgeSelectedAssets(Set(id))`; counts identical before/after.
10. **Pipeline-incomplete excluded** - persist in `folder1`, then
    `update("UPDATE asset SET is_pipeline_processed = ? WHERE id = ?", <native false>, id)`
    via `IntegrationTestCore.update` -> `folder1` 0. Use the engine-appropriate
    false literal (`false` Postgres, `0` SQLite).
11. **Repo-scoped** - `testContext.persistRepository()` for a second repo,
    persist an asset there, `switchContextRepo` back -> first repo's root
    excludes it. Guards the manual `repository_id = ?` bind, since raw SQL
    does not go through `Query.withRepository()`.

Red state is a compile failure (`getTree`, `numOfAssets` do not exist).

### Step 2 - GREEN

- **`altitude/src/altitude/core/models/Folder.scala`** - add
  `numOfAssets: Int = 0` as the last constructor param. All construction
  sites use named args / `copy` (`FolderDao.makeModel`,
  `RepositoryService.scala:50`). `equals` keeps ignoring it, like
  `numOfChildren`.

- **`altitude/src/altitude/core/dao/AssetDao.scala`** (trait) -
  `def countByFolder(): Map[String, Int]`.

- **`altitude/src/altitude/core/dao/jdbc/AssetDao.scala`** - engine-neutral
  raw SQL via the existing `manyBySqlQuery` / `nativeBool` idiom (see
  `getAssetsByIdAndRecycledFlag` in the same file):

  ```scala
  override def countByFolder(): Map[String, Int] =
    val sql = s"""
      SELECT ${FieldConst.Asset.FOLDER_ID}, COUNT(*) AS ${FieldConst.Folder.NUM_OF_ASSETS}
        FROM asset
       WHERE ${FieldConst.REPO_ID} = ?
         AND ${FieldConst.Asset.IS_RECYCLED} = ?
         AND ${FieldConst.Asset.IS_TRIAGED} = ?
         AND ${FieldConst.Asset.IS_PURGED} = ?
         AND ${FieldConst.Asset.IS_PIPELINE_PROCESSED} = ?
       GROUP BY ${FieldConst.Asset.FOLDER_ID}
    """
    val values = List(RequestContext.getRepository.persistedId,
      nativeBool(false), nativeBool(false), nativeBool(false), nativeBool(true))
    manyBySqlQuery(sql, values).map { rec =>
      rec(FieldConst.Asset.FOLDER_ID).asInstanceOf[String] -> getIntField(rec(FieldConst.Folder.NUM_OF_ASSETS))
    }.toMap
  ```

  `COUNT(*)` is `java.lang.Long` on Postgres and `java.lang.Integer` on
  SQLite. Add `protected def getIntField(value: AnyRef): Int` to
  **`altitude/src/altitude/core/dao/jdbc/BaseDao.scala`** (Integer/Long
  match, else `IllegalArgumentException`), next to `getBooleanField`.
  `dao/postgres/AssetDao.scala` needs no change.

- **`altitude/src/altitude/core/service/AssetService.scala`** -
  `def countByFolder(): Map[String, Int] = txManager.asReadOnly { dao.countByFolder() }`.

- **`altitude/src/altitude/core/service/FolderService.scala`** - move tree
  assembly here from the controller:

  ```scala
  /** Non-recycled tree rooted at the repository root, children sorted by name, with
    * numOfChildren and the recursive numOfAssets populated on every node. */
  def getTree: Folder =
    txManager.asReadOnly {
      val folders = getAll.filterNot(_.isRecycled)
      val rootFolder = folders.find(_.persistedId == contextRepo.rootFolderId)
        .getOrElse(throw NotFoundException(s"Root folder ${contextRepo.rootFolderId} not found"))
      // Root is its own parent - keep it out of the index so it does not become its own child
      val childrenByParentId = folders.filter(f => f.persistedId != f.parentId).groupBy(_.parentId)
      val directCounts = app.service.asset.countByFolder()
      assembleTree(rootFolder, childrenByParentId, directCounts)
    }

  private def assembleTree(folder: Folder, childrenByParentId: Map[String, List[Folder]],
                           directCounts: Map[String, Int]): Folder =
    val children = childrenByParentId.getOrElse(folder.persistedId, Nil)
      .sortBy(_.nameLowercase)
      .map(assembleTree(_, childrenByParentId, directCounts))
    folder.copy(
      children = children,
      numOfChildren = children.length,
      numOfAssets = directCounts.getOrElse(folder.persistedId, 0) + children.map(_.numOfAssets).sum)
  ```

  Single post-order pass, O(folders + count rows); both reads share one
  `asReadOnly` snapshot. Assets in recycled folders never surface because
  `deleteFolderById` recycles them in the same transaction.

- **`altitude/src/altitude/core/routes/api/FolderController.scala`** -
  `getFolderTree` becomes `service.folder.getTree` plus a private
  `toJson(folder)` emitting `id, parentId, name, numOfChildren, numOfAssets,
  isRoot, children`. Keep the hand-built camelCase `ujson.Obj`: the codec
  would emit snake_case and there is no `isRoot` on the model. Update the
  doc comment with `numOfAssets`.

### Step 3 - REFACTOR

Replace the inline Integer/Long match in `FolderDao.makeModel`
(`jdbc/FolderDao.scala:19-23`) with `getIntField`. Run `make test-sqlite`.

### Step 4 - controller test

New `altitude/test/src/altitude/core/controller/FolderControllerTests.scala`
(pattern: `AssetControllerTests`), registered in
`altitude/test/src/altitude/core/suites/AllControllerTestSuites.scala`.

"Folder tree JSON carries recursive asset counts": `folder1` > `folder1_1`;
2 assets in `folder1_1`, 1 in `folder1`, 1 triaged.
`GET /api/folder/r/$repoId/tree` with `testContext.cookies` -> 200, JSON
content type, root `isRoot` true and `numOfAssets == 3`, `children(0)` is
`folder1` with `numOfAssets 3`, `numOfChildren 1`, its child `numOfAssets 2`,
`numOfChildren 0`.

## Frontend

### `altitude/static/js/common/folder-tree.js`

1. Extract `_renderTree(treeData, repoId)` from the post-fetch body of
   `reloadFolderTree` (snapshot, teardown, rebuild, `htmx.process`,
   `bindSearchTriggers`, restore expansion, `applyViewedFolderScope`, focus).
   `reloadFolderTree` = fetch + `_reloadSeq` guard + `_renderTree`.
2. Count cell, inserted immediately **before the icon** in both
   `_buildRootControls` (menu | count | icon | name) and
   `_buildFolderControls` (menu | trace | count | icon | name). Update the
   ordering comments and the header comment.

   ```js
   function _buildAssetCountEl(folder) {
       const el = document.createElement("span")
       el.id = `folder-count-${folder.id}`
       el.className = "asset-count"
       _setAssetCount(el, folder.numOfAssets)
       return el
   }
   // Zero counts render empty, never "(0)"
   function _setAssetCount(el, numOfAssets) {
       el.textContent = numOfAssets > 0 ? `(${numOfAssets})` : ""
   }
   ```

3. `export async function refreshFolderCounts(repoId)` - in-place patch with
   its own `_countsSeq` guard. Bails if `_reloadSeq` advanced meanwhile (a
   full rebuild will carry fresh counts) or `#rootFolderList` is absent
   (another explorer tab is active). Recurse `_patchAssetCounts(folder)` over
   the JSON, setting `#folder-count-{id}`; if any folder has no DOM row
   (restore can un-recycle folders), fall back to
   `_renderTree(response.data, repoId)`. On error: `console.error` plus
   `showErrorSnackBar("Failed to refresh folder counts")`. Rows in the DOM
   but absent from the JSON are left alone; folder ops already do full
   reloads.

### `altitude/static/js/frontend-app.js`

- Add `reloadFolderCounts() { refreshFolderCounts(this.context.getRepoId()) }`,
  importing from `./common/folder-tree.js`.
- Pass `reloadFolderCounts: this.reloadFolderCounts.bind(this)` into
  `createAssetActions` next to `reloadNav` (`:32-36`).
- Trash-purge branch in `handleAfterRequest` (`:101-110`): call
  `this.reloadFolderCounts()` after `this.reloadNav()`. A visual no-op by
  construction (purge only touches recycled assets) but consistent with the
  "every mutation refreshes" rule.

### `altitude/static/js/assets/asset-actions.js`

- Signature `createAssetActions({ Alpine, context, reloadNav, reloadFolderCounts })`.
- Local `function refreshCounts() { reloadNav(); reloadFolderCounts() }`;
  replace the four `reloadNav()` calls in `moveAssets` (:117),
  `recycleAssets` (:148), `purgeAssets` (:171), `restoreAssets` (:205).
- Import completion needs nothing: uploads land in triage.

### CSS in `altitude/views/htmx/folders.scala.html`

A `max-content` column would collapse to 0 on hidden zeros but the 6px
`column-gap` would still apply, jittering sibling icons between rows with and
without a count. Reserve a fixed right-aligned slot instead:

```css
#rootFolderList { /* existing custom properties */ --folder-count-column: minmax(4ch, max-content); }

#rootFolderList .folder.root > .controls {
    /* ⋯ menu button | asset count | icon | folder-name (grows) */
    grid-template-columns: max-content var(--folder-count-column) max-content 1fr;
}
#rootFolderList .folder:not(.root) > .controls {
    /* ⋯ menu button | dotted trace (= indent) | asset count | icon | folder-name (grows) */
    grid-template-columns: max-content max-content var(--folder-count-column) max-content 1fr;
}
/* Right-aligned in a slot wide enough for "(999)", so sibling icons line up whether or not a
   row shows a count (zero counts render empty). A longer count widens only its own row */
#rootFolderList .asset-count {
    justify-self: end;
    color: var(--font-color-dim);
    font-size: .9em;
    white-space: nowrap;
}
```

Update the `.trace` comment (`:52-57`): the count slot adds the same constant
offset to every row, so relative indentation is unchanged. `.9em` follows the
People tab precedent (`people.scala.html:43-46`) and is the one aesthetic
choice not in the requirements.

## Docs (anti-drift rule)

- `altitude/views/AGENTS.md`: "Nav refresh" (`:361`) - asset mutations also
  call `app.reloadFolderCounts()`, which patches `#folder-count-{id}` in
  place and falls back to a full rebuild when the tree shape changed; folder
  ops keep `reloadFolderTree`. "Folder tree expansion and viewed scope"
  (`:286`) - describe the `.asset-count` cell, recursive semantics, hidden
  zeros. `:84` and the Key Files row (`:393`) - tree JSON includes
  `numOfAssets`.
- `altitude/AGENTS.md`: note that `FolderService.getTree` assembles the tree;
  fix the stale Models section (`:71-80`, still describes Play JSON; models
  use upickle `JsonCodec`).
- `FolderController` doc comment (Step 2).

## Verification

1. `make test-focused-sqlite` while iterating, then `make test-sqlite`.
   Never `make test` (needs a live Postgres).
2. `make test-controllers` (ensure nothing else holds `:8081`).
3. `make lint`; re-run tests if scalafix touched Scala.
4. `ENV=dev mill altitude.runBackground`, open the Folders tab: counts before
   icons on non-empty folders, root shows the total, empty folders show
   nothing. Drag an asset between folders, recycle, restore (including one
   whose folder was deleted, which should rebuild the tree with the folder
   back), purge from trash, sort from triage. Confirm expansion state and
   focus survive the in-place patch and the console is clean.

## Risks

- `TestContext.makeAsset(isRecycled = true)` is silently ignored by
  `AssetDao.add`; tests must recycle via `LibraryService`.
- Pre-existing inconsistency: `FolderDao.getChildren` counts recycled
  children in `num_of_children` while `getById` does not. Not touched; the
  tree uses non-recycled children as today.
- Concurrency corner: a full reload started before an asset mutation and
  finishing after the count patch would show pre-mutation counts. Requires
  simultaneous folder and asset ops in a single-user UI; accepted.

## Follow-ups (out of scope)

- Index if the tree endpoint ever shows up hot:
  `CREATE INDEX asset_03 ON asset (repository_id, is_recycled, is_triaged, is_pipeline_processed, folder_id)`
  as a `schemaVersion = 2` migration (`postgres/2.sql`, `sqlite/2.sql`,
  `Altitude.scala:136`).
- Remove or honor the dead `isRecycled` parameter on `TestContext.makeAsset`.
- Delete the commented-out legacy folder-count tests in
  `StatsServiceTests.scala:188-257` once the new tests land.
