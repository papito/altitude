# A faster test suite: Scene models off in tests, bundles in parallel

## Goals

- A fixture import in a test runs no Scene model unless its suite asks for them, so the integration bundles take minutes
  instead of about twenty each.
- The few tests whose subject is the Scene embeddings run on the real models, by their suite's own opt-in.
- The bulk fixtures that never read a file, a Preview or a measurement persist their assets without the import pipeline.
- `make test` runs the four bundles at once, one JVM each, and `make test-local` runs the three that need no PostgreSQL at
  once.

Out of scope: deleting or merging tests, sharding a bundle across JVMs, one class per suite, the PostgreSQL container's
durability settings.

## Measurements the design rests on

The four bundles, run one after another on the 24-core, 31 GB dev machine: Unit 26 s, SQLite 1,121 s, Controller 237 s,
PostgreSQL 1,167 s. PostgreSQL is about 4% slower than SQLite on the same tests, so the engine is not what costs.

`TestContext.persistAsset` runs the whole import pipeline, and the measurement stage runs both Scene models on one thread,
about 365 ms for DINOv2 and 270 ms for CLIP (`util/SceneEmbedder`): about 0.68 s per fixture import in all. The SQLite
bundle with `AssetService.measureImage` embedding nothing takes 131 s, its tests 94 s of it, and 9 of its 551 tests fail:
the 8 in `SequenceServiceTests` that pair or part real frames by their Scene embeddings, and `AssetImportServiceTests`'
"A still image is imported with the Scene embedding of each model, stored with its completion". With the models off, the
two tests over `CurationServiceTests.longSequence` (103 imports each) take 2.2 s each, and the remaining cost of a fixture
import is about 45 ms.

The controller bundle's reset before each test (wipe, migrate, Settings) costs under 60 ms a test, and stays.

Mill 1.0.6 has `testParallelism` on: with `-j` above 1, each test class a run selects is claimed by a worker JVM of its
own, which runs in its own sandbox (`out/altitude/test/<task>.dest/worker-N/sandbox`). The tests' relative `test-data`
directory, which holds the SQLite file and the file store, is therefore a worker's own, and the bundles share no file. The
PostgreSQL bundle is the only one on the PostgreSQL test database. The unit and controller bundles run this way at once
take 253 s, the controller bundle's own time, and pass.

## Decisions

| Concern | Decision |
|---|---|
| Where the switch lives | A test double of the embedder, off by default, which the test bundles build their instances with. Production builds the ONNX one, as now |
| How a test turns the models on | Its suite: `override def usesSceneModels = true` for a whole suite, `withSceneModels { ... }` around one test |
| Which suites turn them on | `SequenceServiceTests` (whole suite: its subject is pairing by the models) and the one `AssetImportServiceTests` test (`withSceneModels`) |
| When the real models load in a test JVM | On the first embedding with the switch on, so a bundle that never turns them on never opens the sessions (about 1.1 GB) |
| Fixtures with the models off | Imported as now, measured and hashed, with no Scene embedding. A frame with a hash and no embedding pairs by the hash alone, which two random images never come near |
| The controller server's own instance (`App.altitude`) | Builds the ONNX embedder, as production does; the controller tests import through the suite's instance, never over HTTP |
| Bulk fixtures | `TestContext.insertAsset`, the asset persisted, indexed and completed (`persistAndIndex`, then `completeImport`) with no file, Preview, faces, measurements or Sequence, for `CurationServiceTests.longSequence` and `CurationActionControllerTests.frame` |
| `persistAsset` | Unchanged, the pipeline import. It still clears a Sequence the import paired, which can happen where a suite turns the models on |
| Parallel runs | Mill's own workers (`-j`), one bundle class per worker. No per-bundle directory or database name in code |

## Constraints

- No change in production behavior: production and the dev server build `OnnxSceneEmbedder` in the constructor, so a
  missing model file still fails the start.
- No schema change.
- Tests run serially within a bundle, so a flag the suite sets in `beforeAll` and clears in `afterAll` is seen by no
  other suite. The flag is `@volatile`, since the embedding runs on the import dispatcher's threads.

## Changes

There is no new behavior to drive red-green: group A is a refactor of the embedder's wiring and of the test setup, and the
existing suites are its guard. The 9 tests that need the real models fail while their opt-in is missing, and pass with it.

### A. The Scene models off in tests

1. **A trait for the embedder (`util/SceneEmbedder.scala`).** `trait SceneEmbedder extends AutoCloseable` with
   `embed(image: Mat, name: String): SceneEmbeddings`. Today's class becomes `class OnnxSceneEmbedder extends SceneEmbedder`
   in the same file, its body unchanged; `object SceneEmbedder` keeps the shared constants. The Scaladoc of the class moves
   to `OnnxSceneEmbedder`, and the trait's says what an embedder answers.
2. **The embedder is the instance's (`Altitude.scala`, `AssetService.scala`).** `Altitude` takes
   `newSceneEmbedder: () => SceneEmbedder = () => OnnxSceneEmbedder()` after `dbEngineOverride`, and builds
   `val sceneEmbedder` from it in the constructor, before the services. `AssetService` uses `app.sceneEmbedder`; its
   `close` goes, and `Altitude.cleanup` closes `sceneEmbedder` where it closed the service, after the import pipeline
   has shut down. Update the startup-order comments in `Altitude.scala` and the `AssetService` notes on the sessions.
3. **The test double (`test/src/altitude/test/SwitchableSceneEmbedder.scala`).** `@volatile var on = false`; a `lazy val`
   `OnnxSceneEmbedder`, opened the first time `embed` runs with `on`; `embed` answers the real embedder's result when on
   and `SceneEmbeddings()` when off; `close` closes the real one only if it was opened.
4. **The test instances build with it.** `SqliteSuiteBundle.testApp`, `PostgresSuiteBundle.testApp` and the second
   instance of `StartupTests` (`StartupTests.scala:31`) pass `() => SwitchableSceneEmbedder()`. One helper in
   `altitude.test` builds a test instance for an engine, so the three agree.
5. **The opt-in (`IntegrationTestCore.scala`).** Mix in `BeforeAndAfterAll`. `protected def usesSceneModels: Boolean =
   false`; `beforeAll` sets the switch to it and `afterAll` turns it off. `withSceneModels[T](f: => T): T` turns it on
   around `f` and puts back what it was. The switch is `testApp.sceneEmbedder` matched as a `SwitchableSceneEmbedder`,
   failing with a clear message for any other embedder.
6. **The suites that opt in.** `SequenceServiceTests` overrides `usesSceneModels`. `AssetImportServiceTests` wraps the
   body of "A still image is imported with the Scene embedding of each model, stored with its completion" in
   `withSceneModels`.
7. **The unit suite.** `SceneEmbedderTests` builds `OnnxSceneEmbedder()`.

### B. A fixture without the pipeline

8. **`TestContext.insertAsset(asset: Asset = makeAsset())` (`TestContext.scala`).** Persists the asset through
   `library.persistAndIndex`, completes it through `library.completeImport`, records it in `assets`, and returns it. Its
   Scaladoc says what it leaves out (file, Preview, faces, Image quality, hash, Scene embeddings), and so that it is in no
   Sequence. `StatsServiceTests.importedAsset` calls it with its sized asset instead of the two calls of its own.
9. **The bulk fixtures.** `CurationServiceTests.longSequence` and `CurationActionControllerTests.frame` use
   `insertAsset`. Both already set what their tests read (capture times, the Sequence, the Image quality); a test of
   either suite that reads a file, a Preview or a measurement it does not set keeps `persistAsset`.

### C. The bundles in parallel

10. **`Makefile`.** `test` becomes `ENV=test mill -j4 altitude.test.testOnly '*SuiteBundle'`, and `test-focused` the same
    with `-- -n focused -oD`. A new `test-local` runs `'*UnitSuiteBundle' '*SqliteSuiteBundle' '*ControllerSuiteBundle'`
    with `-j3`. The single-bundle targets stay as they are.

### D. Docs and comments

11. **`altitude/AGENTS.md`.** The startup order (the embedder before the services). **Test Structure:** fixture imports
    carry no Scene embedding unless the suite opts in (`usesSceneModels`, `withSceneModels`), the test double and when
    it opens the real models, and `insertAsset` beside `persistAsset`. **Build & Test Commands:** `make test` runs the
    four bundles at once, one worker JVM and sandbox each, and needs the PostgreSQL container; `make test-local` runs
    the other three at once.
12. **`docs/test-coverage.md`.** Where it describes a pipeline import's Scene embeddings in the suites, say which suites
    run the real models.
13. **Code comments.** `ImportParallelism.scala` (the sessions are `OnnxSceneEmbedder`'s), `AssetService.measureImage`'s
    Scaladoc, the `persistAsset` comment on pairing by likeness (it can pair only where the suite turns the models on),
    and `IntegrationTestCore`.

## Critical files

- `altitude/src/altitude/core/util/SceneEmbedder.scala`, `altitude/src/altitude/core/Altitude.scala`,
  `altitude/src/altitude/core/service/AssetService.scala`, `altitude/src/altitude/core/util/ImportParallelism.scala`
- `altitude/test/src/altitude/test/SwitchableSceneEmbedder.scala` (new), `altitude/test/src/altitude/test/TestContext.scala`
- `altitude/test/src/altitude/core/integration/IntegrationTestCore.scala`, `SequenceServiceTests.scala`,
  `AssetImportServiceTests.scala`, `CurationServiceTests.scala`, `StatsServiceTests.scala`, `StartupTests.scala`
- `altitude/test/src/altitude/core/suites/SqliteSuiteBundle.scala`, `PostgresSuiteBundle.scala`
- `altitude/test/src/altitude/core/controller/CurationActionControllerTests.scala`
- `altitude/test/src/altitude/core/unit/SceneEmbedderTests.scala`
- `Makefile`, `altitude/AGENTS.md`, `docs/test-coverage.md`

## Verification

1. `make compile`, then `make lint`.
2. Before step 6, `make test-sqlite` fails exactly the 9 tests named above; after it, every bundle passes:
   `make test-local`, then `make test` with the PostgreSQL container up.
3. `make test` three times in a row with no failure, since the bundles now share the CPU: the tests of contended locks
   (`contend`), of the queue's restart backoff and of the PostgreSQL read time limit are the ones to watch.
4. Timings from the reports (`out/altitude/test/testOnly.dest/test-report.xml`): the SQLite and PostgreSQL bundles each
   take a few minutes, `CurationServiceTests` no longer leads them, and the wall time of `make test` is about that of the
   slowest bundle. `ls out/altitude/test/testOnly.dest/` shows one `worker-N` per bundle.
5. The real models open late and once: with the `altitude.core.util` logger raised in `logback-test.xml` for the check
   (and restored after), `make test-sqlite` logs "Opened the ONNX session of Scene model" for each model once, when
   `SequenceServiceTests` or the opt-in `AssetImportServiceTests` test first embeds, and not at the bundle's start.
6. The dev server starts and imports a photo with both Scene embeddings stored, as before.
