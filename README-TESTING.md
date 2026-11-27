# Testing Altitude #

How the tests are organized, how to run them, and what to know before writing one. The development setup is in
[README.md](README.md#development-setup).

## Before the first run

* The Postgres integration tests need the Postgres test database (`altitude-core-postgres-test`, on port 5433), and
  so Docker. Every make target that runs them starts it when it is down and waits until it accepts connections
  (`make test-db`); `make db` starts it together with the dev one.
* The first test run downloads the model files (about 1.6 GB) into the Models directory, the same one the dev server
  uses ([Models](README.md#models)), so a machine downloads them once. The download is logged, so the run does not look
  hung.
* The controller tests start a server on port 8081, which must be free.

## Running the tests

```sh
make test              # the JS tests, then every bundle at once (starts the Postgres test database if it is down)
```

One bundle at a time:

    make test-unit
    make test-sqlite
    make test-psql
    make test-controllers
    make test-js

`make test` runs the bundles in parallel, each in its own JVM and sandbox
(`out/altitude/test/testOnly.dest/worker-N/sandbox`). Each worker's output is in `worker-N.log` and the merged report
in `test-report.xml`, both in `out/altitude/test/testOnly.dest/`. The run takes as long as the slowest bundle, a few
minutes.

The tests log nothing of the application's: `altitude/test/resources/logback-test.xml` silences it. To see a logger's
lines, raise its level there for the run.

### Running a tagged test(s)

Tag the test as `Focused`:

```scala
test("work in progress", Focused) {
}
```

Then:

    make test-focused

For integration tests, **this runs the test twice, once for each database**. To run the one bundle:

    make test-focused-sqlite
    make test-focused-psql
    make test-focused-controllers
    make test-focused-unit

A name filter (`-z`) does not reach the tests inside a bundle, so `Focused` is the way to pick one test.

### Slow tests

A test that seeds a library's worth of rows, runs a model many times over, or binds tens of thousands of IDs is tagged
`Slow` (`org.scalatest.tagobjects.Slow`):

```scala
test("A selection larger than a statement's parameter limit is added to an album", Slow) {
}
```

Every target skips them but `make test-with-slow` and the focused ones, so to run a `Slow` test, focus it.

## How the tests work

There are four kinds of tests:

| Kind | What they test | Bundle | Runs against |
|---|---|---|---|
| Integration | The services, with a real database (`altitude/test/src/altitude/core/integration/`) | `SqliteSuiteBundle`, `PostgresSuiteBundle` | SQLite, then Postgres |
| Controller | The web interface, over HTTP (`altitude/test/src/altitude/core/controller/`) | `ControllerSuiteBundle` | SQLite |
| Unit | Models and functions, without a database (`altitude/test/src/altitude/core/unit/`) | `UnitSuiteBundle` | Nothing |
| JS | The browser's DOM-free modules (`scripts/test-js/`) | None: `make test-js` | Node's built-in runner, no dependencies |

### Bundles

The Scala tests run only through the bundles in `altitude/test/src/altitude/core/suites/`. The suites of each kind are
listed in one place: `AllIntegrationTestSuites`, `AllControllerTestSuites` and `AllUnitTestSuites`. A new suite is
added to its list, or it never runs.

Every suite is annotated `@DoNotDiscover`, because the bundles run it: if the test runner found it too, it would run
twice, once on its own and once in its bundle.

### Integration tests

The integration suites run **twice**, under `SqliteSuiteBundle` and under `PostgresSuiteBundle`, with no change to
the tests. Each bundle builds its own app instance (`TestAltitudeApp.build`) on its database engine, and resets the
database before its suites run: every table dropped, the schema created again and the Settings seeded
(`TestDatabase.reset`). The database then keeps every test's accounts and Libraries, so each test makes its own
(`TestContext`), with unique names.

SQLite has one write connection, which serializes the writers, so a concurrency bug can pass there. A test of
concurrent writes has to fail on Postgres first (`make test-focused-psql`).

### Controller tests

The controller tests run once, under SQLite. Each test starts a real HTTP server (Undertow, on port 8081) that serves
the app, and makes its requests to it over HTTP (`ControllerTestCore.withServer`).

The server has an app instance of its own, over the same SQLite file as the test's instance. Before each test, the
database is reset and the server's instance loads the Settings again, so a test never sees another's rows. A test
builds its fixtures through its own instance, never over HTTP.

A test logs in the way a person does: `login()` posts the test account's email and password to `/login` and keeps the
session cookies for the requests that follow. There is no test-only way in. `loginAs(accountType, permissions)` logs
in an account with the Permissions the test names.

### Scene models

The test instances have the Scene models (DINOv2, SigLIP 2) switched off: an import runs no model pass, which would
take about 0.75 s an image, and stores no Scene embedding. A suite whose subject is the embeddings turns the models on
for all its tests (`override def usesSceneModels = true`, `usesDescribeModel = true`), or for one test
(`withSceneModels { ... }`, `withDescribeModel { ... }`). The Scene answers are cached by the image's pixels, so an
image imported test after test is embedded once.

### On every system

The unit and SQLite suites and the JS tests also run on Linux, macOS and Windows, in the jars workflow
(`.github/workflows/jars.yml`). A test resource's path comes from its URL's URI (`Path.of(url.toURI)`), never
`URL.getPath`, which is wrong on Windows.

## Writing a test

The rules for a test (the comment that opens every test, the fixtures, the helpers) are in
[altitude/AGENTS.md](altitude/AGENTS.md), under **Tests** and **Test Structure**. Recipes for common checks and
fixtures are in [docs/testing-cookbook.md](docs/testing-cookbook.md): assertions, raw SQL, fixtures and media, accounts and Permissions,
controller requests, Settings, waiting, concurrency, failure injection and log capture.
