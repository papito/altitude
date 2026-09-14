# Altitude DAM – Agent Guide

## Do

* Update this document if you are making changes to the architecture, design patterns, or anything else that future developers should know when working on the codebase. 
* Factor out duplicated code into helper methods.
* Update comments and docstrings when making changes to the code and warn about discrepancies in comments vs code.

## Architecture Overview

`App.scala` is the single entrypoint (extends `cask.Main`). It registers all routes and wires the app via `new Altitude()`.

The frontend is now organized around a thin composition root in `static/js/frontend-app.js`.
Feature logic is split into focused ES module folders instead of accumulating in one large file:

- `static/js/stores/` — Alpine store initialization (`app-stores.js`), including the search parameter set (`search-params.js`)
- `static/js/alpine/components/` — Alpine components (`date-group-selectable.js`, `context-menu.js`), registered from its `index.js` before Alpine starts; asset cells have no component, selection is a reactive set of IDs (`static/js/search-results/selection.js`)
- `static/js/fragments/` — declarative HTMX fragment hydration (`data-app-fragment="..."`), including the modal dialog operation lifecycle (`dialog-operations.js`)
- `static/js/listeners/` — `document.body` custom-event wiring per domain, and the one rule for every htmx request's outcome (`htmx-requests.js`: failures to the snackbar, declared `data-app-success-event`s dispatched, tab selection, fragment hydration)
- `static/js/assets/` — asset mutation/action flows (move, recycle, purge, restore)
- `static/js/search-results/` — the single search funnel (`search.js`), its declarative `data-app-search` triggers (`search-triggers.js`), and the grid's behaviours one module each: selection (`selection.js`), infinite scroll (`infinite-scroll.js`), lazy images (`lazy-images.js`), metadata visibility (`metadata-visibility.js`, CSS classes on `#assets`), the ⚙ View control, detail navigation over the rendered grid (`detail-navigator.js`), the group headers' counts (`date-groups.js`), every cell of an asset (`cells.js`: a Location grouping shows an asset once under each of its Locations), and box selection (`box-selection.js`, built on the vendored Viselect in `static/js/lib/`, committing through the selection store). Asset drags and box selection swallow the click that follows them through `click-suppression.js`
- `static/js/map/` — the map layout of the results: `map-view.js` hydrates the `map-view` fragment (a Leaflet map fed viewport cells from `/api/map/r/:repoId/cells` and clustered again on screen with supercluster), `map-panel.js` is the crowded-pin panel (the ordinary results grid for a map area, a `bbox` search), and `map-state.js` remembers the view looked at per search scope for the tab (`sessionStorage`). See **Map view** in `views/AGENTS.md`
- `static/js/dragdrop/` — interact.js binding modules for assets (thumbnails and the trash drop zone), batch, people, folder, album, and Location drag/drop; the drop-target highlighting they share is `dropzoneListeners` in `static/js/dragdrop/helpers.js`
- `static/js/common/folder-tree.js` — renders the folder tree client-side from `/api/folder/r/:repoId/tree` (assembled by `FolderService.getTree`, which also rolls up each folder's recursive `numOfAssets`), including each folder's native popover context menu, so no menu markup comes from the server; the menu's actions load separate dialogs into the shared modal host, anchored below the row's trigger and sized to their contents. `common/anchored-panel.js` supplies the viewport placement shared by menus and anchored modals. After asset mutations `refreshFolderCounts` patches the counts in place. Branch expansion (single-click one level, double-click all levels, collapse resets descendants) and the green viewed-folder highlight are specified in `views/AGENTS.md` under **Folder tree expansion and viewed scope**
- `static/js/common/album-list.js` — renders the flat album list client-side from `/api/album/r/:repoId/list` (`AlbumController`), with the same menu (`common/context-menu-markup.js`, shared with the folder tree) and count cell (`common/asset-count.js`); `refreshAlbumCounts` patches the counts in place after membership changes and asset mutations. Albums are pointers only: see **Albums** in `views/AGENTS.md`
- `static/js/common/location-list.js` — renders the categories and Locations client-side from `/api/location/r/:repoId/list` (`LocationController`, path order) with the same menu and count cell, one level of indentation for a Location under a category; `refreshLocationCounts` patches the counts in place. Membership drops and the batch footer's Add / Remove go through `assets/asset-actions.js`: see **Locations** in `views/AGENTS.md`
- `static/js/http/client.js` — shared axios client for non-HTMX HTTP requests; prefer this over raw `fetch()` and only override `validateStatus` on the specific calls that intentionally handle non-2xx responses (for example `409`)

`frontend-app.js` should stay the composition root: it initializes context stores, creates the
feature coordinators, registers listeners, starts Alpine, binds drag/drop, and hydrates initial
fragments. Avoid moving feature logic back into that file when adding new behavior.

`Altitude.scala` is the central dependency-injection object. It creates every DAO, service, and the Pekko `ActorSystem` inline. The `DAO` inner object and the `service` inner object both use `dataSourceType match` blocks to mix in the correct DB-specific trait:

```scala
val asset: dao.AssetDao = dataSourceType match {
  case Const.DbEngineName.POSTGRES => new dao.postgres.AssetDao(app.config)
  case Const.DbEngineName.SQLITE   => new dao.jdbc.AssetDao(app.config) with dao.sqlite.SqliteOverrides
}
```

Never instantiate services or DAOs elsewhere — always inject `app: Altitude` and use `app.service.*` / `app.DAO.*`.

## Request Lifecycle

Every request gets its DB connection, authenticated user, and repository injected into **thread-local** `DynamicVariable`s in `RequestContext` (conn, account, repository). These are set by:

- `decorators.repoContext()` — parses `/r/:repoId` from the URL and calls `repository.setContextFromRequest`
- `decorators.requireLogin` — validates the PASETO token (Bearer header or cookie), populates `account`

All three global decorators are applied in `App.mainDecorators`: `compress → requestResponseLogger → repoContext`. Per-route authentication uses `@requireLogin`.

## DAO / Service Layer

- `BaseService` wraps every DAO call in `txManager.withTransaction{}` (writes) or `txManager.asReadOnly{}` (reads). Controllers call services; services call DAOs — never skip a layer.
- `BaseDao` (JDBC) has abstract methods (`jsonFunc`, `nativeBool`, `nativeLocalDateTime`, `nativeUtcTimestamp`, `getBooleanField`, `getDateTimeField`, `getDateField`, `getSortValueField`, `forUpdate`) filled in by `PostgresOverrides` or `SqliteOverrides` traits. `rowProcessor` is how result rows become maps: `PostgresOverrides` reads `timestamp`, `timestamptz` and `date` columns as `java.time` values so a wall-clock timestamp never round-trips through an instant in the JVM zone.
- DB-specific DAO overrides live in `dao/postgres/` and `dao/sqlite/`. The JDBC base lives in `dao/jdbc/`.
- Face vector search requires `txManager.withFaceVector{}` (loads SQLite vector extension before querying).

## Route Layout

| Directory | Purpose |
|---|---|
| `routes/web/` | Full-page Twirl responses |
| `routes/web/partial/` | HTMX partial responses — always return `"<!doctype html>" + template(...)` |
| `routes/api/` | JSON API (`Content-Type: application/json`) |

URL paths scoped to a repository contain `/r/:repoId/` which `repoContext()` uses to populate `RequestContext.repository`.

## Import Pipeline

Pekko Streams pipeline in `service/ImportPipelineService.scala` and `pipeline/flows/`. SQLite uses a sequential single-stream flow (parallelism=1); Postgres uses an async multi-stage flow. Adding a new pipeline stage = add a `Flow` in `pipeline/flows/` and wire it into both `sqliteFlow` and `postgresFlow` in `ImportPipelineService`.

## Models

All models extend `BaseModel` and are case classes with a upickle `JsonCodec` read/writer plus a `ujson.Value` conversion in their companion:

```scala
object Folder:
  given JsonCodec.ReadWriter[Folder] = JsonCodec.macroRW
  given Conversion[ujson.Value, Folder] = json => JsonCodec.read[Folder](json)
```

`JsonCodec` emits snake_case field names. DAOs build typed models from result rows in `makeModel`, so services and DAOs exchange models, not JSON. JSON APIs that need a different shape (camelCase, derived fields such as the folder tree's `isRoot`) build their `ujson.Obj` by hand in the controller.

`FolderService.getTree` assembles the folder tree in memory from one folder query and one per-folder asset count query (`AssetDao.countByFolder`), filling `children`, `numOfChildren`, and the recursive `numOfAssets` on every node; `FolderController` only serializes it.

`BaseDao.updateById` / `updateByQuery` take an untyped `Map[String, Any]`; `Columns.literal` binds the scalar types the old hand-built SQL accepted and, only for a `SET`, an `Option` (`None` renders `NULL`, `Some(v)` binds `v`). A `None` in a `Query` predicate would render `= NULL` and never match, so that path does not use it.

## Albums

An `Album` is a flat, repository-scoped, uniquely named (case-insensitive) list of pointers to assets: the `album` table plus the `album_asset` membership table, which has no model of its own and is written only through `AlbumDao`. An asset can be in any number of albums. Nothing album-related touches an asset: adding to, removing from, renaming, or deleting an album (a hard delete; memberships cascade) changes membership rows only. The reverse direction is `LibraryService.recycleAssets`, which drops recycled assets from every album in the same transaction (so folder deletion does too); restoring does not re-add them, and purging deletes the asset row, whose foreign key cascades. `Album.numOfAssets` is computed on read (`AlbumDao.getAll`). Searching within an album is the `albumIds` filter of `SearchQuery` (`asset.id IN (SELECT asset_id FROM album_asset ...)`), reached through the `albumId` search parameter.

## Search results and date grouping

Two search paths share one definition of "what matches": `LibraryService.search` (full `Asset` records for the ungrouped HTML grid) and `LibraryService.searchGrouped` (a grouped page for the grouped HTML grid). Both resolve the folder scope the same way (`withResolvedFolderScope`: the root folder means no folder filter, any other folder means itself plus its current descendants) and then use the engine's `SearchDao`, which builds every predicate once, for both, in `SearchQueries.matching` (`core/dao/sql/search/`): a typed ScalaSql relation carrying repository scoping, view flags, pipeline completion, column filters, and text, folder, person, album, Location, bounding-box and user-metadata filters. Everything joined in is a semi-join on `asset.id` - the metadata one counts an asset's matching values with its own `GROUP BY`/`HAVING` - so a search reads `FROM asset` alone, with no duplicate rows and nothing to deduplicate on the outside. `LibraryService.count` is the third path over the same relation: the bare `COUNT` of the matches, scoped like `search`, for a result that renders no rows of its own (the map layout). The Location filter (`SearchQuery.locationIds`) is the album filter's shape over `location_asset`. The bounding-box filter (`SearchQuery.bbox`, a `util/BoundingBox` of south, west, north, east; `west > east` crosses the antimeridian and becomes two open-ended longitude ranges) matches an asset by its own point or, when it has none, by the pin of any Location it is in - the rule the map plots by - as plain `BETWEEN` arithmetic, no dialect hook.

`SearchQuery.grouping` (`SearchGrouping(GroupBy.DateTaken, direction)` or `SearchGrouping(GroupBy.Location)`) turns a query into a grouped one; a grouped query has no page number. `GroupBy` carries the date column behind a date grouping (`dateField`, `None` for Location); `SearchDao.searchGrouped` dispatches on it to one of two statements that share one shell. `SearchQueries.grouped` emits **one statement** per page: a materialized, narrow `candidates` slice (ID, day, sort key; `LIMIT rpp + 1` to detect continuation), the `page`, `day_counts` as one correlated count per distinct day on the page, the page joined back to `asset` for its full rows and, on a first page only, `total` over all matches (the dominant cost on a large library, and the footer total is set once). Everything is ordered by day, then the sort, then `asset.id`. It is a hybrid: every branch is the same typed `matching` relation rendered into a hand-written `WITH` shell, because `MATERIALIZED`, the guarded `LIMIT CASE` on the trailing null slice and SQLite's planner hint cannot be expressed as a typed query - but each fragment carries its own bind values, so no branch can drift from the counts beside it. The engine-specific `SearchDialect`s (`Postgres`/`Sqlite`) supply the day expression (`date(col)` on SQLite, `col::date` on PostgreSQL), the full-text predicate, null placement, the `SortValue` type mapper, and on SQLite a unary `+` on the secondary sort term so the planner keeps the grouping day index. The DAO returns `GroupedSearchRow`s (the `Asset`, its group as a `SearchGroupKey`, its `SortValue` exactly as stored, its group's count) in a `GroupedSearchPage` (rows, `total` on a first page, `hasMore`); `SearchService.searchGrouped` folds consecutive rows of one group into `AssetGroup`s and builds the next cursor. `GroupedSearchResult` also carries `continuesGroup`, whether the first group is the one the previous page ended in when this page was reached by cursor.

**Group by Location** (`SearchQueries.groupedByLocation`) is the same shell over two relations instead of one, because its shape differs from the day statement on every axis: `located` is `matching` joined to `location_asset`, `location` and a left-joined category - **one row per asset × Location**, so an asset in two Locations appears under both and the group counts may sum to more than the total, which still counts assets - and `unlocated` is `matching` less every asset in a Location, which is always the trailing "No location" group. Groups are in **path order**, the order the sidebar lists them in: the key is the category's lower-cased name and the Location's own joined by U+0001 (`SearchQueries.PATH_SEPARATOR`, below every printable character, so the composite orders as the pair would and cannot collide with a top-level name), or the Location's name alone at the top level; then the Location's ID as a deterministic tiebreaker, the sort within the group, the asset ID. The direction is fixed: `SearchResultsController` refuses `groupDirection` with `groupBy=location`. A cursor whose anchor was in a Location slices `located` after it and lets `unlocated` fill the page only once `located` has run out (the day statement's guarded `LIMIT CASE`, on `located`); a cursor without a group key slices `unlocated` alone. The `SearchGroupKey` enum is `Day(date)` or `Location(id, pathKey, name, categoryName)`, all `None` for the trailing group; the grouped grid heads a Location group `Category › Location`.

Paging is by cursor only: `SearchCursor` (version 4) is an opaque, versioned Base64URL token holding the last returned image's group (`key`, the ISO day or the Location path key, plus `groupId`, the Location's ID; both absent in the trailing group), sort value and ID, and a fingerprint of the search as requested (engine, repository, filters including `locationIds` and `bbox`, grouping, ordering; a folder filter as given, not its expanded descendants). `LibraryService.searchGrouped` recomputes the fingerprint and rejects a mismatch with `SearchCursorException`; a cursor supplies a position only, never access or SQL, and the page size is not part of it. Results are live: a cursor continues from values, so deleting its anchor or inserting before it neither skips nor repeats the remaining images, while the counts describe the current matching set.

`routes/SearchRequestParser` is the shared parameter-to-scope parser for the HTML search and the map cells/bounds endpoints: `view`, `q`, folder/person/album/Location IDs and `bbox`. It returns a scope that each controller turns into a `SearchQuery` with its own sort, grouping and paging. `bbox` is the grid's and the crowded-pin panel's filter only: the map endpoints and the map layout never read it, and a malformed one is a plain-text 400 in HTML search.

`layout` is separate from `view`: `grid` (default) or `map`. With `layout=map`, grouping, paging and the `bbox` filter are ignored (`bbox` is the panel's scope and stays in the URL), `LibraryService.count` supplies the asset total of the whole search, and `mapBounds` supplies the initial plotted bounds. `includes/search_results` renders the toolbar with Group disabled and `htmx/map_view` directly, without `#assets`. The fragment carries `data-results-location-id`, `data-results-bbox`, `data-results-layout` and the grouping the server used (`data-results-group-by`, and `data-results-group-direction`, empty for a Location grouping); `HX-Replace-Url` includes non-default layout, Location and bbox scope. Map searches omit the ignored grouping from that URL. In the browser, `js/map/map-view.js` plots the search by asking the cells endpoint for every settled viewport and clustering the cells once more on screen; a crowded pin opens a panel whose grid is an ordinary search with `bbox` set. See **Map view** in `views/AGENTS.md`.

The contract on `GET /htmx/search/r/:repoId` is HTML only: JSON negotiation (`Accept` or the legacy `Content-Type: application/json`) is a 400 with a `{"error": ...}` body, grouped or not. Nothing in the app asks for results as JSON - the detail modal walks the rendered grid (see **Detail navigation** in `views/AGENTS.md`).

| Parameter | Values |
|---|---|
| `groupBy` | `dateTaken` (capture day, the camera's calendar date) or `location` (the user's Locations in path order, "No location" last). Anything else, including empty, is a 400. Grouping by import day was removed: an import lands a whole archive on one or two days. |
| `groupDirection` | `asc` or `desc` (default). Needs `groupBy=dateTaken`; a 400 with `location`, whose order is fixed. |
| `sort` | Field plus direction digit, one of the results UI's fields (`Const.Search.SORT_FIELDS`). Applies within each day. |
| `rpp` | 1 to `Const.Search.MAX_GROUPED_RPP` (500); default 50. May differ between a page and its continuation. |
| `after` | The previous page's cursor, sent with `isContinuousScroll`. Rejected for another search. `p` is a 400 with `groupBy`. |

A first page renders `includes/search_results` with `htmx/results_grid_grouped` and an `HX-Replace-Url` carrying `groupBy` and `groupDirection`; a continuation renders the grid alone and is a 204 when the continuation is empty. The grouped grid opens each group with a `result-group` header (`Util.humanReadableDate` for a day, `Category › Location` for a Location with the category in a `.category` span, the group's full count, `data-group-key` naming the group; the first header is skipped when the group continues the previous page's, `continuesGroup`, so the new cells read as the same group), and its last cell carries the encoded cursor in `data-app-search-after` - the last by position, since under a Location grouping the page's last asset can also have a cell in an earlier group. A cell's `id` is `asset-<id>`, except under a Location: an asset in two Locations has a cell in each group, so there it is `asset-<id>-in-<locationId>`, and the client finds an asset's cells by the `data-asset-id` of their thumbnails (`js/search-results/cells.js`). A continuation sends back the layout and grouping its fragment was rendered with, not the store's (`js/search-results/infinite-scroll.js`). Both grids render cells through `htmx/result_cell`, which puts exactly one of `data-app-search-next-page` (ungrouped) or `data-app-search-after` (grouped) on a page's last cell. Validation errors and rejected cursors are plain-text 400s, reported by the snackbar. The frontend side - the Group dropdown, the `groupBy`/`groupDirection` parameters in the `searchParams` store, cursor continuation shared by the scroll observer and the detail modal, and the header counts - is documented in `views/AGENTS.md` under **Search parameters**, **Infinite scroll + lazy load** and **Detail navigation**.

Date storage behind this: `original_created_at` is the camera's wall-clock time with no zone (PostgreSQL `TIMESTAMP WITHOUT TIME ZONE`, SQLite `yyyy-MM-dd HH:mm:ss` text) and is parsed and bound as a `LocalDateTime` with no instant conversion, so its calendar day never depends on the JVM or server zone. `created_at` on assets is bound explicitly in UTC by `AssetDao.add` (an `OffsetDateTime` on PostgreSQL, UTC text on SQLite) instead of relying on the engine default. When no metadata rung yields a capture time the column stays NULL and the asset joins the "No date" group. The index `asset_search_date_taken` is `(repository_id, is_recycled, is_pipeline_processed, <day expression>, <raw timestamp>)`: the day gives seeks and per-day counts, the trailing timestamp makes a same-field grouping and sort read in index order. Nulls sort where each engine puts them (no `NULLS LAST`, which would forfeit index-ordered reads); SQLite's `created_at` can still be null on legacy rows, which matters only when it is the sort column. There is no incremental migration for any of this: the capture columns and the index live in `all.sql` only, the schema version stays 2, and a database is created from scratch.

Coordinates: `latitude` / `longitude` on `asset` are WGS84 decimal degrees, nullable, and are parsed on import only, in `ExtractMetadataFlow` through `util/GeoLocationResolver`. Like `CaptureDateResolver`, it reads only persisted inputs - the `GPS` directory of `extracted_metadata`, where metadata-extractor stores each coordinate as a signed degrees-minutes-seconds description plus its `N`/`S`/`E`/`W` ref tag - so a later backfill needs no file. The ref decides the hemisphere whenever present, because the description's integer degree part drops the sign of a value between -1 and 0 (London's longitude). Unparseable or out-of-range values and 0/0 resolve to nothing, and a coordinate whose ref tag is missing has no description at all: `MetadataExtractionService` skips a tag with a null description instead of storing a null (which the JSON column cannot hold). There is no editing and no backfill of existing rows. The partial index `asset_geo` `(repository_id, is_recycled, is_pipeline_processed, latitude, longitude) WHERE latitude IS NOT NULL` is for the map's bounding-box queries; `SearchMapTests` checks on both engines that a cells statement reads through it (on Postgres over a few thousand analyzed rows, because its planner costs by statistics and every index ties on a handful; the rows are rolled back once the plan is read and the table analyzed again, since the schema is shared by the suites that follow). The `location` and `location_asset` tables are described under **Locations**.

## Locations

A `Location` (`models/Location.scala`) is one row of the `location` table, and `LocationKind` says which of two things it is: a **Category** (a named container, exactly one level deep, no pin, no assets) or a **Location** (a WGS84 pin, optionally under a Category). Both kinds share one case-insensitive name pool per repository (`location_01` on `(repository_id, name_lc)`), so a category and a Location cannot have the same name, and both kinds surface a clash as `DuplicateException` through `BaseService.add` / `updateById`. The model's constructor keeps the kinds honest (a Category has no pin and no Category; a Location has a pin in range) and the schema repeats the rules as CHECK constraints; "a Location's category must be a category" is a `LocationService` check (`IllegalOperationException`), as is "only a Location can be moved" and "a category holds no assets".

Membership is `location_asset`, the `album_asset` shape: no model, written only through `LocationDao`, an asset in any number of Locations, unknown / foreign / recycled asset IDs dropped on add, adds idempotent. `LibraryService.recycleAssets` drops recycled assets from every Location beside every album; restoring does not re-add; purging cascades. `Location.numOfAssets` and `categoryName` are computed by `LocationDao.getAll`, which lists the repository in **path order**: categories and top-level Locations interleaved by name, each category directly followed by its own Locations by name (`ORDER BY COALESCE(category.name_lc, name_lc), category first, name_lc`; the explicit category-first key matters because a Location named "Alba" would otherwise sort before its category "Italy"). Deleting either kind is a hard delete; a category's Locations are moved to the top level in the same transaction first (`LocationDao.moveChildrenToRoot`), and the `category_id` foreign key has no cascade, so a delete that forgot the re-category fails instead of orphaning. `LocationService.getById` is repository-scoped, and every mutation goes through it, so a foreign ID is a `NotFoundException` and changes nothing. The `locationIds` and `bbox` search filters and **Group by Location** are described under **Search results and date grouping**, the map's queries under **Map**; `LocationController` exposes `/api/location/r/:repoId/list` as flat camelCase JSON in path order (nullable `categoryId`, `categoryName` and pin), and `PUT`/`DELETE /api/location/r/:repoId/assets` accept `{locationId, assetIds: [...]}` and return `{added}`/`{removed}`. Malformed membership payloads and category targets are JSON 400s; a foreign Location is a JSON 404. `LocationActionController` owns `/htmx/location/r/:repoId/tab`, all six dialogs, and add/add-category/rename/move/assets/delete. Its dialog membership payload carries a comma-separated `assetIds` string, and its success response carries `{"added": n}` in the `App-Success-Detail` header (`BaseController.dialogSuccessResponse`, `Api.Field.SUCCESS_DETAIL_HEADER`), which the client merges into the dialog's success detail, so the dialog reports what the server applied rather than the size of the selection. Name, coordinate and Category errors replace the form through `dialogFormValidationResponse`; duplicate names share that path. Coordinates arrive as decimal strings from the dialog's hidden inputs (the pin is placed on a map, never typed; `views/AGENTS.md` **Location pin editor**) or as JSON numbers; a missing or out-of-range coordinate is reported once, as `Const.Msg.Err.PIN_REQUIRED`. An empty/null Category means the top level. Invalid hidden IDs are plain-text 400s, foreign IDs plain-text 404s. The Locations tab, its dialogs and the membership flows are described under **Locations** in `views/AGENTS.md`, and the map editor of the Add location dialog under **Location pin editor** there.

## Map

The map never receives a result set: a repository can hold millions of assets, so the browser asks for a viewport and gets back aggregates. Both aggregates are one statement each in `SearchQueries`, over a `points` CTE of the search's **plotted points** (`plottedPoints`, the rule the `bbox` filter also follows): a matching asset with coordinates of its own is plotted there and only there; one without is plotted at the pin of *each* Location it is in, so an asset in two Locations is two points, as it is two cells in the Location grid. The CTE is a `UNION ALL` of two typed relations over `matching`, so the map carries every filter the grid does.

- `mapCells(query, bbox, cellDegrees)`: the points inside the box, each assigned to a square cell by `floor(coordinate / cellDegrees)`, then one window pass per cell for the count of plotted points, the centroid, and the rank of each point by newest capture time (nulls last on both engines, through a `CASE`) then ID; the rank-one row is the cell and its asset **represents** it, so the thumbnail standing for a cell is stable across pans. A cell of one point carries that point's asset. `SearchService.cellDegrees(zoom)` is `360 / 2^zoom / 4` degrees - a quarter tile, about 64 pixels - with the zoom clamped to 0..20. `floor` is built into both engines (SQLite is compiled with its math functions).
- `mapLocations(query, bbox)`: a typed query for the Locations pinned inside the box that hold at least one matching asset, with that count (a correlated count over `matching`, so it agrees with the grid scoped to the Location) and the category's name.
- `mapBounds(query)`: `min`/`max` of both coordinates and the count of every plotted point of the search, not clipped, for fitting the map to a result; `None` when nothing is plotted. Points on both sides of the antimeridian span the whole longitude range.

`LibraryService.mapCells(query, bbox, zoom)` returns `MapCells` (the cells and the Locations, read in one read-only transaction) and `LibraryService.mapBounds(query)` an `Option[MapBounds]`; both resolve the folder scope like `search`. The models (`MapCell`, `MapLocation`, `MapCells`, `MapBounds`, `GeocoderResult`) are plain case classes: the JSON shape is the controller's.

`GeocoderService` is the place-name search behind the Add Location dialog, proxied through the server so the browser never talks to the geocoder and the identifying `User-Agent` the Nominatim policy asks for is set in one place. It is **off by default** (`map.geocoder.enabled`; `search` throws `IllegalOperationException` when disabled) because every query is sent to a third party; `map.geocoder.url` is a Nominatim-compatible endpoint (`?q=&format=json&limit=5`, a list of `display_name`/`lat`/`lon`). A blank query asks nothing; a place without coordinates is skipped; an unreachable endpoint, a non-200 answer or a body that is not a list is a `GeocoderException`. `map.tile.url` and `map.tile.attribution` (`Const.Conf`) are the basemap the pin editor and the map view render with; the OSM defaults and the privacy trade-off of each key are commented in `reference.conf`.

`MapController` exposes `/api/map/r/:repoId/cells?viewport=s,w,n,e&zoom=n` (`cells`, `locations`, `countsPlottedPoints: true`), `/bounds` (the four bounds and plotted count, or `{count: 0}`), and `/geocode?q=` (camelCase `{label, latitude, longitude}` entries). Cells and bounds take the shared scope and, through `cask.QueryParams`, accept the client's search parameters verbatim: the ones that only shape a grid (`sort`, `layout`, grouping, paging) and the `bbox` filter are ignored, so the map behind an open panel plots the whole search. `viewport` (`Api.Field.Map`) is the map's own clipping box, passed separately to the service and never an asset filter: a visible Location counts every member of the search even when its own GPS point is outside the viewport. A missing or malformed `viewport` and a non-integer `zoom` are JSON 400s naming the parameter; out-of-range integer zooms use the service's 0..20 clamp. JSON routes build their responses with `BaseController.jsonResponse` / `jsonError`. The geocoder is a JSON 404 when disabled and a JSON 502 on an upstream `GeocoderException`. All Location and Map routes require login.

## Schema migrations

`schemaVersion` in `Altitude.scala` is the current version. A fresh database (version 0) runs `migrations/<engine>/all.sql` once and is stamped with the current version. `MigrationService` can still run `migrations/<engine>/<version>.sql` for each version an existing database is behind, but no such files exist: the rule in force (root `AGENTS.md`) is that every schema change goes into both `all.sql` files as original definitions, `schemaVersion` stays 2, and a database is recreated from scratch.

## Config & Environments

`ENV` env var controls mode (`dev` | `test` | `prod`). Config resolution order:
- **dev**: `application-dev.conf` → `reference.conf` (defaults in `altitude/resources/`)
- **prod**: `application.conf` → `reference.conf`
- **test**: system env overrides → `altitude/test/resources/reference.conf`

To skip login during frontend dev work, set `dev.user` and `dev.password` in `application-dev.conf`.

## Build & Test Commands

```sh
make compile              # mill altitude.compile – always do this after editing
make watch                # hot-reload dev server (ENV=dev)
make lint                 # scalafmt + scalafix + prettier/eslint (eslint.config.js: no-undef, no-unused-vars, import/no-cycle)
make test-sqlite          # safe: no Postgres needed
make test-controllers     # safe: controller-only tests
make test-psql            # integration tests against the Postgres test container (docker compose, port 5433)
make test-focused-sqlite  # run tests tagged `Focused` against SQLite only
make publish              # fat JAR → target/
```

> **Do not run `make test`** (requires a live Postgres container). Use `make test-sqlite` and `make test-controllers`, and `make test-psql` when the `altitude-core-postgres-test` container is up.
> **Do not run tests if only the frontend was changed** (Twirl templates, CSS, JS, HTML) — these can be manually verified in the browser without running the full test suite.

To focus a test, tag it with the `Focused` tag:
```scala
test("my wip test", Focused) { ... }
```

## Test Structure

Integration tests extend `IntegrationTestCore`. `beforeEach` auto-creates a fresh repository and file-store directory. Tests run against both DB engines via `SqliteSuiteBundle` / `PostgresSuiteBundle` without code changes. `TestContext.setAssetDates` rewrites an asset's capture and import timestamps in each engine's storage form for date-dependent fixtures, and `TestContext.setAssetCoordinates` gives an asset a point for map and bounding-box fixtures. Controller tests run against SQLite through a real HTTP server (`ControllerTestCore`).

## Key Files to Know

| File | Why |
|---|---|
| `altitude/src/altitude/core/App.scala` | Route registration |
| `altitude/src/altitude/core/Altitude.scala` | All wiring (DAOs, services, config) |
| `altitude/src/altitude/core/RequestContext.scala` | Thread-local connection/user/repo |
| `altitude/src/altitude/core/routes/decorators.scala` | Auth + repo-context decorators |
| `altitude/src/altitude/core/transactions/TransactionManager.scala` | DB connection lifecycle |
| `altitude/src/altitude/core/dao/jdbc/BaseDao.scala` | DAO base + query runner |
| `altitude/src/altitude/core/service/BaseService.scala` | Service base + tx wrapping |
| `altitude/src/altitude/core/service/ImportPipelineService.scala` | Pekko import pipeline |
| `altitude/resources/reference.conf` | Default config values |
| `views/` | Twirl templates (edit here, not in `out/`) |

