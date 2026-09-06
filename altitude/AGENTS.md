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
- `static/js/alpine/components/` — Alpine components (`selectable.js`, `folder-menu.js`), registered from its `index.js` before Alpine starts
- `static/js/fragments/` — declarative HTMX fragment hydration (`data-app-fragment="..."`), including the operation lifecycle shared by modal and inline dialogs (`dialog-operations.js`)
- `static/js/listeners/` — `document.body` custom-event and HTMX lifecycle wiring
- `static/js/assets/` — asset mutation/action flows (move, recycle, purge, restore)
- `static/js/search-results/` — the single search funnel (`search.js`), its declarative `data-app-search` triggers (`search-triggers.js`), shadow-results/detail navigation and image-detail coordination, asset drag/drop (`dragon-drop.js`), and box selection (`box-selection.js`, built on the vendored Viselect in `static/js/lib/`, committing through the existing `selectable` component). Both gestures swallow the click that follows them through `click-suppression.js`
- `static/js/dragdrop/` — interact.js binding modules for batch, people, and folder drag/drop
- `static/js/common/folder-tree.js` — renders the folder tree client-side from `/api/folder/r/:repoId/tree`, including each folder's native popover context menu, so no menu markup comes from the server; the menu's actions load the folder dialogs inline into the menu panel. Branch expansion (single-click one level, double-click all levels, collapse resets descendants) and the green viewed-folder highlight are specified in `views/AGENTS.md` under **Folder tree expansion and viewed scope**
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
- `BaseDao` (JDBC) has abstract methods (`jsonFunc`, `nativeBool`, `getBooleanField`, `getDateTimeField`, `forUpdate`) filled in by `PostgresOverrides` or `SqliteOverrides` traits.
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

All models extend `BaseModel` and use Play JSON with snake_case:

```scala
object Asset:
  implicit val config: JsonConfiguration = JsonConfiguration(SnakeCase)
  implicit val format: OFormat[Asset]     = Json.format[Asset]
  implicit def fromJson(json: JsValue): Asset = Json.fromJson[Asset](json).get
```

DAO methods pass `JsObject` across the service↔DAO boundary; services convert to typed models via the implicit `fromJson`.

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
make test-focused-sqlite  # run tests tagged `Focused` against SQLite only
make publish              # fat JAR → target/
```

> **Do not run `make test`** (requires a live Postgres container). Use `make test-sqlite` and `make test-controllers`.
> **Do not run tests if only the frontend was changed** (Twirl templates, CSS, JS, HTML) — these can be manually verified in the browser without running the full test suite.

To focus a test, tag it with the `Focused` tag:
```scala
test("my wip test", Focused) { ... }
```

## Test Structure

Integration tests extend `IntegrationTestCore`. `beforeEach` auto-creates a fresh repository and file-store directory. Tests run against both DB engines via `SqliteSuiteBundle` / `PostgresSuiteBundle` without code changes.

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

