# Locations Unit 5 follow-ups (before Unit 6)

Status: **done 2026-09-13**, verified with `make compile`, `make lint`, `make test-unit` (86), `make test-controllers` (45), `make test-sqlite` (223). Not committed.

## Context

Commit `6c634f37` (Unit 5, routes) was reviewed against the plan and the album code it copies. It is a
faithful, well-tested implementation; four groups of findings were accepted for a follow-up. Two of
them (the map endpoints) have a downstream cost in Unit 7 if left, the other two are cleanups that are
cheapest before the frontend units build on the templates and controllers.

Per the project convention, on approval this plan is first copied to
`plans/locations-unit-5-followups.md` and moved to `plans/done/` when finished. No commit is made by
the agent.

## 1. Map endpoints: a `viewport` parameter, the full search parameter set, `bbox` ignored by the map

Problem: on `GET /api/map/r/:repoId/cells` the `bbox` query parameter is the viewport and the search
scope's `bbox` is discarded (`scope.copy(bbox = None)`), while on `/bounds` and on the HTML search's
map layout `bbox` is an asset filter. Unit 7 intends the map to keep showing the whole scope while the
crowded-pin panel (which is a `bbox` search) is open, so `layout=map&bbox=…` currently gives a toolbar
total and an initial `fitBounds` that honour the filter while the cells will not. Separately, Cask
rejects undeclared query parameters, which is why `sort` was added to the map endpoints; the client
would otherwise have to hand-strip `layout`, `groupBy`, `groupDirection`, `rpp`, `p`, `after`, `bbox`.

Changes
- `Api.Field.Map` (new): `VIEWPORT = "viewport"`, `ZOOM = "zoom"`.
- `routes/api/MapController.scala`
  - `cells`: rename the viewport argument to `viewport: Option[String]`, parse it directly with
    `BoundingBox.parse` (JSON 400 "viewport is required" / "Invalid viewport: …"); pass `bbox = None`
    to `SearchRequestParser.parse` (or drop it from the scope afterwards, but only in one place).
  - `bounds`: also ignore the scope's `bbox`.
  - Both endpoints add `params: cask.QueryParams /* allow unknown params */` as the last argument, the
    precedent in `routes/web/IndexController.repositoryView`, and drop the dummy `sort` argument.
- `routes/web/partial/SearchResultsController.scala`, map layout branch: `count` and `mapBounds` use
  `scope.copy(bbox = None)`; `browserViewUrl` still carries `bbox` (it is the panel's scope). Comment:
  in map layout `bbox` belongs to the panel, the map reads the whole scope.
- `routes/SearchRequestParser.scala`: no change needed; keep `bbox` there for the grid and the panel.
- Docs: `altitude/AGENTS.md` **Map** paragraph (viewport vs bbox, unknown parameters tolerated) and the
  `layout` paragraph under search; `altitude/views/AGENTS.md` map paragraph. Implementation plan Unit 7:
  the cells request sends the store's search parameters verbatim plus `viewport` and `zoom`; the map
  ignores `bbox` everywhere, only the panel and the grid apply it.

Tests (`controller/MapControllerTests`, `SearchResultsControllerTests`)
- `cells`/`bounds` accept and ignore `layout=map&groupBy=location&groupDirection=up&rpp=0&p=9&after=x&sort=filename0&bbox=…`.
- `cells` with a `bbox` filter still returns the whole scope; the existing "counts preserved across pans"
  case moves to `viewport`.
- Missing / malformed `viewport` is a JSON 400 naming `viewport`.
- HTML `layout=map&bbox=0,0,10,10`: `data-results-total` and `data-map-bounds` reflect the whole
  scope; `HX-Replace-Url` still carries `bbox`.

## 2. Dialog titles from `Const.UI`; validation messages in `Const.Msg.Err`

Problem: Unit 2 added five `*_LOCATION_DIALOG_TITLE` constants to `Const.UI` (unused) and the album
dialogs receive their title from the controller; the Location templates hardcode headings with
different casing, and the coordinate/radius messages are string literals in the controller.

Changes
- `Const.UI`: add `RENAME_PARENT_DIALOG_TITLE = "Rename parent"`, `DELETE_PARENT_DIALOG_TITLE =
  "Delete parent"`; keep the five existing ones. Extend the comment above the object.
- Templates take `title: String` like `rename_album_dialog` / `delete_album_dialog`:
  `add_location_dialog` (`data-app-modal-title`), `rename_location_dialog`, `delete_location_dialog`,
  `move_location_dialog` (heading is the constant; the body names the Location, as the delete button
  does), `add_to_location_dialog` (`data-app-modal-title`). Kind-specific `@if(location.kind == …)`
  branches for the heading move to the controller, which picks the constant by `LocationKind`.
- `Const.Msg.Err`: `VALUE_NOT_A_DECIMAL_IN_RANGE = "Enter a decimal between %s and %s"`,
  `VALUE_NOT_A_POSITIVE_INTEGER = "Enter a positive whole number"` (`.format` in the controller, the
  `VALUE_TOO_LONG` style).
- `LocationActionControllerTests`: assert titles through the constants, not literals.

## 3. Controller cleanups

- `routes/BaseController.scala`: `jsonResponse(value: ujson.Value, status: Int = 200)` and
  `jsonError(message: String, status: Int)` (the `MapController` bodies). Use them in `MapController`
  and `LocationController`; `AlbumController` (the file the Location one copies) may follow, others are
  out of scope.
- `LocationController.membershipChange`: call `unscrubbedJson.get` outside the `Try`, as
  `AlbumController` does, so a wrong content type keeps its `INVALID_CONTENT_TYPE` message; only the
  payload-shape parse is wrapped.
- `LocationActionController`: split `validate` into `normalize(json)` (JSON numbers → strings for the
  three numeric fields, drop null/empty `parentId`/`radiusM`) and a pure `validate(json, required,
  uuid, coordinates)`; each route calls `normalize` then `validate`. Behaviour and messages unchanged.
- `add_to_location_dialog`: render the `locationId` error beside the select and any `assetIds` error
  as a general line, instead of an unlabelled sorted list.
- `plans/locations-and-map-view-implementation.md` status line: Unit 5 is committed.

## 4. Test diagnosability

`withClue` is already the convention (`SearchSqlTests`, `SearchCursorTests`, …). Wrap every looped
assertion so a failure names its case:
- `LocationActionControllerTests`: the invalid-field loop (`s"$field=$invalid"`), the 400/404 action
  and dialog loops, the authentication loops.
- `LocationControllerTests`: the invalid-payload loop.
- `MapControllerTests`: the invalid-params loop, the authentication loop.
- `SearchResultsControllerTests`: the grouping loop and the invalid-params loop added in Unit 5.
No test is split; the clue is enough.

## Files touched

`core/Api.scala`, `core/Const.scala`, `routes/BaseController.scala`, `routes/api/MapController.scala`,
`routes/api/LocationController.scala`, `routes/web/partial/LocationActionController.scala`,
`routes/web/partial/SearchResultsController.scala`, the six `views/htmx/*location*` templates,
`altitude/AGENTS.md`, `altitude/views/AGENTS.md`, the four controller test files,
`plans/locations-and-map-view-implementation.md` (Unit 7 text and status line).

## Verification

1. `make compile`, `make lint`, `make test-unit`, `make test-controllers`, `make test-sqlite`. No SQL
   changes, so `make test-psql` is optional.
2. Grep: no `LOCATION_DIALOG_TITLE` constant unused; no `"Enter a` literal in `routes/`; no `bbox` left
   in `MapController` except the comment explaining it is ignored.
3. No browser step: the frontend for these routes is Unit 6/7.
