# Import pipeline fault isolation

## Goals

- No single asset can stop the import queue. Whatever a stage throws for one asset drops that asset, reports it, and
  the queue goes on with the next.
- One place expresses the rule, so a new stage cannot forget it.
- The user sees why an import was dropped, in the status ticker, and the log carries the stack trace.
- In development, a queue that fails all the same can restart itself, behind a switch that is off by default.

Out of scope: retrying a dropped asset, and cleaning up an asset persisted by an earlier stage when a later one fails
(startup already purges assets that never reached `MarkAsCompleteFlow`).

## Current behavior

`ImportPipelineService.runAsQueue` runs one persistent Pekko stream per app instance for the uploads
(`ImportController` offers each staged file to it). The stream has no supervision strategy. A `mapAsync` stage whose
function throws fails the stream; `res.onComplete` logs the failure and nothing restarts it, so every later offer to the
queue is never processed until the app restarts.

Each stage is an object in `pipeline/flows` with the same shape: `mapAsync(app.parallelism)` over
`TDataAssetOrInvalidWithContext`, a `Left(dataAsset)` case that does the work and a `Right(invalid)` case that passes the
dropped asset through. What each `Left` case rescues today:

| Flow                    | Rescues                                              |
|-------------------------|------------------------------------------------------|
| `CheckMediaTypeFlow`    | `UnsupportedMediaTypeException`                      |
| `CheckDuplicateFlow`    | none; produces `DuplicateException` itself           |
| `AssignIdFlow`          | none (`map`, cannot throw in practice)               |
| `ExtractMetadataFlow`   | `VideoException`                                     |
| `IndexFlow`             | `DuplicateException`                                 |
| `FacialRecognitionFlow` | `DuplicateException`, `NonFatal`                     |
| `FileStoreFlow`         | none                                                 |
| `AddPreviewFlow`        | `NonFatal`                                           |
| `StripBinaryDataFlow`   | none (`map`)                                         |
| `MarkAsCompleteFlow`    | none                                                 |

So an image `ImageIO` cannot read (it returns `null`, and `getDimensionsAndDuration` throws a
`NullPointerException`), a metadata extractor failure, a full disk in `FileStoreFlow`, or a database error in
`IndexFlow` or `MarkAsCompleteFlow` still fails the stream.

A dropped asset is a `Right(InvalidAsset(payload, cause, stagedFile))`. `StripBinaryDataFlow` discards its staged file,
`AssetErrorLoggingSink` logs `cause.toString`, and `WsAssetProcessedNotificationSink` has `ImportStatusWsActor` show a
ticker line chosen by the cause's type, "Unknown error importing" for anything it does not name.

## Design

### One guard for every stage

`PipelineUtils` gets a helper that wraps the `Left` case of a stage:

```scala
/** Runs a stage for one asset; whatever it throws drops the asset with that cause, so the queue goes on */
def guarded(stage: String, dataAsset: AssetWithData, ctx: PipelineContext)(
    work: => TDataAssetOrInvalidWithContext): Future[TDataAssetOrInvalidWithContext] =
  try Future.successful(work)
  catch
    case NonFatal(e) =>
      logger.error(s"$stage failed for ${dataAsset.asset.fileName}", e)
      Future.successful((Right(InvalidAsset(dataAsset, e)), ctx))
```

Every `mapAsync` stage's `Left` case becomes `guarded("Extracting metadata", dataAsset, ctx) { ... }` around the work
it does now, and its own `try`/`catch` keeps only the cases that mean something more specific than "failed":

- `DuplicateException` in `IndexFlow` and `FacialRecognitionFlow`, where the cause shown to the user differs from the
  generic one.
- `UnsupportedMediaTypeException` and `VideoException`, which are the guard's behavior already; their `catch` clauses go.

`FileStoreFlow` and `MarkAsCompleteFlow` gain the guard and nothing else. `MarkAsCompleteFlow` works on `Asset`, not
`AssetWithData`, so the helper takes the pieces it needs (`fileName`, and an `InvalidAsset` with no staged file) or has
an overload for that stage.

The `NonFatal` clauses of `FacialRecognitionFlow` and `AddPreviewFlow` fold into the guard.

`StripBinaryDataFlow` and `AssignIdFlow` are plain `map` stages that only reshape the element; they stay as they are.

### Why not a supervision strategy

`ActorAttributes.supervisionStrategy(Supervision.resumingDecider)` on the stream would keep it alive, but a resumed
`mapAsync` drops the element outright: no `InvalidAsset` reaches the sinks, the user sees nothing, the staged file is
never discarded, and an asset persisted by `IndexFlow` is left without a trace of why it stopped. The per-stage guard
keeps the existing drop path, which already does all of that. The strategy is still worth adding as a last line of
defense against a stage that throws outside its function (a stream-level failure in `groupBy` or a sink), logged as
such; it is not a substitute for the guard.

### Restarting the queue (development only)

The guard and the supervision strategy keep the queue's stream from failing. For a failure that gets past both, the
queue can restart itself, as a development aid: a developer working on a stage does not have to restart the server
after breaking the stream. Production keeps the current behavior, where a failed stream is logged and stays down, so
that a stream failing over and over is noticed instead of hidden by restarts.

**The switch.** `dev.restart_pipeline`, `false` in `reference.conf`, set in `application-dev.conf`. It is read the way
`dev.sql_explain` is: honored only with `ENV=dev`, and ignored with a WARN in any other environment. `Altitude` has
one private helper for both keys (the key in, whether it is on in this environment out, the WARN inside), and exposes
`isPipelineRestartEnabled`; it logs at INFO when the restart is on.

**The stream.** With the switch on, `runAsQueue` wraps the whole stream in
`RestartSource.onFailuresWithBackoff(RestartSettings(minBackoff = 1.second, maxBackoff = 30.seconds, randomFactor = 0.2))`.
The factory it is given builds what `runAsQueue` builds today: a `Source.queue`, pre-materialized, merged with
`Source.never` and run through the pipeline's flow. A queue source belongs to one materialization, so each start has a
queue of its own; the service holds the current one in an `AtomicReference`, which the factory sets and `addToQueue`
and `shutdown` read. Each start is logged at INFO ("Starting the import queue pipeline"), and a failure at ERROR with
its stack trace before the backoff.

With the switch off, `runAsQueue` runs the same factory once, with no wrapper: one code path builds the stream in both
cases.

**What a restart loses.** The assets in flight and those buffered in the failed queue are gone from the stream, and an
offer made during the backoff reaches a closed queue and is dropped, logged by `addToQueue` as now. An asset that
`IndexFlow` had persisted stays `is_pipeline_processed = false` and is pruned at the next startup, like any import that
did not finish; its staged file is left in the staging directory.

**Shutdown.** `shutdown()` completes the current queue. A completed stream is not restarted: only a failure is.

### What the user sees

`ImportStatusWsActor` names a dropped asset's cause for `DuplicateException`, `UnsupportedMediaTypeException` and
`StorageException` and says "Unknown error" otherwise. It gains:

- `VideoException` and the `RuntimeException` from `previewFrame`: "Cannot decode video".
- Anything else: "Error importing <name>: <exception message>", so a `NullPointerException` from an unreadable image or
  an `SQLException` at least names itself.

`AssetErrorLoggingSink` keeps logging the cause; the guard logs the stack trace at the stage where it happened, so the
sink does not need to.

### Tests

`ImportPipelineServiceTests` runs the pipeline over one bad asset followed by a good one and checks the bad one comes
out `Right` with the expected cause, its staged file is gone, and the good one is imported, for:

- a video no frame of which decodes (present).
- an image whose bytes are not an image, with a `jpeg` asset type set explicitly on `makeAssetWithData`, which fails in
  `ExtractMetadataFlow`.
- a store failure in `FileStoreFlow`: the repository's `files` directory replaced by a plain file for the test, so the
  rename fails.

A queue-level test in `ImportPipelineServiceTests` offers a bad then a good asset through `addToQueue` and waits, with
a probe sink or the ws actor's message, for the good one to complete: the queue outlives the bad asset.

The restart is tested in `ImportPipelineServiceTests` with a service of its own, since the test environment ignores
the switch: `ImportPipelineService` takes `restartOnFailure: Boolean = app.isPipelineRestartEnabled` and builds the
queue's flow in a `protected def queueFlow`, which the test's subclass overrides to put a stage in front that throws
for one file name and carries `Supervision.stoppingDecider`, so the failure reaches the stream.

- With `restartOnFailure = true`: the failing asset is offered, then, after the backoff, a good one; the good one is
  imported.
- With `restartOnFailure = false`: the same two offers; the good one is never imported and its offer reports a closed
  queue.

A unit test covers the switch itself: on with `ENV=dev`, ignored in the test environment whatever the key says, off
when the key is `false`.

## Files

- `altitude/src/altitude/core/pipeline/PipelineUtils.scala`: the guard.
- `altitude/src/altitude/core/pipeline/flows/*.scala`: each `mapAsync` stage wraps its work in the guard.
- `altitude/src/altitude/core/service/ImportPipelineService.scala`: the supervision strategy attribute on the queue
  stream, with a log line naming it as the last resort; the restart wrapper, `queueFlow`, the current queue.
- `altitude/src/altitude/core/Altitude.scala`, `Const.scala`: `dev.restart_pipeline` and the helper for the dev-only
  switches.
- `altitude/resources/reference.conf`, `application-dev.conf.example`: the key, `false` and commented out.
- `altitude/AGENTS.md`: the import pipeline paragraph (the guard, the restart switch) and the dev settings line.
- `altitude/src/altitude/core/actors/ImportStatusWsActor.scala`: the ticker messages.
- `altitude/test/src/altitude/core/integration/ImportPipelineServiceTests.scala`: the tests above.
