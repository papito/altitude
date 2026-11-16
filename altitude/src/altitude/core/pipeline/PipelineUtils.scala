package altitude.core.pipeline

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

import altitude.core.DuplicateException
import altitude.core.ImageException
import altitude.core.ImportDestinationException
import altitude.core.ImporterUnavailableException
import altitude.core.QueueRefusedException
import altitude.core.RequestContext
import altitude.core.SamePersonDetectedTwiceException
import altitude.core.StorageException
import altitude.core.UnsupportedMediaTypeException
import altitude.core.VideoException
import altitude.core.models.AssetWithData
import altitude.core.models.Permissions
import altitude.core.pipeline.PipelineConstants.DEBUG
import altitude.core.pipeline.PipelineTypes.InvalidAsset
import altitude.core.pipeline.PipelineTypes.PipelineContext
import altitude.core.pipeline.PipelineTypes.TDataAssetOrInvalid
import altitude.core.pipeline.PipelineTypes.TDataAssetOrInvalidWithContext

object PipelineUtils:
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  /**
   * Runs the work under the context's repository, account and Permissions, scoped to the work alone so a pooled thread never
   * works under the request that last ran on it. The log context (the MDC's request ID and user) is the request thread's and is
   * not bound here: a line the work logs carries neither.
   */
  def withContext[A](ctx: PipelineContext)(work: => A): A =
    RequestContext.repository.withValue(Some(ctx.repository)) {
      RequestContext.account.withValue(ctx.account) {
        RequestContext.permissions.withValue(if ctx.account.isEmpty then Permissions.none else ctx.permissions)(work)
      }
    }

  /** A drop cause the user is told and the log records in one line, without a stack trace */
  def isExpectedDrop(cause: Throwable): Boolean = cause match
    case _: UnsupportedMediaTypeException | _: ImageException | _: VideoException | _: DuplicateException |
        _: SamePersonDetectedTwiceException | _: ImporterUnavailableException | _: ImportDestinationException =>
      true
    case _ => false

  /** Why a file was dropped, fit to show and keep: an unnamed cause by its class alone, never its message (paths, SQL) */
  def dropReason(cause: Option[Throwable]): String = cause match
    case Some(_: DuplicateException) => "Duplicate asset"
    case Some(_: UnsupportedMediaTypeException) => "Unsupported media type"
    case Some(_: ImageException) => "Cannot decode image"
    case Some(_: VideoException) => "Cannot decode video"
    case Some(_: StorageException) => "Storage error"
    case Some(_: QueueRefusedException) => "Import queue unavailable"
    // A fixed text (Const.Msg.Err.IMPORTER_GONE or IMPORTER_DEACTIVATED)
    case Some(unavailable: ImporterUnavailableException) => unavailable.getMessage
    // A fixed text (Const.Msg.Err.DESTINATION_IN_TRASH), or DESTINATION_FOLDER_NAME around the folder rule's, which names a
    // length at most
    case Some(destination: ImportDestinationException) => destination.getMessage
    case other => other.fold("Unknown error")(_.getClass.getSimpleName)

  /** Runs a stage for one asset in its pipeline context; whatever it throws drops the asset with that cause */
  def guarded(stage: String, dataAsset: AssetWithData, ctx: PipelineContext)(
      work: => TDataAssetOrInvalid): TDataAssetOrInvalidWithContext =
    withContext(ctx) {
      val result =
        try work
        catch
          case NonFatal(cause) =>
            // An expected cause is logged once, where the asset leaves the pipeline (AssetErrorLoggingSink)
            if !isExpectedDrop(cause) then logger.error(s"$stage failed for ${dataAsset.asset.fileName}", cause)
            Right(InvalidAsset(dataAsset, cause))
      (result, ctx)
    }

  /** [[guarded]] on the import dispatcher, off the stream's actor, which only routes the elements */
  def guardedAsync(stage: String, dataAsset: AssetWithData, ctx: PipelineContext)(work: => TDataAssetOrInvalid)(using
      dispatcher: ExecutionContext): Future[TDataAssetOrInvalidWithContext] =
    Future(guarded(stage, dataAsset, ctx)(work))

  /** Works on the assets still in the pipeline, `parallelism` at once, passing dropped ones on; the input order is kept */
  def stage(name: String, parallelism: Int)(work: AssetWithData => TDataAssetOrInvalid)(using
      dispatcher: ExecutionContext): Flow[TDataAssetOrInvalidWithContext, TDataAssetOrInvalidWithContext, NotUsed] =
    Flow[TDataAssetOrInvalidWithContext].mapAsync(parallelism) {
      case (Left(dataAsset), ctx) => guardedAsync(name, dataAsset, ctx)(work(dataAsset))
      case dropped => Future.successful(dropped)
    }

  def debugInfo(msg: String): Unit =
    if DEBUG then println(s"(${Thread.currentThread().getName}) $msg")
