# Import pipeline fault isolation

## Goals

- No single asset can stop the import queue. Whatever a stage throws for one asset drops that asset, reports it, and
  the queue goes on with the next.
- One place expresses the rule, so a new stage cannot forget it.
- The user sees why an import was dropped, in the status ticker, and the log carries the stack trace.

Out of scope: retrying a dropped asset, and cleaning up an asset persisted by an earlier stage when a later one fails
(startup already purges assets that never reached `MarkAsCompleteFlow`).

## Current behavior

`ImportPipelineService.runAsQueue` runs one persistent Pekko stream per app instance for the uploads
(`ImportController` offers each staged file to it). The stream has no supervision strategy. A `mapAsync` stage whose
function throws fails the stream; `res.onComplete` logs the failure and nothing restarts it, so every later offer to the
queue buffers and is never processed until the app restarts.

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
| `IndexAndFaceRecFlow`   | `DuplicateException`, `NonFatal`                     |
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

- `DuplicateException` in `IndexFlow`, `IndexAndFaceRecFlow` and `FacialRecognitionFlow`, where the cause shown to the
  user differs from the generic one.
- `UnsupportedMediaTypeException` and `VideoException`, which are the guard's behavior already; their `catch` clauses go.

`FileStoreFlow` and `MarkAsCompleteFlow` gain the guard and nothing else. `MarkAsCompleteFlow` works on `Asset`, not
`AssetWithData`, so the helper takes the pieces it needs (`fileName`, and an `InvalidAsset` with no staged file) or has
an overload for that stage.

The `NonFatal` clauses added to `FacialRecognitionFlow`, `AddPreviewFlow` and `IndexAndFaceRecFlow` fold into the
guard.

`StripBinaryDataFlow` and `AssignIdFlow` are plain `map` stages that only reshape the element; they stay as they are.

### Why not a supervision strategy

`ActorAttributes.supervisionStrategy(Supervision.resumingDecider)` on the stream would keep it alive, but a resumed
`mapAsync` drops the element outright: no `InvalidAsset` reaches the sinks, the user sees nothing, the staged file is
never discarded, and an asset persisted by `IndexFlow` is left without a trace of why it stopped. The per-stage guard
keeps the existing drop path, which already does all of that. The strategy is still worth adding as a last line of
defense against a stage that throws outside its function (a stream-level failure in `groupBy` or a sink), logged as
such; it is not a substitute for the guard.

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

## Files

- `altitude/src/altitude/core/pipeline/PipelineUtils.scala`: the guard.
- `altitude/src/altitude/core/pipeline/flows/*.scala`: each `mapAsync` stage wraps its work in the guard.
- `altitude/src/altitude/core/service/ImportPipelineService.scala`: the supervision strategy attribute on the queue
  stream, with a log line naming it as the last resort.
- `altitude/src/altitude/core/actors/ImportStatusWsActor.scala`: the ticker messages.
- `altitude/test/src/altitude/core/integration/ImportPipelineServiceTests.scala`: the tests above.
