# Copilot coding agent instructions (Altitude DAM)

## Repo summary
Altitude is a self-hosted, web-based **digital asset manager (DAM)** for organizing a personal media library. It’s designed to be simple to deploy (Java runtime), resilient, and fast.

## Tech stack / tooling (as used in this repo)
- **Language:** Scala 3
- **Build tool:** **Mill** (pinned in `build.mill` via `//| mill-version: 1.0.6`)
- **Scala version:** 3.6.3 (see `build.mill`)
- **Web server / routing:** Cask (`cask.*`), with route controllers under `altitude/src/altitude/core/routes/**`
- **Templates:** Twirl (`views/**`, and Mill Twirl module in `build.mill`)
- **Streaming/actors:** Pekko
- **Computer vision:** OpenCV (native libs loaded via Bytedeco; see `altitude/src/altitude/core/App.scala`)
- **Databases:** SQLite (default) or Postgres (configurable)
- **Formatting/linting:** Scalafmt + Scalafix via Mill (`.scalafmt.conf`, `.scalafix.conf`)
- **Optional UI tooling:** Node/NPM for eslint/prettier (`package.json`)

## Project layout (where to make changes)
- **Server entrypoint:** `altitude/src/altitude/core/App.scala`
  - Registers all HTTP routes in `allRoutes`.
- **Application wiring/config/migrations:** `altitude/src/altitude/core/Altitude.scala`
  - Loads config based on `ENV` (`dev`, `test`, `prod`).
  - Runs migrations on startup.
- **Controllers:**
  - Web: `altitude/src/altitude/core/routes/web/**`
  - API: `altitude/src/altitude/core/routes/api/**`
  - Common routing helpers/decorators: `altitude/src/altitude/core/routes/BaseController.scala`, `.../decorators.scala`
- **Services:** `altitude/src/altitude/core/service/**`
- **DAOs + DB-specific overrides:** `altitude/src/altitude/core/dao/**`
- **Config + migrations:**
  - Defaults: `altitude/resources/reference.conf`
  - Migrations: `altitude/resources/migrations/{sqlite,postgres}/`
  - Logging: `altitude/resources/logback.xml` (tests: `altitude/test/resources/logback-test.xml`)
- **Static assets:** `static/**`
- **Twirl views:** `views/**`
- **Legacy code:** `src.legacy/**` (avoid unless you’re intentionally working on legacy)

## Build & validation (commands and order)
### Compile (always do this)
Use the Makefile target (verified working in this repo):

```sh
make compile
```

This runs `mill altitude.compile`.

After compiling, never attempt to fix warnings or errors by editing generated sources (e.g. Twirl templates under `out/altitude/compileTwirl.dest`); instead, fix the original source (e.g. `views/**`).

### Tests
Only run `make test-sqlite` and `make test-controllers`, and only at the end of all initial edits. Do not run `make test` as Postgres may not be available.

### Run locally (dev)

For hot-reload/watch mode:

```sh
make watch
```

### Lint / format
Scala formatting and Scalafix are driven by Mill:

```sh
make lint
```

Optional JS/CSS lint/format requires installing Node deps first:

```sh
npm install
# then npm run format / npm run lint:fix
```

(Uses `docker-compose.yml` and `docker-compose.test.yml`.)

## Common gotchas
- Twirl templates are compiled as generated sources; template changes can break Scala compilation.
- Only touching code under `altitude/` (current module) and never under `src.legacy/`.

## Efficiency tips
- Start from the entrypoint (`App.scala`) to find which controller owns an endpoint.
- If editing a route, search in `altitude/src/altitude/core/routes/**` first.
- Trust these instructions for build/run commands; only search further if something is missing or out of date.
