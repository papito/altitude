# Import pipeline refactor

## Original goals

- No single asset can stop the import queue: whatever a stage throws drops that asset, reports it, and the queue goes on.
  One place expresses the rule, so a new stage cannot forget it.
- The user sees why an import was dropped, in the status ticker. The log records each drop once, with a stack trace only
  for a cause nobody expected.
- A dropped asset leaves nothing behind: no asset row, no faces, no face count it added, no person only it created, no
  stored file, no preview, no staged file. The same file can be uploaded again right away. An import that a crash or a
  shutdown cut off is cleaned up the same way at the next startup.
- Shutdown waits for the assets already accepted, up to a limit, before the database closes.
- An asset the queue did not accept is reported as such, not as queued.
- In development, a queue whose stream fails all the same restarts itself, behind a switch that is off by default.
- Import work runs on its own bounded threads, never on the actor system's default dispatcher.
- The CPU-bound work of an import (metadata, face detection, preview) uses more than one core per repository, with
  database writes and face matching still one asset at a time, in upload order.

Out of scope: retrying a dropped asset; a durable queue (queued uploads are lost on a crash, and startup discards what
they left); widening the 32-bit `checksum` that `asset_01` makes unique per repository; moving the purge pipeline's work
off the default dispatcher.

## Status

Done.

## Confirmed decisions

- A face-recognition or preview failure drops the whole asset; it is not imported without faces or without a preview.
- Losing the in-memory queue on a crash is acceptable for now.
- The guard returns the stage's result and scopes the request context to the stage; its asynchronous form runs it on the
  import dispatcher.
- A dropped import is undone completely, face recognition included: the face counts it added are given back, and a
  person left with no face rows is deleted with its files. The startup prune discards a dangling asset the same way.
- The ticker names a cause it does not know by the exception's class name, never by its message.
- An expected cause (unsupported media type, undecodable image or video, duplicate) is logged at INFO in one line;
  anything else at ERROR with the stack trace.
- A queue restarts after a failure only in development, behind `dev.restart_pipeline`.
- `import.parallelism` defaults to half the cores, never below 2. Every import thread holds its own ArcFace and YuNet
  instance (`FaceDetectionService.arcFaceNetLocal`, `yuNetLocal`), so the setting bounds native memory as well as CPU.
- At shutdown, both queues drain for at most `pipeline.shutdown_timeout`, which defaults to 30 s.

## Current behavior

`ImportPipelineService.combinedFlow` groups by repository and runs, per substream: `CheckMediaTypeFlow`,
`CheckDuplicateFlow`, `AssignIdFlow`, `ExtractMetadataFlow`, `IndexFlow`, `.async`, `FacialRecognitionFlow`, `.async`,
`FileStoreFlow`, `.async`, `AddPreviewFlow`, `StripBinaryDataFlow`, `MarkAsCompleteFlow`; the merged output goes to
`WsAssetProcessedNotificationSink` and `AssetErrorLoggingSink`. `runAsQueue` materializes it once behind a
`Source.queue`, which `ImportController` offers each staged upload to through `addToQueue`; `run` materializes it per
call (`LibraryService.addAsset`, tests). `PurgePipelineService` has the same queue shape over `DeletePersonFilesFlow`,
`DeleteAssetFilesFlow` and `DeletePurgedFromDBFlow`, fed by `enqueue`.

- **Failures.** The stream has no supervision strategy. A stage function that throws fails the stream, and the queue
  source stops with it. An offer pending at that moment completes as `QueueClosed`; every later `offer` fails with
  `StreamDetachedException`, which `addToQueue` passes on, so `Await.result` in `ImportController` throws and every
  upload answers 500 until restart. What each stage rescues:

  | Flow                    | Rescues                                                                                  |
  |-------------------------|------------------------------------------------------------------------------------------|
  | `CheckMediaTypeFlow`    | `UnsupportedMediaTypeException` (a `map` stage)                                          |
  | `CheckDuplicateFlow`    | none; produces `DuplicateException` itself                                               |
  | `AssignIdFlow`          | none (`map`, only reshapes)                                                              |
  | `ExtractMetadataFlow`   | `VideoException`, around the dimensions only; the extractor call is outside the `try`    |
  | `IndexFlow`             | `DuplicateException`                                                                     |
  | `FacialRecognitionFlow` | `DuplicateException` (as `SamePersonDetectedTwiceException(e.message.get)`), `NonFatal`  |
  | `FileStoreFlow`         | none                                                                                     |
  | `AddPreviewFlow`        | `NonFatal`                                                                               |
  | `StripBinaryDataFlow`   | none (`map`; `staging.discard` can throw)                                                |
  | `MarkAsCompleteFlow`    | none                                                                                     |

- **Causes shown and logged.** `ImportStatusWsActor` names `DuplicateException`, `UnsupportedMediaTypeException` and
  `StorageException`, and shows "Unknown error importing <name>" for anything else. An image `ImageIO` cannot read is a
  `NullPointerException` (`AssetService.getDimensionsAndDuration` dereferences the `null` `ImageIO.read` returns). A
  video no frame of which decodes fails `VideoService.previewFrame` with a bare `RuntimeException`.
  `AssetErrorLoggingSink` logs every dropped asset at ERROR (`cause.toString`); `CheckMediaTypeFlow` adds an ERROR line
  of its own, and `FacialRecognitionFlow` and `AddPreviewFlow` an ERROR with the stack trace.
- **Dropped assets.** A dropped asset is `Right(InvalidAsset(payload, cause, stagedFile))`, and `StripBinaryDataFlow`
  discards its staged file. An asset dropped after `IndexFlow` keeps its row (`is_pipeline_processed = false`) and its
  search document. After face recognition it also keeps its faces and face files, the face counts it added
  (`PersonService.addFace` increments `num_of_faces`), and any person it created, whose cover is the asset's face. After
  `FileStoreFlow` it keeps its stored file, and after the preview its preview. `AssetService.getByChecksum` does not
  filter on `is_pipeline_processed`, so until the next startup the same file is rejected as a duplicate of an asset
  nobody can see.
- **Startup.** `App` runs `pruneDanglingAssets()`, `requeuePurgePending()` and `reconcileStats()`, and `Altitude`
  empties staging. The prune deletes the rows that are not pipeline-processed (`AssetService.pruneDanglingAssets`, one
  `deleteByQuery`); the cascades take their faces and search documents. The stored files, previews and face files stay
  on disk for good, and so do the face counts the assets added and the people they created.
- **Shutdown.** `shutdown()` completes the queue and awaits `watchCompletion()`, which resolves when the queue source
  has handed on its buffer, not when the stream has finished; `merge(Source.never)` keeps the stream from ever
  completing. `Altitude.cleanup` then closes the transaction manager under the assets still in flight.
  `PurgePipelineService.shutdown()` has the same shape and `cleanup` never calls it.
- **Offers.** `addToQueue` logs `Dropped`, `Failure` and `QueueClosed` and returns a successful future, so
  `ImportController` treats the file as queued and its staged file stays until restart. The dropped-asset message has a
  stray `}`. `PurgePipelineService.enqueue` offers one asset at a time and logs at ERROR an asset the queue does not
  take; `requeuePurgePending` queues it again at the next startup.
- **Threads.** Every `mapAsync` returns `Future.successful`, so the work runs on the stream actor's thread, on the
  default dispatcher, which `ImportStatusWsActor` and the SQLite optimize schedule share. Pekko fuses the first island
  of every substream into the actor that runs `groupBy`, so everything through `IndexFlow` is one asset at a time
  across all repositories; the three islands after it are per repository. Throughput per repository is one asset per
  island, bounded by the slowest.
- **Context.** `PipelineUtils.setThreadLocalRequestContext` sets `RequestContext.repository` and `account` on the
  dispatcher thread and nothing clears them.
- **Decoding.** `AssetService.getDimensionsAndDuration` fully decodes an image (`ImageIO.read`) for its width and
  height; face detection and the preview each decode it again.
- **Duplication.** `ImportPipelineService` and `PurgePipelineService` carry the same `runAsQueue` and `shutdown`, and
  run their callbacks on `ExecutionContext.global`.
- **Dev switches.** `dev.sql_explain` is honored only with `ENV=dev` and ignored with a WARN anywhere else; the check is
  written inline where `Altitude` builds its `SqlExplainer`. A failed queue stream stays down in every environment.

## Implementation

Server-side units are test-first (red, green, refactor) with integration tests in `ImportPipelineServiceTests`, on both
engines. Each unit updates the documentation its change touches as it lands (unit 9 lists where).

### 1. One guard for every stage

`PipelineUtils` gets the one place a stage runs:

```scala
/** Runs a stage for one asset in its pipeline context; whatever it throws drops the asset with that cause */
def guarded(stage: String, dataAsset: AssetWithData, ctx: PipelineContext)(
    work: => TDataAssetOrInvalidWithContext): TDataAssetOrInvalidWithContext =
  withContext(ctx) {
    try work
    catch
      case NonFatal(e) =>
        if !isExpectedDrop(e) then logger.error(s"$stage failed for ${dataAsset.asset.fileName}", e)
        (Right(InvalidAsset(dataAsset, e)), ctx)
  }
```

- `withContext(ctx)(work)` binds `RequestContext.repository` and `account` for the work alone
  (`DynamicVariable.withValue`). `setThreadLocalRequestContext` goes; the purge flows use `withContext` too.
- `stage(name, parallelism)(work)` builds a stage's `Flow`: a `mapAsync(parallelism)` with `guarded` around the `Left`
  case and the `Right` case passed through, so no flow repeats either. Every `mapAsync` stage is built with it.
  `CheckMediaTypeFlow`, a `map` over the raw input, calls `guarded` itself. `AssignIdFlow` and `StripBinaryDataFlow`
  only reshape the element and stay plain `map` stages.
- `MarkAsCompleteFlow` works on `Asset`; `guarded` has an overload for it that builds the `InvalidAsset` without a
  staged file.
- A stage keeps its own `catch` only where the cause shown to the user differs: `DuplicateException` in `IndexFlow`,
  and in `FacialRecognitionFlow`, where it becomes `SamePersonDetectedTwiceException`, built without `e.message.get`. The
  `UnsupportedMediaTypeException`, `VideoException` and `NonFatal` clauses fold into the guard, and the extractor call
  of `ExtractMetadataFlow` comes inside it.
- Undecodable files get named causes. `getDimensionsAndDuration` throws `ImageException` (new, beside
  `VideoException`) when `ImageIO` cannot read an image. `previewFrame` throws `VideoException` when no frame decodes.
- Each drop is logged once. `PipelineUtils.isExpectedDrop` is true for `UnsupportedMediaTypeException`,
  `ImageException`, `VideoException`, `DuplicateException` and `SamePersonDetectedTwiceException`. The guard logs any
  other cause at ERROR with the stack trace, naming the stage and the file. `AssetErrorLoggingSink` logs an expected
  cause at INFO in one line, with the repository, and nothing for the rest. `CheckMediaTypeFlow`'s own ERROR line goes,
  and so do the flows' loggers.
- The queue stream gets `ActorAttributes.supervisionStrategy` with a resuming decider that logs at ERROR, as the last
  resort for a failure outside a stage function. It is not the mechanism: a resumed element reaches no sink, so it is
  neither reported nor discarded.
- `ImportStatusWsActor` shows `ImageException` as "Cannot decode image" and `VideoException` as "Cannot decode video".
  Any cause it does not name is "Error importing <name>: <the exception's simple class name>"; no exception message
  reaches the browser.

Tests: a bad asset followed by a good one, through `run`. The bad one comes out `Right` with the expected cause and its
staged file is gone; the good one is imported. The cases:

- Bytes that are not an image, staged under a `jpeg` asset: `ImageException`, in `ExtractMetadataFlow`.
- The repository's `files` directory replaced by a plain file for the test: `StorageException`, in `FileStoreFlow`.
- The video no frame of which decodes (present): its expected cause becomes `VideoException`.

The image pair also goes through `addToQueue`, and the test polls for the good one, as the queued-batch test does.

### 2. A dropped asset leaves nothing behind

- `LibraryService.discardImport(asset: Asset)` undoes what the import of an asset has written. It is a no-op for an
  asset that was never persisted and for one that is pipeline-processed. `CheckDuplicateFlow` drops before
  `AssignIdFlow` gives an id; `IndexFlow` persists. In one transaction it:
  1. Reads the asset's faces with their people (`PersonService.getAssetFacesWithPeople`), and locks those people
     (`FOR UPDATE` on PostgreSQL), so a face another import adds to one of them meanwhile keeps that person.
  2. Gives their face counts back (`PersonService.recycleFacesForAssets`, the count that recycling takes).
  3. Deletes the asset row while `is_pipeline_processed = false`. This cascades to its faces, its search document and
     its metadata parameters.
  4. Deletes each of those people left with no face rows. Only this import can have created such a person: matching
     reads face rows, so a person without any is never matched.

  After the commit, it purges the files: each face's files (`fileStore.purgeFaceById`), and the stored file and the
  preview (`fileStore.purgeAssetById`). A person who stays keeps the files of its cover face, as a purge keeps them; a
  person whose faces are all recycled takes a newly matched face as cover. `DeletePersonFilesFlow` and `discardImport`
  share one helper for this step.
- A new last stage, `DiscardDroppedFlow`, runs after `MarkAsCompleteFlow`. For a `Right`, it discards the staged file
  (`StagingService.discard`) and calls `discardImport`. It logs its own failure at ERROR and swallows it, so a cleanup
  that fails still reports the original cause, and the startup prune gets another go. `StripBinaryDataFlow` only
  strips.
- A failed `completeImport` (unit 1 makes it a drop) is discarded the same way, since the discard runs after it. The
  stats count an asset only once its import completes, so a discard changes none.
- At startup, `LibraryService.pruneDanglingAssets` discards each dangling asset with `discardImport`, in its
  repository's context, logging each at WARN as now. `AssetService.pruneDanglingAssets` goes.

Tests:

- An image of a face, dropped in `FileStoreFlow` (the `files` directory replaced by a plain file) after face
  recognition stored its face. Afterwards there is no asset row, no face row, no face file and no staged file, and the
  person the import created is gone.
- An image of a person an earlier import created, dropped the same way: the person stays, with the face count it had
  before.
- The video no frame of which decodes, dropped in the preview stage: no asset row, no stored file, no preview and no
  staged file.
- The same file imports successfully right after it was dropped.
- A dropped duplicate leaves the original asset untouched.
- An asset left dangling (persisted and its faces stored through the services, never completed) is discarded by
  `pruneDanglingAssets`, files and face counts included.

### 3. One queued-pipeline helper

- `QueuedPipeline[In](name, flow, bufferSize, maxConcurrentOffers)` in `altitude.core.pipeline` owns the queue source,
  the materialized stream with its supervision strategy, the completion log, `offer` and `shutdown`.
  `ImportPipelineService` and `PurgePipelineService` each hold one, and keep their flow, `run`, and their entry points
  (`addToQueue`, `enqueue`).
- Its callbacks run on the actor system's execution context instead of `ExecutionContext.global`, and the stray `}`
  goes.
- Otherwise behavior is unchanged, and the existing suites are this unit's test. Units 4 to 6 change the queues in this
  one place.

### 4. Shutdown that drains

- `merge(Source.never)` goes; the queue source stays open until `complete()`.
- `shutdown()` completes the queue and awaits the stream's own `Future[Done]` for `pipeline.shutdown_timeout` (`30s`
  in `reference.conf`). On
  timeout it logs at WARN how long it waited, and returns. Whatever the timeout cuts off is handled at the next
  startup: the prune discards an import, and `requeuePurgePending` queues a purge again.
- The completion callback logs at INFO for a normal completion and at ERROR for a failure.
- `Altitude.cleanup` shuts down the purge pipeline as well, before the transaction manager.

Tests: assets are offered, then `shutdown()` runs on a service instance of the test's own. Every offered asset is
pipeline-processed when it returns.

### 5. Offers that fail are failures

- `QueuedPipeline.offer` fails with `QueueRefusedException`, naming the queue and the element, and logs it at WARN.
  That covers `Dropped`, `Failure`, `QueueClosed` and a stopped stream (`StreamDetachedException`).
- `ImportPipelineService.addToQueue` discards the staged file of an asset the queue refused, then passes the failure
  on, and `ImportController` lets it answer the request.
- `PurgePipelineService.enqueue` goes on past a refused asset, logging it at ERROR as now.

Tests: an offer to a shut-down import queue fails with `QueueRefusedException`, and its staged file is gone.

### 6. Restart in development

- **The switch.** `dev.restart_pipeline` is `false` in `reference.conf` and commented out in
  `application-dev.conf.example`. `Environment.devSwitch(config, key, environment = CURRENT)` holds the one rule for
  dev-only keys: a key is on only when it is `true` and the environment is `dev`, and a key set in any other
  environment is off, with a WARN. `Altitude` reads `dev.sql_explain` through it. It also reads `dev.restart_pipeline`
  as `isPipelineRestartEnabled`, before the services are wired, and logs at INFO when the restart is on.
- **The stream.** `QueuedPipeline` takes `restartOnFailure`, and both services pass `app.isPipelineRestartEnabled`, so
  the switch covers both queues. One factory builds each start of the stream: a `Source.queue`, pre-materialized and
  stored as the current queue in an `AtomicReference`, run through the pipeline's flow. The factory logs each start at
  INFO.
  - With the switch on, the stream is `RestartSource.onFailuresWithBackoff(RestartSettings(minBackoff = 1.second,
    maxBackoff = 30.seconds, randomFactor = 0.2))` over the factory. Its restarts are logged at ERROR with the
    failure's stack trace.
  - With the switch off, the factory runs once.
  - A completed stream is not restarted; only a failed one is.
- **What a restart loses.** `offer` reads the current queue, which during the backoff is the stopped one, so an offer
  fails as in unit 5. The assets in flight or buffered when the stream failed are gone. An import that `IndexFlow` had
  persisted is discarded by the next startup's prune, and its staged file goes when startup empties staging.
- **Shutdown.** A `KillSwitches.single` follows the restart source. If the current queue has already stopped (the
  backoff), `shutdown()` ends the stream through it instead of waiting for a restart. Otherwise it completes the queue
  and drains as in unit 4.

Tests in `ImportPipelineServiceTests` use a `QueuedPipeline` of the test's own. Its flow throws for one element and
carries `Supervision.stoppingDecider`, so the failure reaches the stream:

- With `restartOnFailure = true`: the failing element is offered, then a good one, retried until the queue takes it.
  The good one comes out of the flow.
- With `restartOnFailure = false`: the same two offers. The good one's offer fails with `QueueRefusedException`, and
  nothing comes out.
- `shutdown()` during the backoff returns well within the drain limit.

A unit test (`EnvironmentTests`) covers `devSwitch`. It is on in `dev` with the key `true`. It is off in `test` and in
`prod`, whatever the key says, and off when the key is `false`.

### 7. Import threads

- `import.parallelism` sets the size of the import thread pool. `reference.conf` carries it commented out; left unset,
  it is half the cores, never below 2, the way `TransactionManager` sizes the PostgreSQL pool when its key is unset.
  `Altitude` creates the actor system with the app config plus a
  Pekko dispatcher, `altitude.import-dispatcher`: a `thread-pool-executor` with a fixed pool of `import.parallelism`
  threads.
- `guardedAsync`, the guard's asynchronous form, runs the guarded stage in a `Future` on that dispatcher. `stage(...)`
  uses it, and so do `MarkAsCompleteFlow` and `DiscardDroppedFlow`, so stream actors on the default dispatcher only
  route.
- The `.async` boundaries go: with real futures, a stage no longer occupies its actor, so stages overlap without them,
  and the first island no longer serializes repositories.
- `Altitude.parallelism` keeps sizing the queues. Its comment and `ImportPipelineService`'s docstring say what bounds
  the concurrent work now: the import dispatcher.

### 8. Parallel decode, sequential writes

Per repository substream, in order:

| Stage                         | Operator                       | Touches                    |
|-------------------------------|--------------------------------|----------------------------|
| check media type, assign id   | `map`                          | nothing                    |
| check duplicate               | `mapAsync(1)`                  | database read              |
| extract metadata, dimensions  | `mapAsync(import.parallelism)` | file                       |
| index                         | `mapAsync(1)`                  | database write             |
| detect faces                  | `mapAsync(import.parallelism)` | file                       |
| recognize and store faces     | `mapAsync(1)`                  | database write, face files |
| file store                    | `mapAsync(1)`                  | rename                     |
| preview                       | `mapAsync(import.parallelism)` | stored file, preview file  |
| strip                         | `map`                          | nothing                    |
| mark complete                 | `mapAsync(1)`                  | database write             |
| discard dropped               | `mapAsync(1)`                  | database write, files      |

- `mapAsync` keeps upstream order, so faces are matched and people created in upload order, one asset at a time per
  repository, as now. SQLite's single write connection sees the same one-writer-per-stage pattern, and no parallel
  stage writes to the database.
- A repository's import holds at most five connections at once, one per database stage. The PostgreSQL pool default
  (`TransactionManager`) goes from a connection per core plus four to a connection per core plus five, with its
  comments in `reference.conf` and `altitude/AGENTS.md`.
- `FaceRecognitionService.processAsset` splits in two:
  - `detect(dataAsset): DetectedFaces` (an image's `extractFaces`; a Video's sampled frames, `cluster` and
    `mergeClusters`), with no database access.
  - `recognizeAndStore(asset, detected)`, which holds today's `withFaceFiles` bodies.

  `FacialRecognitionFlow` becomes `DetectFacesFlow` and `RecognizeFacesFlow`. The detections (`FaceImages` are byte
  arrays) ride on the pipeline element between them: `AssetWithData` gains an optional `detectedFaces`, which
  `StripBinaryDataFlow` drops.
- `getDimensionsAndDuration` reads an image's size from its header (`ImageIO.getImageReaders`, `reader.getWidth(0)` /
  `getHeight(0)`). It still throws `ImageException` when no reader takes the file.
- The comment on `FaceDetectionService`'s thread-locals names the import dispatcher's threads.

Tests:

- The existing suite passes unchanged in outcome.
- A batch larger than `import.parallelism`, with the same person in every image, yields one person, so order and
  one-at-a-time matching hold.
- An image with a valid header imports with the right dimensions on both engines.

### 9. Documentation

- `altitude/AGENTS.md`:
  - **Import Pipeline**: the stages, the guard and its logging, the discard stage, `QueuedPipeline`, the import
    dispatcher and `import.parallelism`, shutdown, and the dev restart.
  - **Library stats and purging**: the startup prune now discards dangling imports.
  - The PostgreSQL pool note.
  - The dev settings line (`dev.restart_pipeline`).
- `docs/faces.md`: detection and recognition as two stages, and a dropped import's faces and people undone.
- `docs/test-coverage.md`: the new cases.
- `reference.conf`: comments on `import.parallelism`, `pipeline.shutdown_timeout`, `dev.restart_pipeline` and the pool
  size.

## Files

- `altitude/src/altitude/core/pipeline/PipelineUtils.scala`, `PipelineTypes.scala`, new `QueuedPipeline.scala`
- `altitude/src/altitude/core/pipeline/flows/*.scala`: new `DiscardDroppedFlow`, `DetectFacesFlow` and
  `RecognizeFacesFlow`; `FacialRecognitionFlow` removed; the purge flows use `withContext`.
- `altitude/src/altitude/core/pipeline/sinks/AssetErrorLoggingSink.scala`
- `altitude/src/altitude/core/service/`: `ImportPipelineService.scala`, `PurgePipelineService.scala`,
  `LibraryService.scala`, `AssetService.scala`, `PersonService.scala`, `FaceRecognitionService.scala`,
  `FaceDetectionService.scala`, `VideoService.scala`
- `altitude/src/altitude/core/dao/PersonDao.scala`, `dao/jdbc/PersonDao.scala`: locking an asset's people, and deleting
  the ones left with no face rows.
- `altitude/src/altitude/core/models/AssetWithData.scala`
- `altitude/src/altitude/core/`: `Altitude.scala`, `Environment.scala`, `Const.scala`, `Exceptions.scala`
  (`ImageException`, `QueueRefusedException`), `actors/ImportStatusWsActor.scala`, `routes/web/ImportController.scala`,
  `transactions/TransactionManager.scala`
- `altitude/resources/reference.conf`, `application-dev.conf.example`
- `altitude/test/src/altitude/core/integration/ImportPipelineServiceTests.scala`, new
  `altitude/test/src/altitude/core/unit/EnvironmentTests.scala`
- `altitude/AGENTS.md`, `docs/faces.md`, `docs/test-coverage.md`

## Verification

- `make test` (SQLite) and `make test-psql` are green.
- With `PipelineConstants.DEBUG = true`, an upload of a few dozen images shows stage work on `import-dispatcher`
  threads only, and several assets in the metadata, face-detection and preview stages at once.
- Manual: upload a corrupt image between two good ones on `localhost:8080`. The ticker says "Cannot decode image", the
  log has one INFO line for it, and both good ones import. The corrupt file uploaded again fails the same way, not as
  a duplicate.
- Manual: stop the server mid-import. The log shows the drain, and the next startup discards nothing, or only what the
  timeout cut off, files included.
- Manual: with `dev.restart_pipeline=true`, startup logs at INFO that the restart is on under `ENV=dev`, and logs the
  WARN with the restart off under `ENV=prod`.
- Timing of a 200-image upload before and after, to confirm the gain and that the default `import.parallelism` holds
  up.
