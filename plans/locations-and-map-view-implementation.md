# Locations + Map View — implementation plan

Status: **Units 1 and 2 implemented 2026-09-13 (Unit 2 uncommitted); Units 3–9 not started.** This expands §7 of
[locations-and-map-view.md](locations-and-map-view.md) (the library evaluation and the decisions
D1–D20) into units of work.

Read first: `AGENTS.md`, `altitude/AGENTS.md`, `altitude/views/AGENTS.md`, and
[done/albums.md](done/albums.md), whose shape (model, DAO, service, HTMX dialogs, client-rendered
sidebar list, drag/drop, membership API) every Location piece copies.

## Deviations from the decisions document (please veto if wrong)

These came out of reading the code; each is the smallest change that keeps the decision's intent.

1. **`view=map` (D14) becomes `layout=map`.** `view` is already the search parameter for the asset
   state (`repository` | `triage` | `trashbin`): the server maps it to `is_recycled`/`is_triaged`
   predicates, the client derives the `currentView` store, the nav highlight and the `#content`
   tint from it. A map is orthogonal to that (you can look at the trash on a map). So the map is a
   second parameter, `layout` (`grid`, the default, or `map`), remembered in `localStorage` and
   carried in the URL exactly as D14 intends.
2. **"Add to Location" on the asset context menu (D9) is deferred.** There is no per-asset context
   menu in the app today (adding to an album is drag/drop only). This plan puts "Add to Location"
   in the batch footer, which covers a single asset once selected. A per-asset ⋯ menu is a new
   component and is listed under *Not in this phase*.
3. **D7 has a cost the decisions did not mention:** an asset in several Locations renders as
   several cells in one grouped grid. Cell IDs, selection painting, grid removal and the detail
   navigator all assume one cell per asset. Unit 7 makes them tolerate duplicates.
4. **Crowded-pin panel (D10) is a search, not a `getLeaves` walk.** Because cells are aggregated on
   the server (D19), the browser never has the leaves. The panel is the ordinary results grid
   rendered into a side panel with a new `bbox` search filter (the cell's bounds); infinite scroll,
   selection, the detail modal, and drag to a Location row all work in it unchanged, and "show
   only these in the grid" is just `layout=grid` with the same `bbox`. supercluster's job is then
   only to merge server cells that overlap on screen.
5. **supercluster is ISC-licensed**, not MIT as the evaluation table said. Still OSI, still fine.

---

## Decisions made in this plan

1. **D1, one table.** `location` has a nullable `parent_id` and a `kind` (`parent` | `location`).
   One row type means one uniqueness index for the shared name pool (D1), one model, one DAO, and
   the group header / dropdown label (`Parent › Location`) is a self-join. Two tables would need a
   cross-table uniqueness check the DB cannot express. CHECK constraints keep the two kinds
   honest (a parent has no pin, no radius, no parent; a Location has a pin); "a Location's parent
   must be a parent" is a service check.
2. **D2 is explicit, not an `ON DELETE SET NULL`.** `LocationService.deleteById` re-parents the
   children to root in the same transaction, then deletes. The foreign key stays at its default
   (NO ACTION), so a delete that forgot the re-parent fails instead of silently orphaning.
3. **Group by Location is its own statement**, `SearchQueries.groupedByLocation`, beside
   `grouped`, not a generalization of it. Its shape differs on every axis (asset × Location join,
   a two-column group key, fixed order, a trailing "No location" group regardless of direction,
   names to render). Both share `matching`, `sortColumn`, `secondarySort`, the total CTE and the
   `afterBySort` cursor fragment. The day statement, which is tuned and benchmarked, is not
   touched beyond the group-key type.
4. **Group order for Location** is by *path*: `COALESCE(parent.name_lc, location.name_lc),
   location.name_lc`. Root Locations and parents therefore interleave alphabetically at the top
   level, which is also the order the sidebar shows. Names are unique across the pool, so the pair
   is a total order. "No location" is last in every case.
5. **Cursor v4.** `SearchCursor.day: Option[LocalDate]` becomes `key: Option[String]` (the ISO day,
   or the Location path key) plus `groupId: Option[String]` (the Location ID, absent for days).
   `VERSION` goes to 4 and the "Unsupported cursor version" test moves with it.
6. **Plotted point of an asset (D12):** its own coordinates when present; otherwise the pin of
   *each* Location it belongs to (consistent with D7). A cell counts plotted points, not distinct
   assets; the response says so.
7. **Cell size** is `360 / 2^zoom / 4` degrees on both axes (a quarter tile, ~64 px at the requested
   zoom). The representative asset of a cell is its newest by `original_created_at` (then `id`),
   through one window-function pass, so a cell's thumbnail is stable across pans.
8. **Geocoder (D11) is proxied by the server**, `GET /api/map/r/:repoId/geocode?q=`. The server can
   send the identifying `User-Agent` the Nominatim policy asks for, the config gate is enforced in
   one place, and the browser never talks to a third party except for tiles.
9. **Leaflet is loaded as a plain script** (`window.L`, like `interact`), supercluster too
   (`window.Supercluster`); both only on `index.scala.html`. No default marker images are needed:
   every pin is an `L.divIcon`.
10. **Add Location is a modal** (it holds a map); Add parent, Rename, Delete and Move to parent are
    inline dialogs like the album ones.

---

## Data model

Both `all.sql` files change together; no `<version>.sql`, no `schemaVersion` bump.

```sql
-- asset: parsed on import only (D5); NULL when the file carried no usable GPS
latitude  DOUBLE PRECISION,   -- SQLite: REAL
longitude DOUBLE PRECISION,
CREATE INDEX asset_geo ON asset (repository_id, is_recycled, is_pipeline_processed, latitude, longitude)
  WHERE latitude IS NOT NULL;

-- location: parents and Locations in one uniqueness pool (D1)
CREATE TABLE location (
  id CHAR(36) PRIMARY KEY,
  repository_id CHAR(36) REFERENCES repository (id) ON DELETE CASCADE,
  parent_id CHAR(36) REFERENCES location (id),          -- NULL = root; NO ACTION on delete (see decision 2)
  kind VARCHAR(16) NOT NULL,                             -- 'parent' | 'location'
  name VARCHAR(255) NOT NULL,
  name_lc VARCHAR(255) NOT NULL,
  latitude DOUBLE PRECISION,
  longitude DOUBLE PRECISION,
  radius_m INT,                                          -- D4: stored, unused this phase
  CHECK (kind IN ('parent', 'location')),
  CHECK ((kind = 'parent' AND parent_id IS NULL AND latitude IS NULL AND longitude IS NULL AND radius_m IS NULL)
      OR (kind = 'location' AND latitude IS NOT NULL AND longitude IS NOT NULL)),
  CHECK (latitude BETWEEN -90 AND 90), CHECK (longitude BETWEEN -180 AND 180), CHECK (radius_m > 0)
) INHERITS (_core);                                      -- SQLite: explicit created_at/updated_at
CREATE UNIQUE INDEX location_01 ON location (repository_id, name_lc);
CREATE INDEX location_02 ON location (repository_id, parent_id);

-- location_asset: pointers only, like album_asset (no updated_at on SQLite, same as album_asset)
CREATE TABLE location_asset (
  repository_id CHAR(36) REFERENCES repository (id) ON DELETE CASCADE,
  location_id CHAR(36) NOT NULL REFERENCES location (id) ON DELETE CASCADE,
  asset_id CHAR(36) NOT NULL REFERENCES asset (id) ON DELETE CASCADE
) INHERITS (_core);
CREATE UNIQUE INDEX location_asset_01 ON location_asset (location_id, asset_id);
CREATE INDEX location_asset_02 ON location_asset (asset_id);
```

`RowColumnTests` renders every row class against both schemas, so `AssetRow`, `LocationRow` and
`LocationAssetRow` must match the DDL column for column.

---

## Unit 1 — Schema, rows, GPS on import

Server-side, TDD. **Done 2026-09-13**, with two findings the plan did not anticipate:
- metadata-extractor's DMS description has an integer degree part, so a coordinate between -1 and 0
  (London's longitude) is described without its sign. The resolver therefore takes the hemisphere
  from the `GPS Latitude Ref` / `GPS Longitude Ref` tags whenever they are present and falls back
  to the description's sign only without them. A fifth fixture, `gps-sub-degree-west.jpg`
  (51.5007, -0.1276), covers it.
- The null-description hazard was real: importing a JPEG whose GPS tags lack a ref crashed on JSON
  serialization before this unit. `MetadataExtractionService` now skips such tags.
- The fixtures were made from `images/6.jpg` with Pillow by writing a GPS IFD (tags 1–4) and are
  exercised through metadata-extractor only. The parent-vs-Location CHECK is split into two
  single-line constraints so `RowColumnTests`' DDL parser reads the table cleanly.

Files
- `altitude/resources/migrations/{postgres,sqlite}/all.sql`: the DDL above.
- `dao/sql/tables/AssetRow.scala`: `latitude: T[Option[Double]]`, `longitude: T[Option[Double]]`.
- `dao/sql/tables/LocationRow.scala`, `LocationAssetRow.scala` (new; copy `AlbumRow` /
  `AlbumAssetRow`, no column-name overrides).
- `models/Asset.scala`: `latitude: Option[Double] = None`, `longitude: Option[Double] = None`.
- `FieldConst.Asset`: `LATITUDE`, `LONGITUDE`. `FieldConst.Location` (new): `PARENT_ID`, `KIND`,
  `NAME`, `NAME_LC`, `LATITUDE`, `LONGITUDE`, `RADIUS_M`, `LOCATION_ID`, `ASSET_ID`,
  `NUM_OF_ASSETS`, `PARENT_NAME`.
- `dao/jdbc/AssetDao.scala`: `add` binds the two columns (`Any` values, `orNull`, like `capture`);
  `toModel` and `makeModel` read them (`Option(rec(...)).map(_.asInstanceOf[Double])`).
- `util/GeoLocationResolver.scala` (new, the `CaptureDateResolver` shape): a pure
  `resolve(extractedMetadata: ExtractedMetadata): Option[GeoPoint]` that parses the `"GPS"`
  directory's `GPS Latitude` / `GPS Longitude` **descriptions** stored in `extracted_metadata`
  (metadata-extractor writes them as signed DMS strings derived from `getGeoLocation()`, so the
  ref is already applied), rejects unparseable strings, out-of-range values and 0/0. Replayable
  from persisted inputs, so a later backfill needs no file. `GeoPoint(latitude, longitude)` is a
  small case class in `models/`.
- `pipeline/flows/ExtractMetadataFlow.scala`: one more line, `GeoLocationResolver.resolve(extractedMetadata)`
  into `asset.copy(latitude = …, longitude = …)`, with a `debugInfo` line.
- `service/MetadataExtractionService.scala`: a `null` tag description (which is what a missing ref
  produces for the GPS coordinate tags) must not land in the map — skip the tag. This is the one
  hazard the exploration flagged; verify it with the missing-ref fixture.

Fixtures (none exist with GPS): four JPEGs under `altitude/test/resources/import/images/exif/`,
derived from `images/6.jpg` with Pillow (available: `python3` + PIL 10.2): `gps-north-east.jpg`,
`gps-south-west.jpg` (same magnitudes, refs S/W), `gps-no-ref.jpg` (coordinates, no ref tags),
`gps-zero.jpg` (0/0). Round-trip each through metadata-extractor in the test, not through PIL.

Tests
- `unit/GeoLocationResolverTests` (register in `AllUnitTestSuites`): N/E, S/W, missing, garbage,
  0/0 → `None`, ranges.
- `integration/MetadataParserTests`: extract each fixture, assert the resolved point (decimal
  degrees to 1e-4) and `None` for no-ref and zero.
- `integration/AssetDateStorageTests` sibling ("Coordinates round-trip and stay null"): persist via
  `DAO.asset.add`, read back through the service on both engines.
- `integration/ImportPipelineServiceTests`: import `gps-north-east.jpg` through the pipeline,
  assert the stored asset has coordinates.
- `unit/RowColumnTests`: add the three row classes.

## Unit 2 — Location model, DAO, service

**Done 2026-09-13**, with three findings:
- The path order as written (`COALESCE(p.name_lc, l.name_lc), l.name_lc`) puts a Location named "Alba" before its parent
  "Italy". `getAll` orders by the path key, then top-level rows before children, then name, so a parent always directly precedes
  its Locations. The test "listed by path" pins it.
- `Columns.literal` had no way to bind a `NULL`, which a move to the top level and `moveChildrenToRoot` need through the typed
  `updateById` / `updateByQuery` path. It now accepts an `Option` in a `SET` (`None` renders `NULL`); no hand-written `UPDATE`.
- `LocationService.getById` is overridden to be repository-scoped, and every mutation goes through it, which is what makes the
  "foreign IDs change nothing" test pass without a check in each method. `getByIds` was not needed and is not on the trait.

Files
- `models/LocationKind.scala` (new): `enum LocationKind(val dbValue: String)` with `fromDbValue`
  and a `bimap` codec, like `CaptureDateSource`.
- `models/Location.scala` (new): `Location(id, name, kind, parentId: Option[String], latitude:
  Option[Double], longitude: Option[Double], radiusM: Option[Int], numOfAssets: Int = 0,
  parentName: Option[String] = None) extends BaseModel with NoDates`. Constructor validation:
  non-empty name; a parent has no pin/radius/parent; a Location has a pin in range; radius > 0.
  `nameLowercase` as in `Album`. `parentName` is read-only, filled by `getAll`.
- `dao/LocationDao.scala` (trait) and `dao/jdbc/LocationDao.scala` (engine mixin at wiring, like
  the album DAO): `add`, `getAll` (self-join for `parent_name`, correlated membership count,
  ordered by path: `COALESCE(p.name_lc, l.name_lc), l.name_lc`), `addAssets` (the album
  `INSERT … SELECT … NOT EXISTS` shape, returns how many were new), `removeAssets`,
  `removeAssetsFromAllLocations`, `getAssetIds`, `moveChildrenToRoot(parentId)`,
  `getByIds`. Kind is a `location` column, so `updateById` covers rename and move.
- `service/LocationService.scala`: `addLocation(name, latitude, longitude, parentId, radiusM)`,
  `addParent(name)`, `getAll`, `rename(id, name)`, `moveToParent(id, parentId: Option[String])`
  (target must exist, be a parent, and the moved row must be a Location; `IllegalOperationException`
  otherwise), `deleteById` (a parent: `moveChildrenToRoot` first; either kind: hard delete,
  `NotFoundException` on 0 rows), `addAssets`, `removeAssets`, `removeAssetsFromAllLocations`,
  `getAssetIds`. Duplicate names surface as `DuplicateException` through `BaseService.add` /
  `updateById` (both kinds share `location_01`). Membership is only offered on Locations, never
  parents (`IllegalOperationException`).
- `service/LibraryService.recycleAssets`: `location.removeAssetsFromAllLocations(...)` beside the
  album call (D6). Restore and move do nothing, purge cascades.
- `Altitude.scala`: `DAO.location`, `service.location`.
- `Api.Field.Location` (`LOCATION_ID`, `NAME`, `PARENT_ID`, `LATITUDE`, `LONGITUDE`, `RADIUS_M`),
  `Api.Constraints.MAX/MIN_LOCATION_NAME_LENGTH` (same as albums), `Const.UI` dialog titles.

Tests — `integration/LocationServiceTests` (register in `AllIntegrationTestSuites`), on both
engines: trim/empty/duplicate (parent vs Location share the pool, case-insensitive, on add and
rename), pin validation, add under a parent, cannot parent to a Location or to itself, cannot
nest parents, move to parent and back to root, delete a parent moves children to root, delete a
Location drops memberships and leaves assets alone, add assets (idempotent, recycled and foreign
IDs skipped, parent refused), remove assets, recycle drops memberships and restore does not
re-add, purge cascades, `getAll` order and counts and `parentName`, repository isolation (the
`docs/test-coverage.md` gap: foreign IDs on every mutation change nothing).

## Unit 3 — Search: filters, count, group by Location, cursor v4

Files
- `util/SearchQuery.scala`: `locationIds: Set[String]`, `bbox: Option[BoundingBox]` (new small
  case class in `util/`: south, west, north, east; `parse("s,w,n,e")` validates ranges and
  handles `west > east` as an antimeridian split). Both in `copyWith`, `toString`, and the
  `SearchCursor.scopeFingerprint` description list.
- `util/SearchGrouping.scala`: `GroupBy` gains `Location("location")`; the date column moves to
  `dateField: Option[String]` (`Some(ORIGINAL_CREATED_AT)` for `DateTaken`, `None` for Location).
  `SearchGrouping` keeps `direction`; for Location the controller rejects an explicit
  `groupDirection` (400) and the order is fixed.
- `util/SearchCursor.scala`: `key: Option[String]`, `groupId: Option[String]`, `VERSION = 4`.
- `util/GroupedSearchResult.scala`: `GroupedSearchRow(asset, group: SearchGroupKey, sortValue,
  groupTotal)`; `SearchGroupKey` is an enum: `Day(date: Option[LocalDate])` and
  `Location(id: Option[String], pathKey: Option[String], name: Option[String], parentName:
  Option[String])` (all `None` = "No location"). `AssetDateGroup` becomes `AssetGroup(key,
  total, assets)`; `groupsOf` folds on `key`.
- `dao/sql/search/SearchQueries.scala`:
  - `matching`: `.filterIf(query.locationIds.nonEmpty)(locationFilter)` — the `albumFilter`
    semi-join over `LocationAssetRow`; `.filterIf(query.bbox.isDefined)(bboxFilter)` — the
    asset's own point in the box, OR (asset has no point AND it is in a Location whose pin is in
    the box). Plain `BETWEEN` arithmetic, no dialect hook.
  - `count(engine, query, repositoryId)`: `matching(...).size`, for the map layout's total.
  - `groupedByLocation(engine, query, repositoryId)`: the same `WITH` shell as `grouped`.
    `located` = `matching` joined to `location_asset`, `location`, and a left-joined parent,
    projected to `(asset_id, location_id, path_key, location_name, parent_name, sort_value)`,
    ordered by `path_key, location_id, <secondary sort>, asset_id`, `LIMIT rpp + 1`, with the
    cursor predicate `path_key > ?k OR (path_key = ?k AND (location_id > ?g OR (location_id = ?g
    AND afterBySort)))`. `unlocated` = `matching` filtered to assets in no Location, guarded by
    the same `LIMIT CASE` trick as today's `undated` slice, always appended last. `group_counts`
    = one correlated count per distinct `location_id` on the page over `location_asset` ∩
    `matchingIds`, plus the no-location count when the page reaches it. `total` on a first page
    is still the number of matching *assets*. Rows join back to `asset` positionally as today.
    Prefer the typed ScalaSql `.join` for the candidate relation (`personFilter` already uses
    it); fall back to rendering `matchingIds` into a hand-written join only if the typed join
    cannot be ordered/limited through `Select.withExprSuffix`.
  - `SearchDialect.day` keeps its signature but takes the date field, not a `GroupBy`.
- `dao/jdbc/SearchDao.searchGrouped`: dispatch on `grouping.by`; the Location statement reads
  `(AssetRow, Option[String], Option[String], Option[String], Option[String], SortValue, Int, Int[, Int])`.
- `service/SearchService.searchGrouped`: builds the v4 cursor from the last row's key.
- `service/LibraryService`: `count(query)` (read-only, resolves the folder scope like `search`).

Tests
- `unit/SearchSqlTests`: location and bbox filters are semi-joins with bound placeholders; the
  Location statement on both dialects has every `?` bound, no `NULLS FIRST/LAST`, and the
  `unlocated` guard; `count` renders one `COUNT`.
- `unit/SearchQueryModelTests`: `BoundingBox.parse` (bad arity, ranges, antimeridian).
- `integration/SearchGroupingTests`: group by Location — order by path, `Parent › Location`
  data on the rows, an asset in two Locations appears twice, "No location" last, per-group
  totals across pages, combined with folder/album/person/location filters; `locationId` filter;
  `bbox` filter (own point, Location pin fallback, antimeridian).
- `integration/SearchCursorTests`: Location cursor traversal equals the complete order
  (including into the no-location tail), scope rejection on a changed `locationId`/`bbox`, v3
  tokens rejected.
- `docs/test-coverage.md`: note the new suites (it is a reviewed inventory; add rows, do not
  re-review).

## Unit 4 — Map queries, geocoder proxy, config

Files
- `dao/sql/search/SearchQueries.scala`: `plottedPoints(engine, query, repositoryId)` — a
  `UNION ALL` of (matching assets with their own point) and (matching assets without a point,
  joined to their Locations' pins); `mapCells(…, bbox, cellDegrees)` — the points in the bbox,
  `cell_x = floor(longitude / c)`, `cell_y = floor(latitude / c)`, one window pass
  (`COUNT(*) OVER (PARTITION BY cell_x, cell_y)`, `AVG(latitude) OVER (…)`, `AVG(longitude) OVER
  (…)`, `ROW_NUMBER() OVER (PARTITION BY … ORDER BY original_created_at DESC, id)`), filtered to
  `rn = 1`; `mapLocations(…, bbox)` — Locations in the bbox with the count of matching assets
  they hold (only those with at least one); `mapBounds(…)` — min/max of the plotted points and
  their count. `floor` exists on both engines (SQLite is built with math functions); if a dialect
  difference shows up, it goes on `SearchDialect`. The two aggregates run inside one read-only
  transaction.
- `dao/SearchDao` + `dao/jdbc/SearchDao`: `mapCells`, `mapLocations`, `mapBounds`.
- `service/SearchService` / `LibraryService`: `mapCells(query, bbox, zoom)` (cell size from
  zoom, clamped to 0..20), `mapBounds(query)`; models `MapCell(count, latitude, longitude,
  assetId)`, `MapLocation(id, name, parentName, latitude, longitude, count)`, `MapBounds(south,
  west, north, east, count)` in `models/`.
- `service/GeocoderService.scala` (new): `enabled: Boolean` from config; `search(q): List[GeoResult]`
  calls `map.geocoder.url` (`requests`-free: `java.net.http.HttpClient`, 5 s timeout, `format=json`,
  `limit=5`, `User-Agent: Altitude (+https://github.com/…)`), maps `display_name`/`lat`/`lon`.
  Throws `IllegalOperationException` when disabled.
- `Const.Conf`: `MAP_TILE_URL = "map.tile.url"`, `MAP_TILE_ATTRIBUTION = "map.tile.attribution"`,
  `MAP_GEOCODER_ENABLED = "map.geocoder.enabled"`, `MAP_GEOCODER_URL = "map.geocoder.url"`.
  `altitude/resources/reference.conf` defaults: OSM tile template, the OSM attribution string,
  `false`, `https://nominatim.openstreetmap.org/search`, each with a comment on the privacy
  trade-off (every tile request tells the tile host what you look at; the geocoder sends your
  query text). The test `reference.conf` sets `map.geocoder.enabled=false`.

Tests
- `unit/SearchSqlTests`: cells/bounds render one statement each per dialect, all `?` bound.
- `integration/SearchMapTests` (new): assets with points, assets without points in a Location,
  a Location with no matching assets is absent, cells merge same-coordinate assets, the
  representative is the newest, bounds cover both point sources, empty result → bounds `None`,
  bbox clipping, repository isolation, `is_recycled` respected via the view params.
- `integration/GeocoderServiceTests`: disabled → `IllegalOperationException`; enabled path against
  a local stub `HttpServer` (`com.sun.net.httpserver`) returning a canned Nominatim body — no
  network in tests.

## Unit 5 — Routes

Files
- `routes/SearchRequestParser.scala` (new): the parameter → `SearchQuery` scope logic currently
  inline in `SearchResultsController` (view → params, folder/person/album/location IDs, `q`,
  `bbox`), shared by the three controllers below. `SearchResultsController` keeps grouping,
  sort, paging and rendering.
- `routes/api/LocationController.scala` (`api/location`): `GET /r/:repoId/list` (flat JSON,
  camelCase, hand-built: `id, name, kind, parentId, parentName, latitude, longitude, radiusM,
  numOfAssets`, in path order), `PUT /r/:repoId/assets` `{locationId, assetIds}` → `{added}`,
  `DELETE /r/:repoId/assets` → `{removed}`.
- `routes/api/MapController.scala` (`api/map`): `GET /r/:repoId/cells?<search params>&bbox=&zoom=`
  → `{cells: [...], locations: [...], countsPlottedPoints: true}`; `GET /r/:repoId/bounds?<search
  params>` → `{south, west, north, east, count}` or `{count: 0}`; `GET /r/:repoId/geocode?q=` →
  `[{label, latitude, longitude}]`, 404 when disabled. Bad `bbox`/`zoom` are JSON 400s.
- `routes/web/partial/LocationActionController.scala` (`htmx/location`): `tab`;
  `dialogs/add-location` (modal, with the parent dropdown, the tile settings and the geocoder
  flag), `dialogs/add-parent`, `dialogs/rename-location`, `dialogs/delete-location`,
  `dialogs/move-location` (parent dropdown), `dialogs/add-to-location` (`[Parent] - Location`
  dropdown of Locations only); `POST add` (JSON: name, latitude, longitude, parentId?, radiusM?),
  `POST add-parent`, `PUT rename`, `PUT move`, `PUT assets` (JSON `{locationId, assetIds:
  "id,id"}` from the dialog's hidden field; returns empty 200), `DELETE /`. Validation and
  duplicate handling exactly as `AlbumActionController` (`DataScrubber`, `ApiRequestValidator`,
  `dialogFormValidationResponse`); latitude/longitude are validated as decimals in range with a
  field error, not a 400.
- `routes/web/partial/SearchResultsController.scala`: `locationId`, `bbox`, `layout` parameters
  (`Const.Search.Layout.GRID/MAP`, `Api.Field.Search.LOCATION_ID/BBOX/LAYOUT`); `groupBy=location`
  accepted (`groupDirection` with it → 400); in map layout grouping and paging are ignored, the
  response is `search_results` with `htmx.html.map_view(bounds, tileUrl, attribution)` as the grid
  and `count` as the total, and the Group dropdown rendered disabled; `browserViewUrl` carries the
  three new parameters.
- `App.scala`: register the three controllers.

Tests — `controller/LocationControllerTests`, `LocationActionControllerTests`,
`MapControllerTests` (register in `AllControllerTestSuites`): list shape and order, membership
endpoints against persisted membership, every dialog renders, add/rename/move/delete with
validation replacement headers and duplicate names, cells/bounds/geocode status codes and JSON,
401s. `SearchResultsControllerTests`: `groupBy=location` headers read `Parent › Location` and
"No location", `data-group-key`, cursor round-trip; `layout=map` response has `#map` and no
`#assets`, carries `data-map-bounds`, `hx-replace-url` includes `layout`, `locationId`, `bbox`;
the 400 matrix gains `groupDirection` with `location` and malformed `bbox`.

## Unit 6 — Frontend: Locations tab, dialogs, drag/drop, add to Location

Frontend, no server tests; verified in the browser.

- `views/index.scala.html`: a fourth tab `#locationsTab` (`fa-map-marker-alt`, `href="#locations"`,
  `hx-get=/htmx/location/r/:repoId/tab`).
- `views/htmx/locations.scala.html`: styles (rows are the album grid; a Location under a parent
  gets `--depth: 1` like the folder tree's trace cell), `#locationActions` (two centered
  dialog-trigger buttons: "Add location" opening the modal through `hx-target="#modalContent"`,
  "Add parent" inline), `#noLocations`, `#locationList[data-app-fragment="location-list"]`.
- `static/js/common/location-list.js` (copy of `album-list.js`): `reloadLocationList`,
  `refreshLocationCounts`, `setViewedLocation`, `focusAddLocationControlIfFocusLost`. Renders the
  flat JSON as parents with their Locations nested one level, root Locations interleaved by
  name. Parent row: menu (Rename, Delete) | icon `fa-layer-group` | name, no
  count, not a drop target, not a search trigger. Location row: menu (Rename, Delete, Move to
  parent) | `.asset-count` | `fa-map-marker-alt` | name; icon and name are
  `data-app-search-location-id` triggers; `.controls` is `.dropzone[data-location-id]`.
- `static/js/dragdrop/locations.js` + `dragdrop/index.js`: `#locationList .dropzone` accepts
  `#assets .drag-drop, #batchOps .drag-drop`, dispatches `assetAddedToLocation` /
  `batchAssetsAddedToLocation`.
- `static/js/listeners/locations.js` + `listeners/index.js`: `locationAdded`, `parentAdded`,
  `locationRenamed`, `locationMoved`, `locationDeleted` (reload, snackbar, search back to the
  repository when the viewed Location goes), `assetAddedToLocation` (escalates to the batch when
  selected), `batchAssetsAddedToLocation`, `batchAssetsRemovedFromLocation`,
  `batchAddToLocationRequested` (opens the add-to-location modal).
- `static/js/assets/asset-actions.js`: `addAssetsToLocation` / `removeAssetsFromLocation` on the
  album pattern ("Already in location" warning on 0 added), `refreshCounts` also refreshes
  Location counts; `frontend-app.js`: `reloadLocationCounts()`.
- `views/includes/batch_ops.scala.html`: "Add to location (n)" always in the non-trash footer;
  "Remove from location (n)" while `$store.searchParams.locationId` is set.
- Dialog templates under `views/htmx/`: `add_parent_dialog`, `rename_location_dialog`,
  `delete_location_dialog` (names the kind; a parent's says its Locations move to the top level),
  `move_location_dialog` (`<select>` of parents plus "(none)"), `add_to_location_dialog` (modal:
  select of Locations labelled `Parent - Location`, hidden `assetIds` field the hydrator fills from
  the selection store, `hx-put` to `/htmx/location/r/:repoId/assets`, success event
  `ASSETS_ADDED_TO_LOCATION_EVENT`). All follow the album dialogs' attributes (`data-app-fragment`,
  autofocus, return focus, success event/detail).
- `static/js/stores/search-params.js`: `locationId` in `DEFAULTS`; the mutual-exclusion table gains
  it (folder/person/album/location clear each other). `context.js`: `getCurrentLocationId()`.
  `views/includes/search_results.scala.html`: `data-results-location-id`;
  `fragments/search-results.js`: `setViewedLocation`.
- `static/js/constants.js`: the events and `attributes.locationId`, `attributes.parentId`,
  `attributes.kind`; `fragments/index.js` + `fragments/explorer.js`: the `location-list` kind and an
  `add-to-location` hydrator that fills the hidden field.

## Unit 7 — Frontend: layout toggle, map view, crowded-pin panel, location grouping in the grid

- Vendor: `static/js/lib/leaflet.js` (Leaflet 1.9.4 `dist/leaflet.js`), `static/css/leaflet.css`
  (`dist/leaflet.css`), `static/js/lib/leaflet.LICENSE`; `static/js/lib/supercluster.min.js`
  (supercluster 9.1.0 `dist/supercluster.min.js`, kdbush bundled) and `supercluster.LICENSE`.
  Rows in `static/js/lib/README.md` with the exact tarball URLs. Both scripts and the stylesheet
  are included from `index.scala.html` only.
- `views/includes/search_results.scala.html`: the toolbar grid gets a fifth cell, a two-button
  segmented `#layoutToggle` (grid / map, `data-app-search="click" data-app-search-layout="…"`,
  `aria-pressed`); the Group `<select>` gains `Location` and is `disabled` in map layout; the
  `.date-group` header becomes `.result-group` with `data-group-key` (CSS, `date-groups.js`,
  `date-group-selectable.js`, `detail-navigator.js`, `box-selection.js` selectors follow; the
  Alpine component keeps its name).
- `views/htmx/results_grid_grouped.scala.html`: header text by key: a `<time>` for a day,
  "No date"; for a Location `Parent › Location` (`<span class="parent">` + `›` + name), "No
  location". The cell's `id` is `asset-<id>` for day/ungrouped grids and `asset-<id>-in-<locationId>`
  in the Location grid (`result_cell` gets a `cellId` parameter); `data-asset-id` stays.
- Duplicate cells (deviation 3): `selection.js` paints `[data-asset-id="…"] .drag-drop` (all
  copies); `removeAssetsFromGrid` removes every cell of the asset and decrements each header it
  leaves; `detail-navigator.js` remembers the cell element it opened from (the `hx-get` source),
  not the asset ID, and steps from that element.
- `views/htmx/map_view.scala.html` (new): `<div id="map" data-app-fragment="map-view"
  data-map-bounds="s,w,n,e" data-map-count data-map-tile-url data-map-attribution>` and
  `<aside id="mapPanel" hidden>` with a header (`<n> items here`, a "Show only these in the grid"
  button, close) and a `#mapPanelContent` host. CSS in the template: `#content` is the split pane,
  so the map fills it (`height: 100%`, the aside as an overlay column on the right, both under
  `--view-tint`).
- `static/js/map/map-view.js` (new fragment hydrator, registered in `fragments/index.js`):
  creates the Leaflet map (tile layer from the data attributes, attribution control, no default
  marker icons), restores the last center/zoom from `map-state.js` when the search scope
  fingerprint is unchanged else `fitBounds` the server bounds (world view when there are none),
  `invalidateSize()` after the fragment settles and on `#content` resize (the Split.js drag),
  requests `/api/map/r/:repoId/cells` with the store's search parameters (minus `layout`,
  `groupBy`, `bbox`, paging) plus the viewport bbox and zoom on `moveend` (debounced 150 ms,
  superseded responses dropped by sequence), loads the cells into a supercluster index (radius
  60, `map`/`reduce` summing counts and keeping the newest representative) and renders the
  clusters for the viewport as `L.divIcon` markers: a single-asset cell is a thumbnail pin
  (`/content/r/:repoId/preview/:assetId`, `hx-get` of the asset-detail modal into
  `#imageDetailModalContent`, processed with `htmx.process`) — D15; a crowded pin is the
  representative thumbnail with a count badge (`.drag-count-badge` styling) — D10; a Location is a
  distinct pin (marker glyph + name, `--dnd-drop-target-color`) whose click scopes the search to
  it (`runSearch({params: {locationId}})`) — D12/D18. Crowded pin click: if the cluster's
  expansion zoom is below the max, `flyTo` that zoom; otherwise open the panel.
- `static/js/map/map-panel.js`: opens `#mapPanel` and runs the ordinary search into
  `#mapPanelContent` with `runSearch({params: {bbox}, transient: {layout: "grid", groupBy: null,
  groupDirection: null}, target: "#mapPanelContent"})` — the bbox is a real store parameter, so the
  panel's infinite scroll and cursor continuation work unchanged and the URL is bookmarkable; the
  map's own cell requests deliberately omit `bbox`, so the map keeps showing the whole scope.
  "Show only these in the grid" → `runSearch({params: {layout: "grid"}})`; a toolbar chip
  (`#bboxScope`, "Map area ×") clears `bbox` in both layouts. Closing the panel clears `bbox`.
- `static/js/map/map-state.js`: center/zoom in memory keyed by the scope fingerprint the fragment
  carries (`data-results-*`), so a re-render for a sort change does not reset the view.
- Layout persistence (D14): `Const.localStore.resultsLayout`; the store's `merge` writes it when
  `layout` changes; `index.scala.html` seeds the store from the URL first, then `localStorage`.
  `search-params.js`: `layout` and `bbox` in `DEFAULTS` (grid / null); `bbox` is cleared by any
  scope change except `layout` and `sort`.
- `fragments/search-results.js` already skips grid bindings when `#assets` is absent; it
  additionally calls the map hydrator through the fragment registry, and `bindBoxSelection`
  is skipped in map layout.
- Global Escape (`global.js`) closes the map panel after the modal/menu rules, before the
  broadcast.

## Unit 8 — Frontend: Add Location modal and geocoder

- `views/htmx/add_location_dialog.scala.html` (modal, `data-app-fragment="modal"`,
  `data-app-modal-title`): name, parent `<select>`, a `#locationEditorMap` (300 px tall),
  latitude / longitude `<input type="number" step="any">`, radius (m, optional), and — only
  when the geocoder is enabled — a search box with a results list. `hx-post` +
  `hx-json-enc`, success event `LOCATION_ADDED_EVENT`; validation replaces the form in place
  (the map is re-created by the hydrator after the swap, centred on the submitted values).
  `#modalContent:has(.location-editor)` widens `--modal-content-width` to 720 px, capped by the
  viewport.
- `static/js/fragments/location-editor.js` (kind `location-editor`, registered in
  `fragments/index.js`): Leaflet map in the modal (tile settings from data attributes),
  initial view = the current results map view when one is open, else the world; click places or
  moves a draggable pin and writes the fields; editing a field moves the pin; the geocoder box
  queries `/api/map/r/:repoId/geocode` (debounced, Enter or button), a result click places the
  pin and fills the name when it is empty; `invalidateSize()` once the modal is visible. Alpine's
  `x-trap` must not swallow map keyboard handling: the map container gets `tabindex="-1"`.
- Not a menu action: editing a Location's pin after creation is not in D9 and is listed under
  *Not in this phase*.

## Unit 9 — Docs and verification

Docs (anti-drift rule)
- `altitude/AGENTS.md`: a **Locations** section (model, kinds, membership, recycle rule), the
  `locationIds`/`bbox` filters and `count`, **Group by Location** beside the date grouping
  (statement shape, cursor v4, "No location" placement), **Map** (cells/bounds/geocoder, config
  keys, cell size rule), and fix the stale *Schema migrations* paragraph, which still describes
  `<version>.sql` files (root `AGENTS.md` is the rule in force).
- `altitude/views/AGENTS.md`: the Locations tab, `locationId`/`bbox`/`layout` parameters, the
  map fragment and panel, the layout persistence, `.result-group`, duplicate cells in the
  Location grid, the location editor modal.
- `static/js/lib/README.md`: Leaflet and supercluster rows. `CONTEXT.md`: **Location**,
  **Parent**, **Plotted point**. `docs/test-coverage.md`: rows for the new suites.
- `plans/locations-and-map-view.md`: status line points here; §7 replaced by a link.

Verification
1. `make compile`, `make test-unit`, `make test-sqlite`, `make test-controllers`, `make lint`;
   `make test-psql` with the test container up (the map SQL is the part most likely to differ
   between engines).
2. Recreate the dev database (the user does this) and re-import the dev library so assets get
   coordinates; confirm `asset_geo` is used by `EXPLAIN` for a cells query on both engines.
3. Browser, dev server on :8080 (run `mill altitude.resources` after static changes; the
   automation tab is hidden, so verify through DOM APIs):

| Scenario | Expect |
|---|---|
| Locations tab, empty | centered "Add location" only; the modal opens with the map |
| Add a Location by clicking the map, then by typing coordinates, then via the geocoder (enabled in `application-dev.conf`) | pin and fields stay in sync; validation errors keep the values; the list shows it |
| Add a parent; move a Location under it via the menu; rename; delete the parent | the Location returns to root; names are unique across kinds (error in place) |
| Drop one asset, then a selection, onto a Location; footer "Add to location (n)" | counts update; "Already in location" on a repeat |
| Click a Location | grid scoped, row marked green, URL carries `locationId`, footer offers "Remove from location" |
| Group by Location | headers `Parent › Location`, an asset in two Locations appears twice, "No location" last, selecting a header selects its cells, recycling a duplicated asset removes both cells and fixes both counts, infinite scroll continues within and across groups |
| Layout → map | toolbar stays, Group disabled, map fits the results, tiles load, pins render on a blank canvas when tiles are blocked (D17) |
| Zoom in/out, pan | cells re-request and merge; single-asset pins show thumbnails; Location pins are distinct |
| Click a single pin | asset detail modal |
| Click a crowded pin at max zoom | panel with a scrollable grid, sort applies, drag from the panel to a Location works, "Show only these in the grid" switches layout with the bbox chip, closing clears it |
| Reload the page | layout and the last map view are remembered; a bookmarked `layout=map&bbox=` URL opens the same panel |
| Trash and triage views in map layout | plotted from the same scope rules |
| Folders and Albums tabs afterwards | unchanged |

---

## Not in this phase

- A per-asset ⋯ context menu ("Add to Location" on a single asset without selecting it).
- Editing a Location's pin or radius after creation; auto-assignment by radius (D4).
- Backfilling coordinates for already-imported assets (D5); `GeoLocationResolver` replays from
  `extracted_metadata`, so a later `AssetService.resolveMissingCoordinates` needs no file reads.
- Scoping the grid by a parent (all its Locations at once).
- Dragging a pin's asset from the map onto a Location row (the panel covers it).
- Self-hosted tiles: the tile URL is config, but `StaticController` has no `Range` support for
  PMTiles.
- Prev/next in the asset detail modal opened from a map pin (no grid to walk).
