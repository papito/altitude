# Locations + Map View — library options and decisions

Status: **decisions made 2026-09-13 (§6). The implementation plan is
[locations-and-map-view-implementation.md](locations-and-map-view-implementation.md) (Units 1–6 done, the rest not started).**

Written 2026-09-13. Versions below were checked against GitHub releases / npm on that date.

---

## 1. What the codebase gives us (facts that constrain the choice)

- Frontend is vanilla ES modules + Alpine 3 + htmx 4 + interact.js, **all vendored under
  `altitude/static/js/lib/`, no bundler**. Any map library must ship a single-file UMD or ESM
  build plus its CSS, and be usable without a build step.
- Sidebar tabs (Folders, Albums, People) are HTMX partials whose rows are built in JS
  (`common/album-list.js`, `common/folder-tree.js`); context menus are native `popover`
  panels built in JS (`common/context-menu-markup.js`); drag/drop is interact.js
  (`dragdrop/*.js`). Locations will follow the same shape.
- Search results are **HTML only** (`GET /htmx/search/r/:repoId`). The map needs JSON, so
  a new JSON endpoint is required regardless of library. Because repositories can reach
  multi-million assets (§6, D13), that endpoint returns **viewport-aggregated cells**, never
  the whole result set.
- Grouping is one CTE-based statement keyed on a scalar per asset (`day`), cursor-paginated,
  with a `day_counts` CTE (`dao/sql/search/SearchQueries.scala`). "Group by Location" has to
  fit that shape, and an asset in several Locations breaks the one-row-per-asset assumption
  (resolved by D7: row = asset × Location).
- GPS is **not parsed anywhere**. It sits as strings inside `extracted_metadata` under the
  `"GPS"` directory (metadata-extractor's `GpsDirectory`; it also exposes a typed
  `getGeoLocation()` that already handles the DMS → decimal + N/S/E/W ref conversion).
  The replayable-from-persisted-inputs pattern in `util/CaptureDateResolver.scala` is the
  template.
- Databases: Postgres via the `pgvector/pgvector:0.8.2-pg18` image — **no PostGIS**, but
  `cube`, `earthdistance`, `pg_trgm`, `btree_gist` are available. SQLite via
  `sqlite-jdbc 3.51.2.0`, verified compiled with `ENABLE_MATH_FUNCTIONS`, `ENABLE_RTREE`,
  json1 and FTS5. Both `all.sql` files must change together; no numbered migrations.
- Uniqueness is a DB unique index translated to `DuplicateException` in `BaseService`
  (SQLite error 19 / Postgres `23505`), not a service pre-check. Location name uniqueness
  is `UNIQUE (repository_id, name_lc)` — the same as albums.

---

## 2. Map rendering libraries

Candidates are all OSI-licensed. Google Maps and Mapbox GL v2+ are proprietary and excluded.

| | **Leaflet 1.9.4** | Leaflet 2.0 (alpha) | **MapLibre GL JS 6.9.0** | OpenLayers 10.10.0 | deck.gl 9 |
|---|---|---|---|---|---|
| License | BSD-2 | BSD-2 | BSD-3 | BSD-2 | MIT |
| Latest release | 1.9.4, May 2023 | 2.0.0-alpha.1, Aug 2025; "dev" snapshot Sep 2025; **no stable, nothing since** | 6.9.0, **Sep 9 2026** (weekly cadence) | 10.10.0, Jul 27 2026 (quarterly) | active |
| Project health | Dormant but stable; the API we need has not changed in years | Stalled at alpha for 16 months | Very active, Linux-Foundation-style governance | Active | Active (Uber → OpenJS) |
| Size, minified + gzip (approx.) | ~42 KB + 15 KB CSS + 3 marker PNGs | similar | ~250 KB (≈800 KB unminified) + CSS | ~200 KB | ~1 MB+ |
| Vendorable w/o bundler | Yes, `dist/leaflet.js` UMD | ESM only; `leaflet-global.js` bundle for globals | Yes, `dist/maplibre-gl.js` UMD | `dist/ol.js` full build exists, but ESM is the supported path | UMD bundle exists |
| Rendering | DOM + Canvas | same | **WebGL2 required** (WebGL1 dropped in 5.0) | Canvas 2D, optional WebGL | WebGL2 |
| Raster tiles (OSM) | Native | Native | Native (`raster` source) | Native | Via MapLibre base |
| Vector tiles | Plugin: `protomaps-leaflet` 5.1 (canvas) or `@maplibre/maplibre-gl-leaflet` 0.1.4 | same plugins, untested | **Native** (style JSON) | Native (`ol/layer/VectorTile`), needs `ol-mapbox-style` for styles | Via MapLibre base |
| Self-hosted PMTiles | Via `protomaps-leaflet` (vector) or `leaflet-pmtiles` (raster) | same | Native via `pmtiles` 4.5 protocol | `ol-pmtiles` 2.0 | Via MapLibre |
| Built-in clustering | **No** — plugin (see §3) | No | **Yes** (supercluster inside `geojson` source: `cluster`, `clusterRadius`, `clusterMaxZoom`, `clusterProperties` for aggregation) | Yes (`ol/source/Cluster`, distance-based, recomputed per view, no aggregation helpers) | No (supercluster + custom layer) |
| Thumbnail / HTML markers | `L.divIcon` — trivial, plain DOM, stylable with existing CSS | same | `Marker({element})` is DOM but degrades past ~1k markers; the fast path is symbol layers with images loaded into the style (more work, canvas-drawn) | `ol/Overlay` (DOM) or `Icon` style (canvas) | IconLayer (atlas), not DOM |
| Fit map to result set | `map.fitBounds(latLngBounds, {padding})` | same | `map.fitBounds(bounds, {padding})` | `view.fit(extent, {padding})` | via MapLibre |
| Click-to-pick lat/lng (Add Location modal) | `map.on('click', e => e.latlng)` | same | `map.on('click', e => e.lngLat)` | `map.on('click', e => toLonLat(e.coordinate))` | possible |
| Popups / side panels | Built-in `L.popup` | Built-in | Built-in `Popup` | Via `Overlay` | none |
| Interop with interact.js drag/drop | Excellent — markers are ordinary DOM nodes, can carry `.drag-drop` class and be dropped on Location rows | Excellent | Only for DOM markers; symbol-layer markers are canvas pixels | Overlays only | No |
| Works inside an `x-show` panel that starts hidden | Needs `map.invalidateSize()` after show (well-known) | same | Needs `map.resize()` after show | `map.updateSize()` | — |
| Touch / pointer | Mouse + touch events | Pointer Events | Pointer | Pointer | Pointer |
| TypeScript types | `@types/leaflet` | bundled | bundled | bundled | bundled |
| Globe / 3D terrain | No | No | Yes (globe since 5.x) | No | Yes |
| Learning curve for this codebase | Smallest; imperative, tiny API | same | Moderate; style-spec JSON, layers/sources model | Steep; projections, sources, layers, styles | Steep |
| Risk to call out | 1.x dormant; 2.0 may never ship; markercluster plugin will not follow to 2.0 | Alpha | WebGL2 requirement (fine on any desktop browser since 2022; some remote/VM browsers lack it); style maintenance | Heavier than needed; dist build second-class | Overkill for < 1M points |

### Basemap sources (independent of the renderer)

| | OSM raster (`tile.openstreetmap.org`) | OpenFreeMap (vector, hosted) | **Protomaps PMTiles (self-hosted)** | Ship nothing / offline fallback |
|---|---|---|---|---|
| Cost / key | Free, no key, [usage policy](https://operations.osmfoundation.org/policies/tiles/) (needs a real User-Agent/Referer, no bulk download, "heavy use" discouraged) | Free, no key, no stated limits; also self-hostable via Docker | Free; one `.pmtiles` file served by us | Free |
| Privacy | **Every tile request tells a third party where you are looking** | Same | Zero external requests; works on a LAN / offline | Zero |
| Works with | All four renderers | MapLibre native; Leaflet via `maplibre-gl-leaflet` | MapLibre native; Leaflet via `protomaps-leaflet`; OL via `ol-pmtiles` | — |
| Setup effort | None | None | Download a regional extract with `pmtiles extract` (city/country = tens–hundreds of MB; planet ≈ 100+ GB), plus a Cask route that honours HTTP `Range` (the static-resources handler does not) | Low-zoom world only; ugly but functional |
| Look | Classic raster OSM | Modern vector, several styles | Modern vector, Protomaps `light`/`dark`/`white`/`grayscale` styles | Minimal |

---

## 3. Clustering / "many images in one place" libraries

The map view has two overlapping problems: (a) thousands of geotagged assets in a result set
must not become thousands of DOM nodes, and (b) hundreds of assets sharing *one* coordinate
(a user Location, or a burst of shots from the same spot) must be readable.

| | Leaflet.markercluster 1.5.3 | **supercluster 9.1.0** | OpenLayers `ol/source/Cluster` | MapLibre built-in |
|---|---|---|---|---|
| Release / health | 1.5.3 Jun 2022; last commit Mar 2024; effectively unmaintained; Leaflet 1.x only | 9.1.0, Sep 3 2026; MIT; Mapbox-maintained; the engine inside MapLibre | Part of OL | supercluster under the hood |
| Algorithm | Greedy distance clustering per zoom, built once | KD-tree (kdbush) hierarchical grid clustering; 500k points indexed in ~1–2 s; `getClusters(bbox, zoom)` is sub-ms | Distance-based, recomputed on every view change | supercluster |
| Renders markers itself | Yes (Leaflet layer) | **No** — pure data; needs a ~80-line Leaflet adapter (`moveend` → `getClusters` → diff markers) | Yes (as features) | Yes (layers) |
| Aggregations per cluster (e.g. representative thumbnail, count, member Location IDs) | No (only child markers) | **Yes** (`map` / `reduce` options → `clusterProperties`) | No | Yes (`clusterProperties`) |
| Expand cluster / get members | `zoomToShowLayer`, `getAllChildMarkers` | `getClusterExpansionZoom`, `getLeaves(id, limit, offset)` (paginated!) | `get('features')` | `getClusterExpansionZoom`, `getClusterLeaves` |
| Spiderfy (fan out overlapping pins) | Built-in, animated | No (would be custom) | No | No (community plugin) |
| Handles N points at the *same* coordinate | Spiderfy — fine for ~10, useless for 200 | Stays one cluster at max zoom; UI opens a panel instead | Stays one cluster | Stays one cluster |
| Verdict | Tempting, but dead-ends on Leaflet 2 and offers nothing we need beyond supercluster | **Recommended** with any renderer | Only if OL is chosen | Free with MapLibre |

### UX pattern for a crowded location (library-independent; decided in D10)

Photo managers converge on the same answer: a cluster pin is a **stacked thumbnail with a
count badge**, not a circle with a number. Clicking it either zooms into the cluster
(if it would split) or, at max zoom / same coordinate, opens a **panel with a scrollable
thumbnail grid** (paged via `getLeaves(id, 50, offset)`), optionally with a "show only these
in the grid" action. supercluster's `reduce` gives the representative thumbnail per cluster
for free (e.g. "newest asset"). User-defined Locations get a distinct pin (a different colour
and the Location name) so they are visually separate from raw-GPS clusters.

---

## 4. Geospatial search under both engines (no extensions needed)

| Need | Postgres | SQLite | Notes |
|---|---|---|---|
| Columns | `latitude DOUBLE PRECISION NULL, longitude DOUBLE PRECISION NULL` on `asset` and `location` | `REAL NULL` × 2 | Decimal degrees, WGS84. Nullable: "assuming the data is there". |
| Index for bbox / map viewport | B-tree `(repository_id, is_recycled, latitude, longitude)`; optionally `gist(point(longitude, latitude))` (built-in, no PostGIS) | B-tree `(repository_id, is_recycled, latitude, longitude)`; optionally `rtree` virtual table `asset_geo(id, min_lat, max_lat, min_lon, max_lon)` maintained by triggers | A composite B-tree is enough for a personal library (10⁵–10⁶ rows); R*Tree/GiST are an optimisation we can add later without changing the API. |
| "Within bbox" | `latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ?` (antimeridian: split into two boxes) | identical | Pure standard SQL → one `SearchQueries` predicate, no dialect hook. |
| "Within R km of a point" | Equirectangular: `((longitude - ?) * ?cosLat0)^2 + (latitude - ?)^2 <= ?r²` where `cosLat0` and `r²` (in degrees²) are computed in Scala | identical, plain arithmetic | No trig in SQL at all, so it works on any SQLite build; accurate to <0.5 % for radii under ~200 km, which is all a "near this Location" filter needs. Full haversine is also available on both (SQLite has `sin/cos/acos/radians`, verified), and Postgres has `earthdistance`, but neither is needed. |
| Sort by distance | same expression in `ORDER BY` | same | |
| Group by Location | join `location_asset` → group key `location_id` | same | Row = asset × Location (D7); fits the existing grouped CTE. |
| **Map cells for a viewport** (D13/D19) | `GROUP BY floor(latitude / :cell), floor(longitude / :cell)` with `count(*)`, `avg(latitude)`, `avg(longitude)`, `min(id)` (or the newest asset via a window) over the bbox-filtered matching set | identical | `:cell` is the cell size in degrees for the requested zoom, computed in Scala (e.g. `360 / 2^zoom / 4`). Returns at most a few hundred rows per viewport; a cell with `count = 1` carries the asset id. Assets without GPS but in a Location are plotted at the Location pin (D12) via a `COALESCE(asset.latitude, location.latitude)` join. |
| Fit map to result set | `min(lat), max(lat), min(lng), max(lng)` over the matching set (assets' own or Location-derived coordinates) | identical | One aggregate query on the same predicate. |

Everything above can be expressed with ScalaSql `Expr` arithmetic, so it goes through the
same typed `SearchQueries` path and both integration bundles test it automatically.

---

## 5. Recommendations

### Chosen (D20): **Leaflet 1.9.4 + supercluster 9.1.0 + OSM raster tiles by default, with a self-hosted PMTiles option behind it**

With server-side aggregation (D19) the browser never sees more than a few hundred cells per
viewport, so Leaflet DOM markers are comfortably within budget and supercluster's job shrinks
to merging cells that overlap on screen and to `getLeaves` for the crowded-pin panel.

Why:
- It fits the stack exactly: two vendored files, no bundler, imperative API that lives
  comfortably in an Alpine component; markers are DOM nodes, so thumbnail pins reuse the
  existing thumbnail CSS and can be interact.js drag sources (drag a pin's photos onto a
  Location row) with no special casing.
- No WebGL requirement, so it renders in every browser/VM the app might be viewed from.
- supercluster gives hierarchical clustering with per-cluster aggregation (representative
  thumbnail, count, contained Location IDs) and paged `getLeaves` for the crowded-location
  panel. It is maintained and it is the same engine MapLibre uses, so switching renderer
  later does not change the clustering code.
- The basemap is swappable in one line: OSM raster today; `protomaps-leaflet` + a Cask
  `Range` route later if you want zero third-party tile requests.
- Accepted risk: Leaflet 1.x is dormant and 2.0 is stalled. The surface we use
  (`map`, `tileLayer`, `divIcon`, `marker`, `fitBounds`, `popup`, `on('click')`) has been
  stable for a decade, and the 2.0 migration for that surface is mechanical (ESM import,
  `new` keyword). We deliberately avoid `Leaflet.markercluster` for that reason.

### Cutting-edge alternative (not chosen): **MapLibre GL JS 6.9.0 + built-in clustering + Protomaps PMTiles**

Would be chosen if any of these matter more than simplicity: fully self-hosted / offline map
with a modern vector look; globe view; smooth 60 fps at 100k+ points; no dependency on the
dormant Leaflet line. Costs: ~6× the payload, WebGL2 required, style JSON to own, thumbnail
pins have to be drawn as symbol-layer images instead of DOM (so no interact.js drag from the
map), a `Range`-capable static route in Cask, and a regional `.pmtiles` extract to download.

### Not recommended
- **OpenLayers**: excellent GIS toolkit, but the heaviest API of the three for a feature
  that needs pins, clusters, fit-bounds and click-to-pick. Its dist build is second-class.
- **deck.gl**: for millions of points and data-viz layers; far beyond this need.
- **Leaflet.markercluster**: unmaintained, Leaflet-1-only, and spiderfy does not solve
  "200 photos at one pin".
- **PostGIS / SpatiaLite**: would change the Docker image and the SQLite JDBC driver for
  no gain at this scale.

---

## 6. Decisions (2026-09-13)

Answered via structured questions; the recommended option was taken unless noted.

### Data model
- **D1 Parents** are named containers with no pin and no direct assets. **One uniqueness
  pool** per repository: a parent and a Location cannot share a name (`name_lc` unique across
  both). Implementation choice for the plan: one `location` table with a nullable
  `parent_id` and a `kind` (`parent` | `location`) discriminator, or two tables with a shared
  uniqueness check. Decide in the plan.
- **D2 Deleting a non-empty parent** moves its Locations to root.
- **D3 Drop targets** are Location rows only. Parents are not drop targets.
- **D4 Location = pin + optional radius.** Nullable `radius_m` stored now; **no
  auto-assignment** in this phase.
- **D5 Asset coordinates: parse on import only.** *(Not the recommended option.)* Nullable
  `latitude`/`longitude` on `asset`, filled in `ExtractMetadataFlow` from
  metadata-extractor's `GpsDirectory.getGeoLocation()`. No backfill action; existing assets
  get coordinates when the DB is recreated and re-imported. No manual editing.
- **D6 Recycle/restore** mirrors albums: recycling removes the asset from all Locations in
  the same transaction; restore does not re-add.

### Grouping
- **D7 Group by Location, multi-membership:** an asset appears under **every** Location it
  belongs to (row = asset × Location; group counts may sum to more than the result total).
  A **"No location"** group comes last.
- **D8 Group dropdown** gains one option, *Location*. Groups are ordered by parent name,
  then Location name; the header reads `Parent › Location` when a parent exists, otherwise
  just the Location name. Grouping by parent is not offered.

### Menus and dialogs
- **D9 Menus.** Location row ⋯ menu: **Rename, Delete, Move to parent** (dialog with a
  parent dropdown; drag between parents also works). Parent row ⋯ menu: **Rename, Delete**.
  **Add to Location** lives on the **asset context menu and batch ops**, opening a dialog
  with a `[Parent] - Location` dropdown (parent omitted when absent).
- **D10 Crowded pin.** Pin = stacked thumbnail + count badge. Click zooms to the cluster's
  expansion zoom when it would split; at max zoom or identical coordinates it opens a
  **side panel with a paged thumbnail grid**, which offers **"show only these in the grid"**
  (uses the `locationId` filter from D18 or a bbox filter).
- **D11 Add Location modal.** Large modal with the map, click-to-place and draggable pin,
  **editable lat/lng fields**, name, parent dropdown, optional radius. Plus a
  **place-name search box** backed by an external geocoder (Nominatim or Photon),
  **config-gated** (off by default; documents the privacy trade-off). "Create Location from
  this photo" was **not** selected.

### Map view
- **D12 What is plotted.** Assets with GPS at their own point; assets without GPS but in a
  Location at the Location pin; Location pins drawn distinctly when any result belongs to
  them.
- **D13 Scale: multi-million assets per repository.** *(Not the recommended option.)*
  Rules out shipping the whole result set to the browser.
- **D14 Toolbar in Map mode.** Group dropdown disabled; Sort still orders the crowded-pin
  panel. Map mode is a `view=map` URL parameter and is remembered in localStorage like the
  View settings.
- **D15 Single-asset pin click** opens the asset detail modal. Sidebar scope
  (folder/album/person/location) still defines the result set.
- **D16 Basemap.** OSM raster tiles from `tile.openstreetmap.org` now, with attribution and
  a proper `User-Agent`/`Referer`. The tile URL template is a config setting so a
  self-hosted PMTiles/raster source can be swapped in later without code changes.
- **D17 Fallback.** Progressive enhancement only: if tiles fail, pins still render on a
  blank canvas. No extra notice.
- **D18 Location filter is in scope.** `locationId` is a search filter (semi-join like
  `albumId`); clicking a Location row in the sidebar scopes the grid, highlighted like
  albums.
- **D19 Map data transport: viewport-driven grid aggregation in SQL** on both engines
  (§4). The client requests `bbox + zoom`; the server returns cells with count, centroid and
  a representative asset id; single-asset cells return the asset id. Fit-to-results uses a
  min/max aggregate. No geohash column.
- **D20 Renderer: Leaflet 1.9.4 + supercluster 9.1.0** (see §5).

---

## 7. Implementation plan

Expanded on 2026-09-13 into
[locations-and-map-view-implementation.md](locations-and-map-view-implementation.md): nine units
(schema and GPS import, Location model/DAO/service, search filters and Group by Location, map
queries and geocoder, routes, Locations tab, map view, Add Location modal, docs). That document
also records the choices this one left open (D1: one `location` table with `kind` and
`parent_id`) and the deviations it needed: `view=map` becomes `layout=map` because `view` already
names the asset state; the per-asset context menu of D9 is deferred (none exists); the crowded-pin
panel is a `bbox`-scoped search rather than a client-side leaf walk; supercluster is ISC-licensed.
