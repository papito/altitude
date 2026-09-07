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
- `static/js/alpine/components/` — Alpine components (`selectable.js`, `context-menu.js`), registered from its `index.js` before Alpine starts
- `static/js/fragments/` — declarative HTMX fragment hydration (`data-app-fragment="..."`), including the operation lifecycle shared by modal and inline dialogs (`dialog-operations.js`)
- `static/js/listeners/` — `document.body` custom-event and HTMX lifecycle wiring
- `static/js/assets/` — asset mutation/action flows (move, recycle, purge, restore)
- `static/js/search-results/` — the single search funnel (`search.js`), its declarative `data-app-search` triggers (`search-triggers.js`), detail navigation and image-detail coordination over the rendered grid (`detail-navigator.js`), the date headers' counts (`date-groups.js`), asset drag/drop (`dragon-drop.js`), and box selection (`box-selection.js`, built on the vendored Viselect in `static/js/lib/`, committing through the existing `selectable` component). Both gestures swallow the click that follows them through `click-suppression.js`
- `static/js/dragdrop/` — interact.js binding modules for batch, people, folder, and album drag/drop; the drop-target highlighting they share is `dropzoneListeners` in `static/js/common/dragon-drop.js`
- `static/js/common/folder-tree.js` — renders the folder tree client-side from `/api/folder/r/:repoId/tree` (assembled by `FolderService.getTree`, which also rolls up each folder's recursive `numOfAssets`), including each folder's native popover context menu, so no menu markup comes from the server; the menu's actions load the folder dialogs inline into the menu panel. After asset mutations `refreshFolderCounts` patches the counts in place. Branch expansion (single-click one level, double-click all levels, collapse resets descendants) and the green viewed-folder highlight are specified in `views/AGENTS.md` under **Folder tree expansion and viewed scope**
- `static/js/common/album-list.js` — renders the flat album list client-side from `/api/album/r/:repoId/list` (`AlbumController`), with the same menu (`common/context-menu.js`, shared with the folder tree) and count cell (`common/asset-count.js`); `refreshAlbumCounts` patches the counts in place after membership changes and asset mutations. Albums are pointers only: see **Albums** in `views/AGENTS.md`
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

## Albums

An `Album` is a flat, repository-scoped, uniquely named (case-insensitive) list of pointers to assets: the `album` table plus the `album_asset` membership table, which has no model of its own and is written only through `AlbumDao`. An asset can be in any number of albums. Nothing album-related touches an asset: adding to, removing from, renaming, or deleting an album (a hard delete; memberships cascade) changes membership rows only. The reverse direction is `LibraryService.recycleAssets`, which drops recycled assets from every album in the same transaction (so folder deletion does too); restoring does not re-add them, and purging deletes the asset row, whose foreign key cascades. `Album.numOfAssets` is computed on read (`AlbumDao.getAll`). Searching within an album is the `albumIds` filter of `SearchQuery` (`asset.id IN (SELECT asset_id FROM album_asset ...)`), reached through the `albumId` search parameter.

## Search results and date grouping

Two search paths share one definition of "what matches": `LibraryService.search` (full `Asset` records for the ungrouped HTML grid) and `LibraryService.searchGrouped` (a grouped page for the grouped HTML grid). Both resolve the folder scope the same way (`withResolvedFolderScope`: the root folder means no folder filter, any other folder means itself plus its current descendants) and then use the engine's `SearchDao`, whose `SearchQueryBuilder` builds every predicate (repository, view flags, pipeline completion, text, folders, people, albums, metadata filters with their `GROUP BY asset.id` deduplication) once for both.

`SearchQuery.grouping` (`SearchGrouping(GroupBy.DateTaken | DateImported, direction)`) turns a query into a grouped one; a grouped query has no page number. `SearchQueryBuilder.buildGroupedSearchSql` emits **one statement** per page: a materialized, narrow `candidates` slice (ID, day, sort key; `LIMIT rpp + 1` to detect continuation), the `page`, `day_counts` as one correlated count per distinct day on the page, the page joined back to `asset` for its full rows and, on a first page only, `total` over all matches (the dominant cost on a large library, and the footer total is set once). Everything is ordered by day, then the sort, then `asset.id`. The engine-specific `AssetSearchQueryBuilder`s supply the day expression (`date(col)` on SQLite; `original_created_at::date` and `(created_at AT TIME ZONE 'UTC')::date` on PostgreSQL), null placement, and on SQLite a unary `+` on the secondary sort term so the planner keeps the grouping day index. The DAO returns `GroupedSearchRow`s (the `Asset`, its day, its `SortValue` exactly as stored, its day's count) in a `GroupedSearchPage` (rows, `total` on a first page, `hasMore`); `SearchService.searchGrouped` folds consecutive rows of one day into `AssetDateGroup`s and builds the next cursor. `GroupedSearchResult` also carries `continuesDay`, the day the previous page ended on when this page was reached by cursor.

Paging is by cursor only: `SearchCursor` is an opaque, versioned Base64URL token holding the last returned image's day, sort value and ID, and a fingerprint of the search as requested (engine, repository, filters, grouping, ordering; a folder filter as given, not its expanded descendants). `LibraryService.searchGrouped` recomputes the fingerprint and rejects a mismatch with `SearchCursorException`; a cursor supplies a position only, never access or SQL, and the page size is not part of it. Results are live: a cursor continues from values, so deleting its anchor or inserting before it neither skips nor repeats the remaining images, while the counts describe the current matching set.

The contract on `GET /htmx/search/r/:repoId` is HTML only: JSON negotiation (`Accept` or the legacy `Content-Type: application/json`) is a 400 with a `{"error": ...}` body, grouped or not. Nothing in the app asks for results as JSON - the detail modal walks the rendered grid (see **Detail navigation** in `views/AGENTS.md`).

| Parameter | Values |
|---|---|
| `groupBy` | `dateTaken` (capture day, the camera's calendar date) or `dateImported` (import day in UTC). Anything else, including empty, is a 400. |
| `groupDirection` | `asc` or `desc` (default). Needs `groupBy`. |
| `sort` | Field plus direction digit, one of the results UI's fields (`Const.Search.SORT_FIELDS`). Applies within each day. |
| `rpp` | 1 to `Const.Search.MAX_GROUPED_RPP` (500); default 50. May differ between a page and its continuation. |
| `after` | The previous page's cursor, sent with `isContinuousScroll`. Rejected for another search. `p` is a 400 with `groupBy`. |

A first page renders `includes/search_results` with `htmx/results_grid_grouped` and an `HX-Replace-Url` carrying `groupBy` and `groupDirection`; a continuation renders the grid alone and is a 204 when the continuation is empty. The grouped grid opens each day with a `date-group` header (`Util.humanReadableDate`, the day's full count; the first header is skipped when the group continues `continuesDay`, so the new cells read as the same group), and its last cell carries the encoded cursor in `data-app-search-after`. Both grids render cells through `htmx/result_cell`, which puts exactly one of `data-app-search-next-page` (ungrouped) or `data-app-search-after` (grouped) on a page's last cell. Validation errors and rejected cursors are plain-text 400s, reported by the snackbar. The frontend side - the Group dropdown, the `groupBy`/`groupDirection` parameters in the `searchParams` store, cursor continuation shared by the scroll observer and the detail modal, and the header counts - is documented in `views/AGENTS.md` under **Search parameters**, **Infinite scroll + lazy load** and **Detail navigation**.

Date storage behind this: `original_created_at` is the camera's wall-clock time with no zone (PostgreSQL `TIMESTAMP WITHOUT TIME ZONE`, SQLite `yyyy-MM-dd HH:mm:ss` text) and is parsed and bound as a `LocalDateTime` with no instant conversion, so its calendar day never depends on the JVM or server zone. `created_at` on assets is bound explicitly in UTC by `AssetDao.add` (an `OffsetDateTime` on PostgreSQL, UTC text on SQLite) instead of relying on the engine default. Missing or unreadable capture metadata still falls back to the local import time. The indexes `asset_search_date_taken` / `asset_search_date_imported` are `(repository_id, is_recycled, is_pipeline_processed, <day expression>, <raw timestamp>)`: the day gives seeks and per-day counts, the trailing timestamp makes a same-field grouping and sort read in index order. Nulls sort where each engine puts them (no `NULLS LAST`, which would forfeit index-ordered reads); a legacy SQLite row with a null `created_at` has no import day and is left out of Date Imported grouping and its totals. There is no incremental migration for any of this: the capture column type and the two indexes live in `all.sql` only, the schema version stays 2, and a database is created from scratch.

## Schema migrations

`schemaVersion` in `Altitude.scala` is the current version. A fresh database (version 0) runs `migrations/<engine>/all.sql` once and is stamped with the current version; an existing database runs `migrations/<engine>/<version>.sql` for each version it is behind, in every environment (dev included), so a development database keeps its data. A schema change therefore means: bump `schemaVersion`, add both `<version>.sql` files, and add the same statements to both `all.sql` files.

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
make lint                 # scalafmt + scalafix + prettier/eslint
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

Integration tests extend `IntegrationTestCore`. `beforeEach` auto-creates a fresh repository and file-store directory. Tests run against both DB engines via `SqliteSuiteBundle` / `PostgresSuiteBundle` without code changes. `TestContext.setAssetDates` rewrites an asset's capture and import timestamps in each engine's storage form for date-dependent fixtures. Controller tests run against SQLite through a real HTTP server (`ControllerTestCore`).

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

