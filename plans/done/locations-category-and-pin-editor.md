# Locations: Category rename, radius removal, pin editor

Status: **done 2026-09-13.** Verified with `make lint`, `make test-unit` (86), `make test-controllers` (45), `make test-sqlite`
(223), `make test-psql` (223) and the browser table below on the recreated dev database. Not committed.

Findings:
- Disposing the previous editor's map must not be fatal: a Leaflet `remove()` that throws (its drag state was left stuck by a
  synthetic drag in the automation tab) left the next dialog with no map at all, so `disposeCurrentEditor` now logs and goes on.
- A real marker drag cannot be verified from the hidden automation tab: Leaflet moves the marker in a `requestAnimationFrame`,
  which never fires there. Click-to-place, the readout, the hidden inputs, the validation replacement re-creating the map on the
  pin, and Category wording everywhere were verified; the drag and the geocoder (off in dev) remain manual checks.
- A geocoder assertion in `LocationActionControllerTests` had to target `id="locationGeocode"`: the dialog's style block names
  the geocoder selectors whether or not the box is rendered. Written after Unit 6 of
[locations-and-map-view-implementation.md](locations-and-map-view-implementation.md) was committed
(`472933cf`). Three changes the user asked for, in this order, on top of that commit; the third is
Unit 8 of the implementation plan pulled forward and reshaped. No commit is made by the agent.

## What changes, in one paragraph

A **parent** becomes a **Category**, everywhere: code, schema, API, templates, events, CSS, docs. A
Location loses its **radius**: the concept goes away (D4 becomes "a Location is a pin"). A Location's
pin is **placed on a map, never typed**: the Add location dialog gets the Leaflet editor (click to
place, drag to move, place-name search when the geocoder is enabled), the latitude/longitude fields
become hidden inputs with a read-only readout under the map, and a missing pin is reported as "Place
the pin on the map". Everything built on Location pins (D12 plotting, the bbox fallback, Location pins
on the map, `mapLocations`) stays as it is.

## Decisions revised (to be written into `locations-and-map-view.md` §6 as part of this work)

- **D1**: "parent" → "Category" throughout. One `location` table, `kind` is `category` | `location`,
  the self-reference is `category_id`. One name pool per repository for both kinds, unchanged.
- **D4**: Location = pin. No radius, now or later; auto-assignment by radius leaves *Not in this phase*.
- **D9**: the Location row menu is Rename, Delete, **Move to category**; the Category row menu is
  Rename, Delete. "Add to Location" dialog lists `Category - Location`.
- **D11**: the Add location modal holds the map, click-to-place and a draggable pin, a **read-only
  coordinate readout** (no editable fields), name, Category dropdown, and the config-gated place-name
  search. Coordinates travel in hidden inputs.
- D8 header reads `Category › Location`.

Assumptions (veto if wrong): the database is recreated from scratch afterwards, since the `kind`
value and a column name change (the project rule; no migration is written). Coordinates keep six
decimals in the hidden fields and four in the readout. The editor's initial view is the world until
Unit 7 exists (it will centre on the results map when one is open, as the implementation plan says).

---

## Step A — Rename parent → Category (everywhere)

Mechanical, but wide. Server first (TDD is a rename here: update the tests, watch them fail to
compile, rename the code), then templates, then JS, then docs. Nothing behavioural changes, so every
existing test must pass unchanged in substance.

Server
- `models/LocationKind.scala`: `case Category extends LocationKind("category")`; doc "a named
  container or a pinned place".
- `resources/migrations/{postgres,sqlite}/all.sql`: `parent_id` → `category_id` (both the column and
  the FK on SQLite), `CHECK (kind IN ('category', 'location'))`, the kind CHECK's `'category'` branch,
  `location_02` on `(repository_id, category_id)`, the comment ("deleting a Category moves its
  Locations to the top level first").
- `FieldConst.Location`: `CATEGORY_ID = "category_id"`, `CATEGORY_NAME = "category_name"`; remove
  `PARENT_ID` / `PARENT_NAME` from that object only (`FieldConst.Folder.PARENT_ID` stays).
- `Api.Field.Location.CATEGORY_ID = "categoryId"` (folders keep `parentId`).
- `dao/sql/tables/LocationRow.scala`: `categoryId`; `unit/RowColumnTests` renders it against the DDL.
- `models/Location.scala`: `categoryId`, `categoryName`; `LocationKind.Category` branch; messages
  ("A Category has no pin and no Category of its own").
- `dao/LocationDao.scala` + `dao/jdbc/LocationDao.scala`: `moveChildrenToRoot(categoryId)`,
  `getAll`'s self-join alias and `category_name` column, the path-order comment.
- `service/LocationService.scala`: `addCategory`, `moveToCategory(id, categoryId)`, `requireCategory`,
  log lines and docs.
- `dao/sql/search/SearchQueries.scala`: `groupedByLocation` (`categoryId` join, `categoryName`,
  `category_name` in `locationColumnList` and the `WITH` shell), `mapLocations` join and the
  `MapLocationsRow` doc, the `PATH_SEPARATOR` doc.
- `util/GroupedSearchResult.scala`: `SearchGroupKey.Location(id, pathKey, name, categoryName)`;
  `dao/jdbc/SearchDao.scala` pattern matches; `models/MapLocation.categoryName`;
  `routes/api/MapController.scala` JSON `categoryName`; `routes/api/LocationController.scala` JSON
  `categoryId`, `categoryName`.
- `Const.UI`: `RENAME_CATEGORY_DIALOG_TITLE = "Rename category"`, `DELETE_CATEGORY_DIALOG_TITLE =
  "Delete category"`, `MOVE_LOCATION_DIALOG_TITLE = "Move to category"`; the `*_PARENT_*` two go.
- `routes/web/partial/LocationActionController.scala`: routes `dialogs/add-category` and
  `POST add-category`; `categories` / `locations` helpers; `isCategory`; the field lists.

Templates
- `htmx/add_parent_dialog.scala.html` → `add_category_dialog.scala.html`: `id="addCategory"`,
  success event `CATEGORY_ADDED_EVENT`, placeholder "New category name".
- `includes/location_parent_select.scala.html` → `location_category_select.scala.html`: label
  "Category", field `categoryId`, `(none)` for the top level.
- `move_location_dialog`, `delete_location_dialog` (the "Its Locations move to the top level" line
  is for a Category), `add_to_location_dialog` (`Category - Location`), `add_location_dialog`,
  `results_grid_grouped` (comment: `Category › Location`).

Frontend
- `constants.js`: `categoryAdded: "CATEGORY_ADDED_EVENT"`, attribute `categoryId: "data-category-id"`
  (replacing `parentId`); `kind` stays.
- `common/location-list.js`: `CATEGORY_KIND = "category"`, row class `.category`, `data-category-id`,
  the add control `addCategoryBtn` / `addCategoryMenu` / `addCategoryDialog` → `dialogs/add-category`,
  menu label "Move to category", aria label "Actions for category …", comments.
- `htmx/locations.scala.html` CSS: `.location.category`. `listeners/locations.js`: `categoryAdded`.
- `views/AGENTS.md` (**Locations** section, DOM attribute list, dialog lists), `altitude/AGENTS.md`
  (**Locations**, **Group by Location**, **Map**), `docs/test-coverage.md` (section title and rows),
  `plans/locations-and-map-view-implementation.md` (Units 2–7 wording, `parentName` in Unit 7's map
  pin), `plans/locations-and-map-view.md` (D1, D8, D9).

Tests: `LocationServiceTests`, `LocationActionControllerTests`, `LocationControllerTests`,
`SearchGroupingTests`, `SearchMapTests`, `SearchSqlTests`, `MapControllerTests`,
`SearchResultsControllerTests`, `SearchCursorTests`, `RowColumnTests` — rename calls, JSON keys,
dialog paths and title constants. `grep -rni parent` over `altitude/src`, `altitude/views`,
`altitude/static/js` (excluding `lib/`) and `altitude/test` must afterwards hit only folders and DOM
`parentNode`/`parentElement`.

## Step B — Remove radius

Behavioural: tests first (drop the radius cases, add "the API has no radius field"), then code.

- Schema (both engines): drop `radius_m` and its two CHECK mentions.
- `LocationRow.radiusM`, `FieldConst.Location.RADIUS_M`, `Api.Field.Location.RADIUS_M` removed.
- `models/Location`: field and validation gone; the Category rule is "no pin, no Category".
- `LocationService.addLocation(name, latitude, longitude, categoryId)`.
- `dao/jdbc/LocationDao`: `add`, `toModel`, `makeModel`.
- `LocationController.toJson`: no `radiusM`.
- `LocationActionController`: `numeric` = latitude, longitude; `optionalFields` = categoryId; the
  radius branch of `validate` goes; `Const.Msg.Err.VALUE_NOT_A_POSITIVE_INTEGER` is removed if nothing
  else uses it (grep first).
- `add_location_dialog`: the radius field goes (the field loop shrinks to the name; see Step C).
- Tests: `LocationServiceTests` ("A Location needs a pin in range" — the radius assertions go),
  `LocationActionControllerTests` (the `radiusM` cases and the `numeric` post), `LocationControllerTests`
  (the field list and the `rows.last("radiusM")` assertion).
- Docs: `altitude/AGENTS.md` **Locations** ("an optional `radiusM`" goes), `docs/test-coverage.md`
  row 179, `plans/locations-and-map-view.md` D4 and §4's column list, the implementation plan's data
  model block and Unit 2 text.

## Step C — Pin editor in the Add location dialog (Unit 8, reshaped)

Frontend, verified in the browser; the controller test covers the template's new shape.

Vendoring
- `static/js/lib/leaflet.js` = `dist/leaflet.js` and `static/css/leaflet.css` = `dist/leaflet.css`
  from `https://registry.npmjs.org/leaflet/-/leaflet-1.9.4.tgz`, byte-identical;
  `static/js/lib/leaflet.LICENSE` (BSD-2). Rows in `static/js/lib/README.md`. No marker images:
  every pin is an `L.divIcon`, so the `images/` references in `leaflet.css` are never requested.
- `views/index.scala.html`: the stylesheet in the head and `<script src="/static/js/lib/leaflet.js">`
  beside interact.js (plain script, `window.L`; Unit 7 reuses it). `eslint.config.js`: `L: "readonly"`.

Template `htmx/add_location_dialog.scala.html`
- Form: name (autofocus), Category select, `#locationEditorMap` (300 px, `tabindex="-1"` so Alpine's
  focus trap leaves the map's keyboard handling alone), a readout line `#locationPinReadout` ("No pin
  yet" or `36.6000, -121.9000`), hidden `latitude` / `longitude` inputs carrying the submitted values
  (so a validation replacement re-renders the pin where it was), and, only when the geocoder is
  enabled, a search box `#locationGeocode` + button and an empty results list `#locationGeocodeResults`.
- Errors: the name and Category errors stay beside their fields; a latitude or longitude error renders
  once, under the map, as the pin error. Server message for a missing or out-of-range pin:
  `Const.Msg.Err.PIN_REQUIRED = "Place the pin on the map"` (replaces the decimal-range message for
  these two fields; `VALUE_NOT_A_DECIMAL_IN_RANGE` is removed if nothing else uses it).
- `#modalContent:has(.location-editor)` widens `--modal-content-width` to 720 px, capped by the
  viewport rule already in `core.css`.

Hydrator `static/js/fragments/location-editor.js` (kind `location-editor`, registered in
`fragments/index.js` after the modal hydrator so the host is open)
- Creates the Leaflet map in the fragment's container: tile layer and attribution from
  `data-map-tile-url` / `data-map-attribution`, zoom control, no default marker. Initial view: the
  hidden fields' point at zoom 12 when both are present (a validation replacement), else the world
  (`[20, 0]`, zoom 2).
- Click places the pin or moves it; the pin is a draggable `L.marker` with an `L.divIcon`
  (`fa-map-marker-alt`, `--dnd-drop-target-color`); `dragend` and click both call `setPin(latlng)`,
  which writes the hidden fields (6 decimals) and the readout (4 decimals).
- `invalidateSize()` after the host is displayed: the modal is shown by `x-show` in a later task, so
  the hydrator polls `getComputedStyle(container).display` like `modal.js` does, then also on a
  `ResizeObserver` of the container (the split pane cannot resize the modal, but a window resize can).
- One map at a time: a module-level handle is `remove()`d before a new one is created, so a validation
  replacement of the form does not leak the previous map.
- Geocoder: Enter in the box or the Search button → `GET /api/map/r/:repoId/geocode?q=` through the
  shared axios client; results render as buttons (`label`); clicking one calls `setPin`, `setView`
  at zoom 12, and fills the name field when it is empty. Empty query does nothing; a 404 (disabled
  meanwhile) or 502 is a snackbar. The list is cleared on a new search.
- No Alpine component: the editor is plain DOM inside a modal fragment, like the upload form.

Controller
- `LocationActionController`: the hidden inputs still submit strings, so `normalize`/`validate` keep
  their shape; the coordinate check reports `PIN_REQUIRED` on latitude only (one message), unless
  longitude alone is bad, in which case on longitude, so the template shows a single pin error.
  `addForm` passes what it does today.

Tests (`LocationActionControllerTests`)
- The Add dialog carries `type="hidden" name="latitude"`, the readout element, no `radiusM`, the
  geocoder box absent while disabled (existing), and a validation replacement for a missing pin
  contains `Const.Msg.Err.PIN_REQUIRED` and the submitted name.

Browser verification (dev server, `mill altitude.resources` after static changes; the automation tab
gets no pointer input, so the click is `map.fire("click", {latlng})` and the drag is `marker.setLatLng`
+ `marker.fire("dragend")`)
| Scenario | Expect |
|---|---|
| Open Add location | map with OSM tiles, world view, "No pin yet"; the Add button stays enabled, the server reports a missing pin |
| Submit without a pin | form replaced in place, "Place the pin on the map" under the map, name kept, map re-created |
| Click the map, then drag the pin | hidden fields and readout follow; submit creates the Location; list shows it |
| Validation error after a pin was placed (duplicate name) | the replacement re-creates the map centred on the pin at zoom 12 with the pin shown |
| Geocoder enabled in `application-dev.conf` (`map.geocoder.enabled=true`; the user's call, it sends queries to Nominatim) | search box present, a result click places the pin and fills an empty name; disabled → no box |
| Escape / close | modal closes, no console errors; reopening builds a fresh map |
| Categories: add, move a Location under one, rename, delete | all wording says Category; "Category › Location" in the grouped grid |

## Docs (anti-drift)

- `altitude/AGENTS.md`: **Locations** (Category wording, no radius), **Map** (geocoder is behind the
  Add location editor), **Group by Location** header wording.
- `altitude/views/AGENTS.md`: **Locations** (Category, `data-category-id`, the editor: hidden fields,
  readout, geocoder box, one map at a time, focus trap note), the dialog kinds list, key files
  (`fragments/location-editor.js`), the library list (Leaflet).
- `static/js/lib/README.md`: Leaflet rows. `CONTEXT.md`: **Category**, **Location**, **Pin**.
- `docs/test-coverage.md`: the Locations section (Category; no radius; the editor assertions).
- `plans/locations-and-map-view.md`: §6 decisions above, §4 column list, status line.
- `plans/locations-and-map-view-implementation.md`: status line (Unit 8 done through this plan),
  Unit 8 replaced by a pointer here, Unit 7 wording (`categoryName` on map pins), data model block.

## Verification

1. `make compile`, `make lint`, `make test-unit`, `make test-sqlite`, `make test-controllers`;
   `make test-psql` with the test container up (the schema and the grouped/map SQL change).
2. `grep -rniI "radius" altitude/src altitude/test altitude/views altitude/static/js` (excluding
   `lib/`) → nothing; the `parent` grep of Step A → folders and DOM only.
3. The user recreates the dev database (kind value and column rename), restarts the dev server
   (`ENV=dev mill altitude.runBackground`), then the browser table above.

## Order and size

Three steps, each leaving the tree green, so they can be committed separately: A (rename, the
widest diff, no behaviour change), B (radius), C (editor + Leaflet). Roughly 45 files for A, 15
for B, 10 for C.

## Not in this phase (unchanged from the implementation plan, plus)

- Editing a Location's pin after creation (a "Move pin" menu action would reuse the editor; not in D9).
- Auto-assignment by distance: without a radius there is no rule to apply.
