# Altitude DAM – Agent Guide

## Do

* Update this document if you are making changes to the architecture, design patterns, or anything else that future developers should know when working on the codebase. 
* Factor out duplicated code into helper methods.
* Update comments and docstrings when making changes to the code and warn about discrepancies in comments vs code.

## Architecture Overview

`App.scala` is the single entrypoint (extends `cask.Main`). It registers all routes and wires the app via `new Altitude()`.

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

