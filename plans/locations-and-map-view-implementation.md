# Locations + Map View — implementation plan

Status: **Units 1–6 and 8 done; Units 7 and 9 not started.**

Read first: `AGENTS.md`, `altitude/AGENTS.md`, `altitude/views/AGENTS.md`. Every Location piece copies the album shape
(model, DAO, service, HTMX dialogs, client-rendered sidebar list, drag/drop, membership API).

## The feature

A **Location** is a named pin (a WGS84 point placed on a map) that holds pointers to any number of assets, optionally
under a **Category**: a named container, one level deep, with no pin and no assets of its own. Assets carry their own
GPS coordinates, parsed on import. Search gains a Location filter, a bounding-box filter and Group by Location. The
results area gains a **map layout** that plots the current search, aggregated on the server per viewport.

---

## Constraints from the codebase

- The frontend is vanilla ES modules + Alpine 3 + htmx 4 + interact.js, all vendored under `altitude/static/js/lib/`,
  **no bundler**. A map library must ship a single-file UMD or ESM build plus its CSS.
- Sidebar tabs (Folders, Albums, People, Locations) are HTMX partials whose rows are built in JS; context menus are
  native `popover` panels built in JS (`common/context-menu-markup.js`); drag/drop is interact.js (`dragdrop/*.js`).
- Search results are **HTML only**. The map needs JSON, and a repository can hold millions of assets, so the map
  endpoints return viewport aggregates, never a result set.
- Grouping is one CTE-based statement per grouping, cursor-paginated (`dao/sql/search/SearchQueries.scala`).
- Databases: Postgres from the `pgvector/pgvector:0.8.2-pg18` image (no PostGIS; `cube`, `earthdistance`, `pg_trgm`,
  `btree_gist` are available) and SQLite through `sqlite-jdbc 3.51.2.0` (compiled with math functions, R*Tree, json1
  and FTS5). Both `all.sql` files change together; there are no numbered migrations and no `schemaVersion` bump, and
  the database is recreated from scratch after a schema change.
- Uniqueness is a unique index translated to `DuplicateException` in `BaseService` (SQLite error 19 / Postgres
  `23505`), not a service pre-check.

## Stack

**Leaflet 1.9.4 (BSD-2) + supercluster 9.1.0 (ISC) + OSM raster tiles, with the basemap in config.** Geospatial SQL
is plain arithmetic on two nullable coordinate columns, identical on both engines.

Why:
- It fits the stack: two vendored files, no bundler, an imperative API. Every pin is an `L.divIcon`, a DOM node, so
  thumbnail pins reuse the thumbnail CSS and Leaflet's marker images are never needed.
- No WebGL requirement, so the map renders in any browser or VM the app is viewed from.
- The server aggregates cells, so the browser holds at most a few hundred per viewport: DOM markers are well within
  budget, and supercluster only merges cells that overlap on screen. supercluster is also the clustering engine inside
  MapLibre, so switching renderer later leaves the clustering code alone.
- The basemap is one config key (`map.tile.url`). A self-hosted raster source, or PMTiles through `protomaps-leaflet`
  plus a `Range`-capable route, can be swapped in without touching the map code.
- Accepted risk: Leaflet 1.x is dormant and 2.0 is still an alpha. The surface used (`map`, `tileLayer`, `divIcon`,
  `marker`, `fitBounds`, `setView`, `on('click')`) has been stable for a decade, and moving that surface to 2.0 is
  mechanical (ESM import, `new`). `Leaflet.markercluster` is avoided for the same reason.
- SQL: `BETWEEN` boxes, `floor` gridding, window functions and min/max aggregates run on both engines with no
  extension. They go through the typed `SearchQueries` path, so both integration bundles test them. A composite B-tree
  is enough at this scale; an SQLite R*Tree or a Postgres GiST on `point(longitude, latitude)` can be added later behind
  the same API.

Considered, not chosen:
- **MapLibre GL JS 6.9 + built-in clustering + self-hosted Protomaps PMTiles**: the cutting-edge option. It offers a
  modern vector look, a globe, fully offline maps and smooth rendering past 100k points. It costs about 6× the payload,
  requires WebGL2, brings a style JSON to maintain, draws pins as canvas symbol layers (no DOM, so no interact.js drag
  from the map), and needs a `Range` route plus a regional extract. Revisit if offline maps or a vector look become goals.
- **OpenLayers 10**: a full GIS toolkit and the heaviest API for pins, clusters, fit-bounds and click-to-pick. Its dist
  build is second-class.
- **deck.gl 9**: data visualisation for millions of points, far beyond this need.
- **Leaflet.markercluster**: unmaintained, Leaflet-1-only, and its spiderfy does not make 200 photos at one spot readable.
- **PostGIS / SpatiaLite**: they would change the Docker image and the SQLite driver for no gain at this scale.
- **OpenFreeMap** (hosted vector tiles): it needs MapLibre or a plugin, and has the same privacy trade-off as OSM.

---

## Decisions

### Data model
- **D1 One table, one name pool.** `location` holds both kinds: `kind` is `category` | `location`, and the nullable
  self-reference is `category_id`. `UNIQUE (repository_id, name_lc)` makes a Category and a Location unable to share a
  name, case-insensitively; two tables would need a cross-table uniqueness check the database cannot express. CHECK
  constraints keep the kinds honest (a Category has no pin and no Category; a Location has a pin). "A Location's
  Category must be a Category" is a service check.
- **D2 Deleting a Category moves its Locations to the top level.** `LocationService.deleteById` does it in the same
  transaction, then deletes. The foreign key has no cascade (NO ACTION), so a delete that forgot the move fails
  instead of orphaning rows.
- **D3 Drop targets are Location rows only.** Categories hold no assets.
- **D4 A Location is a pin.** It is a point with no area, and nothing is assigned to a Location automatically.
- **D5 Asset coordinates are parsed on import only.** Nullable `latitude` / `longitude` on `asset` are filled in
  `ExtractMetadataFlow`. There is no backfill action and no manual editing; existing assets get coordinates when the
  database is recreated and the library re-imported.
- **D6 Recycle and restore mirror albums.** Recycling drops the asset from every Location in the same transaction,
  restoring does not re-add it, and purging cascades.

### Grouping
- **D7 Group by Location, multi-membership.** A row is asset × Location: an asset appears under every Location it is
  in, and group counts may sum to more than the total, which counts assets. A **"No location"** group comes last. So one
  grouped grid can hold several cells for the same asset, and cell IDs, selection painting, grid removal and the detail
  navigator have to tolerate that (Unit 7).
- **D8 Group order and header.** The Group dropdown gains *Location*. Groups are in **path order**: the Category's
  name, then the Location's. Top-level Locations and Categories interleave alphabetically, which is the order the
  sidebar lists them in. The header reads `Category › Location`, or just the Location's name at the top level. The
  order is fixed (`groupDirection` with `groupBy=location` is a 400), and grouping by Category is not offered.

### Menus and dialogs
- **D9 Menus.** Location row ⋯ menu: Rename, Delete, Move to category. Category row ⋯ menu: Rename, Delete. **Add to
  location** is in the batch footer, "Add to location (n)", which covers a single asset once it is selected. It opens
  a modal with a dropdown of Locations labelled `Category - Location` (Category omitted when absent). Add location is
  a modal because it holds a map. Add category, Rename, Delete and Move to category are inline dialogs like the album ones.
- **D10 Crowded pin.** A cluster pin is the representative thumbnail with a count badge. A click flies to the
  cluster's expansion zoom when the cluster would split there. At max zoom, or when every point shares one coordinate,
  it opens a **side panel**: the ordinary results grid scoped by a `bbox` search filter (the cluster's bounds), not a
  client-side leaf walk, since the browser never has the leaves. Infinite scroll, selection, the detail modal and drag
  to a Location row work in the panel unchanged. "Show only these in the grid" is `layout=grid` with the same `bbox`.
- **D11 Add location modal.** It holds the name, the Category dropdown and a map: a click places the pin, dragging
  moves it, and a **read-only readout** shows it. The coordinates travel in hidden inputs; they are never typed. A
  **place-name search** is config-gated (off by default, since every query goes to a third party) and proxied by the
  server (`GET /api/map/r/:repoId/geocode`). The server can send the identifying `User-Agent` the Nominatim policy asks
  for, the gate is enforced in one place, and the browser talks to no third party except the tile host.

### Map view
- **D12 What is plotted.** An asset with coordinates is plotted at its own point, and only there. One without
  coordinates is plotted at the pin of *each* Location it is in, consistent with D7. A cell counts plotted points, not
  distinct assets, and the response says so. Location pins are drawn distinctly when a matching asset belongs to them.
- **D13 Scale: multi-million assets per repository.** The browser never receives a result set.
- **D14 Map mode is `layout=map`** (`grid` is the default). `view` already names the asset state (`repository` |
  `triage` | `trashbin`), and a map is orthogonal to it: the trash can be viewed on a map. In map layout the Group
  dropdown is disabled and Sort orders the panel. The layout is carried in the URL and remembered in `localStorage`.
- **D15 A single-asset pin opens the asset detail modal.** The sidebar scope (folder, album, person, Location) still
  defines the result set.
- **D16 Basemap.** OSM raster tiles from `tile.openstreetmap.org` with attribution. The tile URL template and the
  attribution are config (`map.tile.url`, `map.tile.attribution`).
- **D17 Fallback.** Progressive enhancement only: if tiles fail, pins still render on a blank canvas, with no notice.
- **D18 Location filter.** `locationId` is a search filter (a semi-join like `albumId`). Clicking a Location row scopes
  the grid and highlights the row like an album's.
- **D19 Map data transport: viewport-driven grid aggregation in SQL.** The client sends its search parameters plus
  `viewport` and `zoom`. The server returns the cells (count, centroid, representative asset ID) and the Locations
  pinned in the viewport. A cell is `360 / 2^zoom / 4` degrees on both axes (a quarter tile, about 64 px), with the
  zoom clamped to 0..20. The representative asset is the newest by capture time (undated last), then the lowest ID, so a
  cell's thumbnail is stable across pans. Fit-to-results is one min/max aggregate. There is no geohash column. `bbox` is
  the panel's and the grid's filter only; the map endpoints and the map layout's count and bounds ignore it, so the map
  behind an open panel keeps plotting the whole search.
- **D20 Renderer.** Leaflet 1.9.4 and supercluster 9.1.0 are loaded as plain scripts (`window.L`,
  `window.Supercluster`) by `index.scala.html` only.

---

## Data model

Both `all.sql` files carry these definitions (SQLite: `REAL` for the coordinates and explicit `created_at` /
`updated_at` in place of `INHERITS (_core)`).

```sql
-- asset: parsed on import only (D5); NULL when the file carried no usable GPS
latitude  DOUBLE PRECISION,
longitude DOUBLE PRECISION,
CREATE INDEX asset_geo ON asset (repository_id, is_recycled, is_pipeline_processed, latitude, longitude)
  WHERE latitude IS NOT NULL;

-- location: Categories and Locations in one name pool (D1)
CREATE TABLE location (
  id CHAR(36) PRIMARY KEY,
  repository_id CHAR(36) REFERENCES repository (id) ON DELETE CASCADE,
  -- NULL = top level. No cascade: deleting a Category moves its Locations to the top level first (LocationService).
  category_id CHAR(36) REFERENCES location (id),
  kind VARCHAR(16) NOT NULL,
  name VARCHAR(255) NOT NULL,
  name_lc VARCHAR(255) NOT NULL,
  latitude DOUBLE PRECISION,
  longitude DOUBLE PRECISION,
  CHECK (kind IN ('category', 'location')),
  CHECK (kind <> 'category' OR (category_id IS NULL AND latitude IS NULL AND longitude IS NULL)),
  CHECK (kind <> 'location' OR (latitude IS NOT NULL AND longitude IS NOT NULL)),
  CHECK (latitude BETWEEN -90 AND 90),
  CHECK (longitude BETWEEN -180 AND 180)
) INHERITS (_core);
CREATE UNIQUE INDEX location_01 ON location (repository_id, name_lc);
CREATE INDEX location_02 ON location (repository_id, category_id);

-- location_asset: pointers only, like album_asset
CREATE TABLE location_asset (
  repository_id CHAR(36) REFERENCES repository (id) ON DELETE CASCADE,
  location_id CHAR(36) NOT NULL REFERENCES location (id) ON DELETE CASCADE,
  asset_id CHAR(36) NOT NULL REFERENCES asset (id) ON DELETE CASCADE
) INHERITS (_core);
CREATE UNIQUE INDEX location_asset_01 ON location_asset (location_id, asset_id);
CREATE INDEX location_asset_02 ON location_asset (asset_id);
```

Each kind rule is a single-line CHECK, so `RowColumnTests`' DDL parser reads the table; it renders `AssetRow`,
`LocationRow` and `LocationAssetRow` against both schemas column for column.

---

## Unit 1 — Schema, rows, GPS on import

**Done.** Server-side, TDD.

Files
- `altitude/resources/migrations/{postgres,sqlite}/all.sql`: the DDL above.
- `dao/sql/tables/AssetRow.scala`: `latitude`, `longitude: T[Option[Double]]`. `LocationRow.scala` and
  `LocationAssetRow.scala`: the `AlbumRow` / `AlbumAssetRow` shape, no column-name overrides.
- `models/Asset.scala`: `latitude`, `longitude: Option[Double] = None`. `models/GeoPoint(latitude, longitude)`.
- `FieldConst.Asset`: `LATITUDE`, `LONGITUDE`. `FieldConst.Location`: `CATEGORY_ID`, `KIND`, `NAME`, `NAME_LC`,
  `LATITUDE`, `LONGITUDE`, `LOCATION_ID`, `ASSET_ID`, `NUM_OF_ASSETS`, `CATEGORY_NAME`.
- `dao/jdbc/AssetDao.scala`: `add` binds the two columns; `toModel` and `makeModel` read them.
- `util/GeoLocationResolver.scala` (the `CaptureDateResolver` shape): a pure
  `resolve(extractedMetadata): Option[GeoPoint]` over the `GPS` directory of `extracted_metadata`. metadata-extractor
  stores `GPS Latitude` / `GPS Longitude` as signed degrees-minutes-seconds descriptions, beside the
  `GPS Latitude Ref` / `GPS Longitude Ref` tags. The degree part is an integer, so a value between -1 and 0 (London's
  longitude) is described without its sign. The ref therefore decides the hemisphere whenever it is present, and the
  description's sign only without it. Unparseable strings, out-of-range values and 0/0 resolve to `None`. The resolver
  replays from persisted inputs, so a later backfill needs no file.
- `pipeline/flows/ExtractMetadataFlow.scala`: resolves the point into the asset's coordinates, with a debug line.
- `service/MetadataExtractionService.scala`: skips a tag whose description is `null`, which is what a GPS coordinate
  without its ref produces; the JSON column cannot hold a null.

Fixtures: five JPEGs under `altitude/test/resources/import/images/exif/`, made from `images/6.jpg` with Pillow by
writing a GPS IFD (tags 1–4) and exercised through metadata-extractor only: `gps-north-east.jpg`,
`gps-south-west.jpg` (same magnitudes, refs S/W), `gps-sub-degree-west.jpg` (51.5007, -0.1276), `gps-no-ref.jpg`
(coordinates, no ref tags), `gps-zero.jpg` (0/0).

Tests
- `unit/GeoLocationResolverTests`: N/E, S/W, sub-degree west, missing, garbage, 0/0, ranges.
- `integration/MetadataParserTests`: each fixture's resolved point (to 1e-4), `None` for no-ref and zero.
- `integration/AssetDateStorageTests` "Coordinates round-trip through storage and stay null when absent", on both engines.
- `integration/ImportPipelineServiceTests` "Pipeline stores the coordinates a photo carries".
- `unit/RowColumnTests`: the three row classes.

## Unit 2 — Location model, DAO, service

**Done.** Server-side, TDD.

Files
- `models/LocationKind.scala`: `enum LocationKind(val dbValue: String)` (`Category` → `"category"`, `Location` →
  `"location"`) with `fromDbValue` and a `bimap` codec, like `CaptureDateSource`.
- `models/Location.scala`: `Location(id, name, kind, categoryId: Option[String], latitude: Option[Double],
  longitude: Option[Double], numOfAssets: Int = 0, categoryName: Option[String] = None) extends BaseModel with NoDates`.
  Constructor validation: a non-empty name; a Category has no pin and no Category; a Location has a pin; both
  coordinates in range (NaN rejected). `nameLowercase` as in `Album`. `numOfAssets` and `categoryName` are computed on
  read, never stored.
- `dao/LocationDao.scala` (trait) and `dao/jdbc/LocationDao.scala` (engine mixin at wiring, like the album DAO):
  - `getAll`: a self-join for `category_name` and a correlated membership count, in path order:
    `COALESCE(category.name_lc, name_lc)`, then the Category before its Locations, then `name_lc`. Without the middle
    key, a Location named "Alba" would sort before its Category "Italy".
  - `addAssets` (the album `INSERT … SELECT … NOT EXISTS` shape: only existing, non-recycled assets; returns how many
    were new), `removeAssets`, `removeAssetsFromAllLocations`, `getAssetIds`, `moveChildrenToRoot(categoryId)`.
  - Rename and move go through the typed `updateById`. `Columns.literal` renders `None` as `NULL` in a `SET`, which
    moving to the top level and `moveChildrenToRoot` rely on.
- `service/LocationService.scala`:
  - `addLocation(name, latitude, longitude, categoryId)`, `addCategory(name)`, `getAll`, `rename(id, name)`.
  - `moveToCategory(id, categoryId: Option[String])`: the moved row must be a Location and the target a Category
    (`IllegalOperationException` otherwise); `None` is the top level.
  - `deleteById`: for a Category, `moveChildrenToRoot` first; either kind is a hard delete, and zero rows is
    `NotFoundException`.
  - `addAssets` / `removeAssets`: the target must be a Location. `removeAssetsFromAllLocations`, `getAssetIds`.
  - `getById` is overridden to be repository-scoped, and every mutation goes through it, so a foreign ID is
    `NotFoundException` and changes nothing.
  - Duplicate names surface as `DuplicateException` through `BaseService.add` / `updateById`; both kinds share `location_01`.
- `service/LibraryService.recycleAssets`: `location.removeAssetsFromAllLocations(...)` beside the album call (D6).
- `Altitude.scala`: `DAO.location`, `service.location`.
- `Api.Field.Location`: `LOCATION_ID`, `NAME`, `CATEGORY_ID`, `LATITUDE`, `LONGITUDE`.
  `Api.Constraints.MAX/MIN_LOCATION_NAME_LENGTH` (as albums). `Const.UI`: `ADD_LOCATION_DIALOG_TITLE`,
  `RENAME_LOCATION_DIALOG_TITLE`, `DELETE_LOCATION_DIALOG_TITLE`, `RENAME_CATEGORY_DIALOG_TITLE`,
  `DELETE_CATEGORY_DIALOG_TITLE`, `MOVE_LOCATION_DIALOG_TITLE` ("Move to category"), `ADD_TO_LOCATION_DIALOG_TITLE`.

Tests: `integration/LocationServiceTests`, on both engines:
- names: trim, blank, and duplicates across kinds, case-insensitive, on add and rename; casing-only renames
- pins: range edges, NaN, a pinless Location, a pinned or nested Category
- Categories: add under a Category; refuse a Location or the row itself as the Category; move between Categories and
  back to the top level
- deletes: deleting a Category moves its children to the top level with their memberships; deleting a Location drops
  its memberships and leaves the assets
- membership: idempotent with counts; recycled and unknown IDs dropped; a Category refused; recycling drops
  memberships, restoring does not re-add, purging cascades
- `getAll` order, counts and `categoryName`
- repository isolation: foreign IDs on every read and mutation change nothing

## Unit 3 — Search: filters, count, group by Location, cursor v4

**Done.** Server-side, TDD.

Files
- `util/BoundingBox.scala`: `BoundingBox(south, west, north, east)`, which validates ranges (NaN rejected) and
  `south <= north`. `west > east` crosses the antimeridian. `parse("s,w,n,e")`, and `toString` round-trips through it.
- `util/SearchQuery.scala`: `locationIds: Set[String]`, `bbox: Option[BoundingBox]`, in `copyWith` and `toString`.
  `SearchCursor.scopeFingerprint` includes both.
- `util/SearchGrouping.scala`: `GroupBy` has `DateTaken("dateTaken", Some(ORIGINAL_CREATED_AT))` and
  `Location("location", None)`; the date column is `dateField: Option[String]`. `SearchGrouping` keeps `direction`,
  which a Location grouping ignores.
- `util/SearchCursor.scala`: `key: Option[String]` (the ISO day, or the Location path key) and
  `groupId: Option[String]` (the Location ID). Both absent means the trailing group. `VERSION = 4`.
- `util/GroupedSearchResult.scala`:
  - `SearchGroupKey` is `Day(date: Option[LocalDate])` or `Location(id, pathKey, name, categoryName)`, all `None` for
    "No location", with `cursorKey`, `cursorGroupId` and `continues(cursor)`.
  - `GroupedSearchRow(asset, group, sortValue, groupTotal)`; `AssetGroup(key, total, assets)`; `groupsOf` folds on the key.
- `dao/sql/search/SearchQueries.scala`:
  - `matching` adds `locationFilter`, the album semi-join over `LocationAssetRow`, and `bboxFilter`. An asset is inside
    the box by its own point or, when it has none, by the pin of a Location it is in: the rule the map plots by.
    `inBox` is plain arithmetic, with the antimeridian as two longitude ranges and no dialect hook.
  - `count(engine, query, repositoryId)`: `COUNT` over `matching`, for the map layout's total.
  - `groupedByLocation(engine, query, repositoryId)` has the `WITH` shell of `grouped` over two relations:
    - `located` is `matching` joined to `location_asset`, `location` and a left-joined Category.
    - `unlocated` is `matching` less every asset in a Location.
    - The **path key** is one string, so one cursor field carries it: the Category's `name_lc` and the Location's
      joined by U+0001 (`PATH_SEPARATOR`), or the Location's `name_lc` alone at the top level. U+0001 sorts below every
      printable character, so the key orders as the pair would and cannot collide with a top-level name.
    - Order: path key, Location ID, the sort within the group (`secondarySort`), asset ID.
    - Cursor predicate: a later path key, or the same key and a later Location ID, or the same Location and
      `afterBySort`. A cursor without a group key is already in the trailing group and slices `unlocated` alone.
    - Page fill: `unlocated` fills a page only once `located` has run out, first pages included, through the same
      guarded `LIMIT CASE` as the day statement's undated slice. The `page` CTE and the final `ORDER BY` lead with
      `CASE WHEN location_id IS NULL THEN 1 ELSE 0 END`, so the trailing group is last on both engines without
      `NULLS LAST`.
    - Counts: each group is a correlated count per distinct Location on the page over `location_asset` ∩ matching IDs,
      plus the no-location count when the page reaches it. The first page's total counts matching *assets*.
  - `SearchDialect.day(asset, field)` and `secondarySort(asset, sort, grouping)` are keyed on the grouping's
    `dateField`. For a Location grouping, SQLite puts the unary `+` on every sort term, since there is no grouping index
    to protect.
- `dao/jdbc/SearchDao.searchGrouped` dispatches on `grouping.by`. `service/SearchService.searchGrouped` builds the v4
  cursor from the last row's group. `service/LibraryService.count(query)` is read-only and resolves the folder scope
  like `search`.
- `views/htmx/results_grid_grouped.scala.html` heads a Location group `Category › Location` or "No location", and
  every header carries `data-group-key`. Unit 7 restyles the header.

Tests
- `unit/SearchSqlTests`: the Location and bbox filters are bound semi-joins. On both dialects the Location statement
  has every `?` bound, no `NULLS FIRST/LAST`, and the `unlocated` guard. `count` renders one `COUNT` with no ordering
  or page.
- `unit/SearchQueryModelTests`: `BoundingBox.parse` (arity, ranges, NaN, antimeridian).
- `integration/SearchGroupingTests`:
  - group by Location: path order, `Category › Location` data on the keys, an asset under each of its Locations, "No
    location" last, group counts against the asset total, the fixed direction, the sort within a group, an empty first
    page, one statement per page
  - the `locationId` filter on flat, grouped and count
  - the `bbox` filter: own point, Location pin fallback (never the pin when the asset has a point), antimeridian
- `integration/SearchCursorTests`: Location cursor traversal equals the complete order, into the no-location tail;
  scope rejection on a changed `locationId` or `bbox`; older versions rejected.

## Unit 4 — Map queries, geocoder proxy, config

**Done.** Server-side, TDD.

Files
- `dao/sql/search/SearchQueries.scala`:
  - `plottedPoints` / `pointsCte`: a `UNION ALL` of two typed relations over `matching`, optionally clipped to a box.
    The first is matching assets at their own point; the second is matching assets without one, joined to their
    Locations' pins. Each carries every filter of the search, so the map can never disagree with the grid.
  - `mapCells(engine, query, repositoryId, bbox, cellDegrees)`:
    - An inner `gridded` CTE computes `floor(latitude / c)` and `floor(longitude / c)`, because a window cannot
      partition on an alias of its own `SELECT`.
    - One named `WINDOW w` per cell gives `COUNT(*)`, `AVG(latitude)`, `AVG(longitude)` and a `ROW_NUMBER()` ordered
      by `CASE WHEN taken IS NULL THEN 1 ELSE 0 END, taken DESC, asset_id`. The `CASE` is needed because Postgres sorts
      nulls first in `DESC`.
    - The rank-one row of each cell is the cell. `floor` is built into both engines.
  - `mapLocations(engine, query, repositoryId, bbox)`: a typed query for the Locations pinned inside the box that hold
    at least one matching asset, with that count and the Category's name. The count is a correlated
    `LocationAssetRow.select.filter(...).size` over `matching`, rendered as a scalar subquery in both the projection and
    the `> 0` filter, so it agrees with the grid scoped to the Location.
  - `mapBounds(engine, query, repositoryId)`: min/max of both coordinates and the count of every plotted point,
    unclipped. The extremes are null and the count zero when nothing is plotted.
- `dao/SearchDao` + `dao/jdbc/SearchDao`: `count`, `mapCells`, `mapLocations`, `mapBounds`.
- `service/SearchService`: `cellDegrees(zoom)` = `360 / 2^zoom / 4`, zoom clamped to 0..20; `mapCells(query, bbox,
  zoom): MapCells`; `mapBounds(query): Option[MapBounds]`.
- `service/LibraryService`: `mapCells` reads both aggregates in one read-only transaction; `mapBounds` also resolves
  the folder scope like `search`.
- `models/`: `MapCell(count, latitude, longitude, assetId)`, `MapLocation(id, name, categoryName, latitude, longitude,
  count)`, `MapCells(cells, locations)`, `MapBounds(south, west, north, east, count)`, `GeocoderResult(label, latitude,
  longitude)`. They are plain case classes; the JSON shape belongs to the controller.
- `service/GeocoderService.scala(config: Config)`:
  - `isEnabled` from `map.geocoder.enabled`. `search(text)` throws `IllegalOperationException` when disabled and
    returns nothing for blank text.
  - `java.net.http.HttpClient` with a 5 s timeout, `?q=&format=json&limit=5`, and
    `User-Agent: Altitude (+https://github.com/papito/altitude)`.
  - Maps `display_name` / `lat` / `lon` and skips a place without coordinates. An unreachable endpoint, a non-200
    answer or a body that is not a list is a `GeocoderException`.
  - Taking a `Config` rather than the app is what lets the test point it at a stub.
- `Const.Conf`: `MAP_TILE_URL`, `MAP_TILE_ATTRIBUTION`, `MAP_GEOCODER_ENABLED`, `MAP_GEOCODER_URL`.
  `altitude/resources/reference.conf`:
  - defaults: the OSM tile template and attribution, `map.geocoder.enabled=false`, and
    `https://nominatim.openstreetmap.org/search`
  - privacy comments: every tile request tells the tile host what you look at, and the geocoder sends your query text
  - the test `reference.conf` sets `map.geocoder.enabled=false`
- `altitude.test.TestContext.setAssetCoordinates` for the tests.

Tests
- `unit/SearchSqlTests`: cells and bounds render one statement each per dialect, every `?` bound.
- `integration/SearchMapTests`:
  - plotting: assets with points, assets without points at their Locations' pins, a Location with no matching assets
    absent, same-coordinate assets merged into one cell, the newest asset representing the cell
  - bounds: covers both point sources; `None` for an empty result
  - clipping to the box, repository isolation, and the view flags respected
- `unit/GeocoderServiceTests`: a unit suite with no database, run once. It covers the disabled path and the enabled
  path against a `com.sun.net.httpserver` stub on 127.0.0.1 returning a canned Nominatim body; there is no network in tests.

## Unit 5 — Routes

**Done.** Controller tests, red → green.

Files
- `routes/SearchRequestParser.scala`: `parse(view, q, folderId, personId, albumId, locationId, bbox): Either[String,
  Scope]`. It maps `view` to the recycled/triaged params, sets the ID filters and `q`, and validates `bbox` (a
  malformed one is `Left`). `Scope.query(rpp, page, searchSort, grouping, cursor)` builds the `SearchQuery`, so each
  controller keeps its own sort, grouping and paging. `SearchResultsController` and `MapController` share it.
- `routes/BaseController.scala`: `jsonResponse(value, status = 200)` and `jsonError(message, status)`, used by the
  JSON routes.
- `routes/api/LocationController.scala` (`api/location`):
  - `GET /r/:repoId/list`: flat camelCase JSON in path order: `id, name, kind, categoryId, categoryName, latitude,
    longitude, numOfAssets`, with nullable Category fields and pin.
  - `PUT /r/:repoId/assets` `{locationId, assetIds: [...]}` → `{added}`; `DELETE /r/:repoId/assets` → `{removed}`.
  - `unscrubbedJson.get` runs outside the payload `Try`, so a wrong content type keeps its own message.
  - A malformed payload or a Category target is a JSON 400; a foreign Location is a JSON 404.
- `routes/api/MapController.scala` (`api/map`):
  - `GET /r/:repoId/cells?<search params>&viewport=s,w,n,e&zoom=n` → `{cells: [{count, latitude, longitude,
    assetId}], locations: [{id, name, categoryName, latitude, longitude, count}], countsPlottedPoints: true}`.
  - `GET /r/:repoId/bounds?<search params>` → `{south, west, north, east, count}` or `{count: 0}`.
  - Both take `view`, `q`, `folderId`, `personId`, `albumId`, `locationId` and a trailing
    `params: cask.QueryParams`. The client sends its search parameters verbatim, and the grid-only ones (`sort`,
    `layout`, `groupBy`, `groupDirection`, `rpp`, `p`, `after`) and the `bbox` filter are accepted and ignored.
  - `viewport` (`Api.Field.Map.VIEWPORT`) is the map's own clipping box, never an asset filter: a visible Location
    counts every matching member even when a member's own point is outside the viewport.
  - `viewport` and `zoom` are read as strings, so a missing or malformed `viewport` and a non-integer `zoom` are JSON
    400s naming the parameter. Out-of-range integer zooms are clamped by the service.
  - `GET /r/:repoId/geocode?q=` → `[{label, latitude, longitude}]`, a JSON 404 while disabled, a JSON 502 on a
    `GeocoderException`.
- `routes/web/partial/LocationActionController.scala` (`htmx/location`):
  - Routes: `tab`; the dialogs `dialogs/add-location` (modal, see Unit 8), `dialogs/add-category`,
    `dialogs/rename-location`, `dialogs/delete-location`, `dialogs/move-location` and `dialogs/add-to-location`; the
    mutations `POST add` (name, latitude, longitude, categoryId?), `POST add-category`, `PUT rename`, `PUT move`,
    `PUT assets` (`{locationId, assetIds: "id,id"}` from the dialog's hidden field) and `DELETE /`. Mutations return an
    empty 200.
  - Every route calls `normalize(json)`, then the pure `validate(json, required, uuid, coordinates)`. `normalize`
    turns JSON numbers for the pin into strings and drops a null or empty `categoryId`.
  - A missing or out-of-range coordinate is reported once, as `Const.Msg.Err.PIN_REQUIRED` ("Place the pin on the
    map"): on latitude, or on longitude when only longitude is bad.
  - Field errors replace the form through `dialogFormValidationResponse`, as `AlbumActionController` does: validation,
    duplicate names, and an invalid Category choice. An invalid hidden ID is a plain-text 400 and a foreign ID a
    plain-text 404.
  - Headings and modal titles come from `Const.UI`, picked by `LocationKind` in the controller.
- Templates under `views/htmx/`:
  - `add_category_dialog` (placeholder "New category name", success `CATEGORY_ADDED_EVENT`), `rename_location_dialog`,
    `delete_location_dialog` (a Category's says its Locations move to the top level), `move_location_dialog` and
    `add_to_location_dialog` (the `locationId` error beside the select, an `assetIds` error as a general line).
  - They take their `title` like the album dialogs. `views/includes/location_category_select.scala.html` (label
    "Category", field `categoryId`, `(none)` for the top level) is shared by Add and Move.
  - `htmx/map_view.scala.html`: the map shell, `<div id="map" data-app-fragment="map-view" data-map-bounds="s,w,n,e"
    data-map-count data-map-tile-url data-map-attribution>` (`data-map-bounds` is empty when nothing is plotted).
- `routes/web/partial/SearchResultsController.scala`:
  - Parameters: `locationId`, `bbox`, `layout` (`Const.Search.Layout.GRID` / `MAP`; `Api.Field.Search.LOCATION_ID`,
    `BBOX`, `LAYOUT`). An unknown layout is a 400, and so is a malformed `bbox`.
  - `groupBy=location` is accepted; `groupDirection` with it is a 400.
  - Map layout: grouping and paging are ignored, and `count` and `mapBounds` run on `scope.copy(bbox = None)`. The
    response is `includes/search_results` with `map_view` as the grid (no `#assets`) and the Group `<select>` disabled.
  - The fragment carries `data-results-location-id`, `data-results-bbox` and `data-results-layout`. `HX-Replace-Url`
    carries non-default `layout`, `locationId` and `bbox`, and omits the ignored grouping in map layout.
- `App.scala` registers the three controllers.

Tests: `controller/LocationControllerTests`, `LocationActionControllerTests`, `MapControllerTests`, and
`SearchResultsControllerTests`. Every looped assertion is wrapped in `withClue`.
- `LocationControllerTests`: list shape, order and counts; membership against persisted rows; bad payloads and foreign
  Locations.
- `LocationActionControllerTests`: every dialog renders; add, add-category, rename, move and delete, with validation
  replacements, duplicate names and titles asserted through the constants; invalid and foreign IDs; 401s.
- `MapControllerTests`:
  - `cells` and `bounds` accept and ignore `layout=map&groupBy=location&groupDirection=up&rpp=0&p=9&after=x&sort=filename0&bbox=…`
  - a `bbox` filter still returns the whole scope, and Location counts are preserved across viewports
  - a missing or malformed `viewport` is a 400 naming it
  - geocode is a 404 while disabled; 401s
- `SearchResultsControllerTests`:
  - `groupBy=location` headers `Category › Location` and "No location", `data-group-key`, cursor round-trip
  - `layout=map` has `#map` and no `#assets`; `data-results-total` and `data-map-bounds` cover the whole scope with a
    `bbox`; `HX-Replace-Url` keeps `layout`, `locationId` and `bbox`
  - the 400 matrix: `groupDirection` with `location`, malformed `bbox`, unknown layout

## Unit 6 — Frontend: Locations tab, dialogs, drag/drop, add to Location

**Done.** Frontend, verified in the browser.

- `views/index.scala.html`: a fourth tab `#locationsTab` (`fa-map-marker-alt`, `href="#locations"`,
  `hx-get=/htmx/location/r/:repoId/tab`).
- `views/htmx/locations.scala.html`: styles (rows use the album grid; a Location under a Category gets `--depth: 1`
  and a `.trace` cell like the folder tree, `.location.category` for Category rows), the `#locationActions` and
  `#noLocations` hosts, and `#locationList[data-app-fragment="location-list"]`.
- `static/js/common/location-list.js` (the `album-list.js` shape):
  - Exports `reloadLocationList`, `refreshLocationCounts` (patches counts in place, falls back to a full render when
    a row is missing), `setViewedLocation` and `focusAddLocationControlIfFocusLost`.
  - Renders the flat JSON in the order it arrives: a Category row, then its Locations indented one step, with
    top-level Locations interleaved by name.
  - Category row: menu (Rename, Delete) | `fa-layer-group` | name. It has no count and is neither a drop target nor a
    search trigger.
  - Location row: menu (Rename, Delete, Move to category) | `.asset-count` | `fa-map-marker-alt` | name. Icon and name
    are `data-app-search-location-id` triggers, and `.controls` is `.dropzone[data-location-id]`.
  - Add controls: an empty list shows only the centered "Add your first location" (`#addFirstLocationBtn`). Otherwise
    the top `#addLocationBtn` (requests the modal into `#modalContent`) and `#addCategoryBtn` (an inline dialog) show.
- `static/js/dragdrop/locations.js` + `dragdrop/index.js`: `#locationList .dropzone` accepts
  `#assets .drag-drop, #batchOps .drag-drop` and dispatches `assetAddedToLocation` / `batchAssetsAddedToLocation`.
- `static/js/listeners/locations.js` + `listeners/index.js`:
  - `locationAdded`, `categoryAdded`, `locationRenamed`, `locationMoved` and `locationDeleted` reload the list and show
    a snackbar; deleting the viewed Location searches back to the repository.
  - `assetAddedToLocation` escalates to the batch when the asset is selected.
  - Also `batchAssetsAddedToLocation`, `batchAssetsRemovedFromLocation`, `batchAddToLocationRequested` (requests the
    add-to-location modal) and `assetsAddedToLocation` (resets the selection, refreshes counts).
- `static/js/assets/asset-actions.js`: `addAssetsToLocation` / `removeAssetsFromLocation` on the album pattern
  ("Already in location" when nothing was added). `refreshCounts` also refreshes Location counts, since recycling drops
  memberships. `frontend-app.js`: `reloadLocationCounts()`.
- `views/includes/batch_ops.scala.html`: "Add to location (n)" is always in the non-trash footer; "Remove from location
  (n)" shows while `$store.searchParams.locationId` is set.
- `static/js/fragments/add-to-location.js`, dispatched by `fragments/modal.js` on `data-app-dialog-kind`:
  - Fills the hidden `assetIds` from the `selectedAssets` store, only when empty, so a validation replacement keeps
    the submitted value.
  - Keeps `data-app-success-detail` (`{locationId}`) in step with the select, because the operation reads the detail
    when the request is issued and the listener names the Location after the modal is gone.
- `static/js/stores/search-params.js`: `locationId` in `DEFAULTS`; folder, person, album and Location clear each other.
  `context.js`: `getCurrentLocationId()`. `fragments/search-results.js` calls `setViewedLocation`.
- `static/js/constants.js`: the events above; `attributes.locationId` (`data-location-id`), `attributes.categoryId`
  (`data-category-id`), `attributes.kind`. `fragments/index.js` + `fragments/explorer.js`: the `location-list` kind.

## Unit 7 — Frontend: layout toggle, map view, crowded-pin panel, Location grouping in the grid

**Not started.** Frontend, verified in the browser. Server-rendered changes get controller assertions.

Leaflet is already vendored and loaded (Unit 8); this unit adds supercluster and everything that uses both.

- **Vendor**:
  - `static/js/lib/supercluster.min.js` (supercluster 9.1.0 `dist/supercluster.min.js`, kdbush bundled) and
    `supercluster.LICENSE` (ISC).
  - A row in `static/js/lib/README.md` with the exact tarball URL, and `window.Supercluster` in its plain-script note.
  - `<script src="/static/js/lib/supercluster.min.js">` beside `leaflet.js` in `index.scala.html`, and
    `Supercluster: "readonly"` in `eslint.config.js`.
- **`views/includes/search_results.scala.html`**:
  - The toolbar grid gets a fifth cell, a two-button segmented `#layoutToggle` (grid / map,
    `data-app-search="click" data-app-search-layout="…"`, `aria-pressed`).
  - The Group `<select>` gains a *Location* option (`data-app-search-group-by="location"`, empty
    `data-app-search-group-direction`, selected for `SearchGrouping(GroupBy.Location, _)`).
  - The `.date-group` header becomes `.result-group`. Its CSS here, `results_grid_grouped`, `date-groups.js` and
    `date-group-selectable.js` follow, along with the comments in `selection.js`. The Alpine component keeps its name.
  - `SearchResultsControllerTests`: the Location option is selected for `groupBy=location`.
- **`views/htmx/results_grid_grouped.scala.html`**:
  - A Location header's Category part becomes `<span class="category">` + `›` + name.
  - A cell's `id` is `asset-<id>` in day and ungrouped grids and `asset-<id>-in-<locationId>` in the Location grid
    (`result_cell` gets a `cellId` parameter); `data-asset-id` on `.drag-drop` and the image stays.
- **Duplicate cells** (D7): every `#asset-<id>` lookup tolerates several cells.
  - `selection.js` paints `.drag-drop[data-asset-id="…"]` (all copies).
  - `asset-actions.js`: `removeAssetsFromGrid` and the single-cell lookups act on every cell of the asset, and
    `decrementDateGroupOf` runs for each header a cell leaves.
  - `detail-navigator.js` remembers the cell element it opened from (the `hx-get` source), not the asset ID, and steps
    from that element.
- **`views/htmx/map_view.scala.html`**: beside the existing `#map`, add `<aside id="mapPanel" hidden>`. It has a header
  (`<n> items here`, a "Show only these in the grid" button, close) and a `#mapPanelContent` host. CSS in the template:
  `#content` is the split pane, so the map fills it (`height: 100%`), with the aside as an overlay column on the right,
  both under `--view-tint`. Move `.location-pin` from the Add location dialog's style block into a stylesheet both load,
  since Location pins on the map reuse it.
- **`static/js/map/map-view.js`** (the `map-view` fragment hydrator, registered in `fragments/index.js`):
  - Creates the Leaflet map from the data attributes (tile layer, attribution control, `worldCopyJump`).
  - Initial view: restore the last center/zoom from `map-state.js` when the search scope fingerprint is unchanged;
    otherwise `fitBounds` the server bounds; the world when there are none.
  - Sizing: `invalidateSize()` once the fragment is displayed and on a `ResizeObserver` of `#content` (the Split.js drag).
  - On `moveend` (debounced 150 ms), requests `/api/map/r/:repoId/cells` with the store's search parameters verbatim
    plus `viewport` and `zoom`, and drops superseded responses by sequence number.
  - Normalizes `viewport` to the server's form: latitudes clamped to ±90, longitudes wrapped into -180..180 with
    `west > east` across the antimeridian, and the whole world when the view spans 360° or more.
  - Loads the cells into a supercluster index (radius 60; `map`/`reduce` sum the counts and keep the newest
    representative), then renders the viewport's clusters as `L.divIcon` markers:
    - **Single-asset cell** (D15): a thumbnail pin (`/content/r/:repoId/preview/:assetId`) whose `hx-get` loads the
      asset-detail modal into `#imageDetailModalContent`, processed with `htmx.process`.
    - **Crowded pin** (D10): the representative thumbnail with a count badge (`.drag-count-badge` styling). A click
      flies to the cluster's expansion zoom when it is below the max; otherwise it opens the panel.
    - **Location pin** (D12/D18): the `.location-pin` marker glyph plus `Category › Name`, in
      `--dnd-drop-target-color`. A click runs `runSearch({params: {locationId}})`.
- **`static/js/map/map-panel.js`**:
  - Opens `#mapPanel` and runs the ordinary search into `#mapPanelContent` with `runSearch({params: {bbox}, transient:
    {layout: "grid", groupBy: null, groupDirection: null}, target: "#mapPanelContent"})`.
  - `bbox` is a real store parameter, so the panel's infinite scroll and cursor continuation work unchanged and the URL
    is bookmarkable. The map endpoints and the map layout's count and bounds ignore `bbox`, so the map keeps plotting
    the whole scope.
  - "Show only these in the grid" → `runSearch({params: {layout: "grid"}})`. A toolbar chip (`#bboxScope`, "Map area
    ×") clears `bbox` in both layouts. Closing the panel clears `bbox`.
- **`static/js/map/map-state.js`**: center and zoom in memory, keyed by the scope fingerprint the fragment carries
  (`data-results-*`), so a re-render for a sort change does not reset the view. It also exposes the displayed map's
  current view.
- **Pin editor start view**: `fragments/location-editor.js` opens on the results map's center and zoom when the map
  layout is displayed, and on the world otherwise. A validation replacement still opens on the submitted pin at zoom 12.
- **Layout persistence** (D14):
  - `Const.localStore.resultsLayout`, written by the store's `merge` when `layout` changes. `index.scala.html` seeds the
    store from the URL first, then `localStorage`.
  - `search-params.js`: `layout` and `bbox` in `DEFAULTS` (`grid` / `null`). `bbox` is cleared by any scope change
    except `layout` and `sort`.
- **`fragments/search-results.js`**: it already skips grid bindings when `#assets` is absent. It additionally hydrates
  the map through the fragment registry and skips `bindBoxSelection` in map layout.
- **Global Escape** (`global.js`): closes the map panel after the modal and menu rules, before the broadcast.
- Remove the plan-unit reference from the `map_view.scala.html` comment.

## Unit 8 — Frontend: Add location modal and pin editor

**Done.** Frontend, verified in the browser; the controller test covers the template's shape.

Vendoring
- `static/js/lib/leaflet.js` (`dist/leaflet.js`) and `static/css/leaflet.css` (`dist/leaflet.css`) are byte-identical
  copies from `https://registry.npmjs.org/leaflet/-/leaflet-1.9.4.tgz`, with `static/js/lib/leaflet.LICENSE` (BSD-2)
  and a row in `static/js/lib/README.md`. No marker images: every pin is an `L.divIcon`, so the `images/` references in
  `leaflet.css` are never requested.
- `views/index.scala.html` loads the stylesheet in the head and `leaflet.js` beside interact.js as a plain script
  (`window.L`). `eslint.config.js` declares `L: "readonly"`.

Template `views/htmx/add_location_dialog.scala.html`
(`title, categories, tileUrl, attribution, geocoderEnabled, fieldErrors, formJson`)
- `#addLocation.location-editor` form: `data-app-fragment="modal"`, `data-app-modal-title`, autofocus on the name,
  success event `LOCATION_ADDED_EVENT`, `hx-post` + `hx-json-enc`, `hx-swap="none"`.
- Fields: Name, the Category select, then a `data-app-fragment="location-editor"` block carrying `data-map-tile-url`,
  `data-map-attribution` and `data-geocoder-enabled`. The block holds:
  - when the geocoder is enabled only: `#locationGeocode` (search input), `#locationGeocodeBtn` and an empty
    `#locationGeocodeResults`
  - `#locationEditorMap` (300 px, `tabindex="-1"`, so Alpine's focus trap leaves Leaflet's keyboard handling alone)
  - the pin error line
  - `#locationPinReadout` ("No pin yet", `aria-live="polite"`)
  - hidden `latitude` / `longitude` inputs carrying the submitted values
- Errors: the name and Category errors sit beside their fields; a latitude or longitude error renders once, under the
  map, as the pin error.
- `#modalContent:has(.location-editor)` widens `--modal-content-width` to 720 px, capped by the viewport rule in
  `core.css`. The form is a one-field-per-row grid. `.location-pin` styles the pin glyph.
- `LocationActionController.addForm` passes the title, the Categories, the tile settings and
  `service.geocoder.isEnabled`.

Hydrator `static/js/fragments/location-editor.js` (kind `location-editor`, registered in `fragments/index.js` after the
modal hydrator); no Alpine component, plain DOM inside a modal fragment like the upload form
- Creates the Leaflet map in `#locationEditorMap`: the tile layer and attribution from the data attributes,
  `worldCopyJump`, `maxZoom` 19, no default marker.
- Initial view: the hidden inputs' point at zoom 12 with the pin shown, when both parse (a validation replacement);
  otherwise the world (`[20, 0]`, zoom 2).
- A click places or moves the pin: a draggable `L.marker` with an `L.divIcon` (`fa-map-marker-alt`, `.location-pin`,
  anchored at the glyph's tip). `dragend` and click both call `setPin(latlng)`, which wraps the point into -180..180
  and writes the hidden inputs (6 decimals) and the readout (4 decimals).
- Sizing: Leaflet measures the container on creation, while the modal is still hidden, so `invalidateSize()` runs once
  the container is displayed (polled until `offsetParent` is set) and on a `ResizeObserver` of the container.
- One editor at a time: a module-level handle is disposed before a new map is created, so a validation replacement
  does not leak the previous map. A `remove()` that throws is logged and the new map is still built.
- Geocoder:
  - Enter in the box, or the Search button, sends `GET /api/map/r/:repoId/geocode?q=` through the shared axios
    client; an empty query does nothing, and the newest search wins.
  - Results render as buttons labelled with the place, or "No places found". A click `setView`s at zoom 12, places the
    pin, fills an empty name with the label's first segment, and clears the list.
  - A failure (404 when disabled meanwhile, 502 upstream) is an error snackbar.

Tests (`LocationActionControllerTests`): the Add dialog carries the hidden `latitude` / `longitude` inputs, the readout,
the map host, the tile URL and `data-geocoder-enabled="false"`; a submission without a pin replaces the form with
`Const.Msg.Err.PIN_REQUIRED` and the submitted name.

Browser-verified through the hidden automation tab (`map.fire("click", {latlng})`, `marker.setLatLng` +
`marker.fire("dragend")`):
- the dialog opens on OSM tiles and the world view
- a submission without a pin shows the error under the map and keeps the name
- click-to-place updates the readout and the hidden inputs, and creates the Location
- a validation replacement after placing the pin re-creates the map on the pin
- Escape and reopening build a fresh map
- Category wording everywhere

A real marker drag (Leaflet moves it in a `requestAnimationFrame`, which never fires in a hidden tab) and the enabled
geocoder remain manual checks in Unit 9.

## Unit 9 — Docs and verification

**Not started.**

Docs (anti-drift rule). Units 1–6 and 8 are already documented; this unit adds the map view and removes what goes stale.
- `altitude/AGENTS.md`:
  - **Coordinates**, **Locations**, **Search results and date grouping** (filters, `count`, **Group by Location**,
    cursor v4, `SearchRequestParser`, `layout`), **Map** and **Schema migrations** are current.
  - Replace the `layout` paragraph's closing "The interactive map renderer is a later unit" with the map view's client
    behaviour.
  - Drop the pointer to this plan at the end of **Locations**.
- `altitude/views/AGENTS.md`:
  - **Locations** and **Location pin editor** are current.
  - Add the map fragment and panel, the layout toggle and persistence, `layout` / `bbox` in the store, the `bboxScope`
    chip, `.result-group` and duplicate cells in the Location grid, the editor's start view, a `js/map/` row in the
    directory table, and supercluster in the Stack line.
  - Replace the "Unit 7 adds …" sentence after the pin editor paragraph, and fix the `js/common/` row, which still
    says location-list "renders the parents and Locations".
- `static/js/lib/README.md`: the supercluster row (Leaflet's is there).
- `CONTEXT.md`: add **Plotted point** (**Location**, **Category** and **Pin** are there).
- `docs/test-coverage.md`: the Locations, search, grouped search and Map sections are current; add rows for any
  controller assertion Unit 7 adds.

Verification
1. `make compile`, `make lint`, `make test-unit`, `make test-sqlite`, `make test-controllers`; `make test-psql` with the
   test container up (the map SQL is the part most likely to differ between engines).
2. The user recreates the dev database and re-imports the dev library so assets get coordinates; restart the dev server
   (`ENV=dev mill altitude.runBackground`). Confirm with `EXPLAIN` that a cells query uses `asset_geo` on both engines.
3. Browser, dev server on :8080. Run `mill altitude.resources` after static changes. The automation tab is hidden: no
   pointer drags, no `requestAnimationFrame`, no IntersectionObserver callbacks. Verify through DOM APIs and mark the
   manual rows for the user.

| Scenario | Expect |
|---|---|
| Locations tab, empty | only the centered "Add your first location"; it opens the Add location modal with the map |
| Add a Location by clicking the map, then dragging the pin (**manual**) | readout and hidden inputs follow the pin; the list shows the new Location |
| Geocoder enabled in `application-dev.conf` (`map.geocoder.enabled=true`; the user's call, queries go to Nominatim) (**manual**) | search box present; a result click places the pin and fills an empty name; disabled → no box |
| Validation error after placing the pin (duplicate name, across kinds) | error in place, name kept, map re-created on the pin at zoom 12 |
| Add location while the map layout is open | the editor opens on the results map's view |
| Add a Category; move a Location under it via the menu; rename; delete the Category | Category wording everywhere; the Location returns to the top level |
| Drop one asset, then a selection, onto a Location (**manual**); footer "Add to location (n)" | counts update; "Already in location" on a repeat |
| Click a Location row | grid scoped, row marked green, URL carries `locationId`, footer offers "Remove from location" |
| Group by Location | headers `Category › Location`, an asset in two Locations appears twice, "No location" last, a header selects its cells, recycling a duplicated asset removes both cells and fixes both counts, infinite scroll continues within and across groups |
| Layout → map | toolbar stays, Group disabled, map fits the results, tiles load; pins render on a blank canvas when tiles are blocked (D17) |
| Zoom in/out, pan, pan across the antimeridian | cells re-request and merge; single-asset pins show thumbnails; Location pins are distinct |
| Click a single pin | asset detail modal |
| Click a Location pin | results scoped to the Location |
| Click a crowded pin at max zoom | panel with a scrollable grid, sort applies, drag from the panel to a Location works (**manual**), "Show only these in the grid" switches layout with the bbox chip, closing clears it |
| Reload the page | layout and the last map view are remembered; a bookmarked `layout=map&bbox=` URL opens the same panel |
| Trash and triage views in map layout | plotted from the same scope rules |
| Folders and Albums tabs afterwards | unchanged |

---

## Not in this phase

- A per-asset ⋯ context menu ("Add to location" on a single asset without selecting it).
- Editing a Location's pin after creation. A "Move pin" row menu action would reuse the pin editor.
- Assigning assets to Locations automatically by distance.
- Distance search and sort ("within R km of a Location"). If wanted, it would use equirectangular arithmetic with the
  degree factors computed in Scala, which needs no trig in SQL on either engine and is accurate to under 0.5 % below
  about 200 km.
- Backfilling coordinates for already-imported assets (D5). `GeoLocationResolver` replays from `extracted_metadata`, so
  a later `AssetService.resolveMissingCoordinates` needs no file reads.
- Scoping the grid by a Category (all its Locations at once).
- Dragging a pin's assets from the map onto a Location row (the panel covers it).
- Moving a Location to another Category by dragging its row (the Move to category dialog covers it).
- Self-hosted PMTiles: the tile URL is config, but `StaticController` has no `Range` support.
- Prev/next in the asset detail modal opened from a map pin (there is no grid to walk).
