package altitude.core.pipeline

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.util.control.NonFatal

import altitude.core.DuplicateException
import altitude.core.ImageException
import altitude.core.RequestContext
import altitude.core.SamePersonDetectedTwiceException
import altitude.core.UnsupportedMediaTypeException
import altitude.core.VideoException
import altitude.core.models.Asset
import altitude.core.models.AssetWithData
import altitude.core.pipeline.PipelineConstants.DEBUG
import altitude.core.pipeline.PipelineTypes.InvalidAsset
import altitude.core.pipeline.PipelineTypes.PipelineContext
import altitude.core.pipeline.PipelineTypes.TAssetOrInvalid
import altitude.core.pipeline.PipelineTypes.TAssetOrInvalidWithContext
import altitude.core.pipeline.PipelineTypes.TDataAssetOrInvalid
import altitude.core.pipeline.PipelineTypes.TDataAssetOrInvalidWithContext

object PipelineUtils:
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  /** Runs the work with the pipeline context's repository and account as the request context, for the work alone */
  def withContext[A](ctx: PipelineContext)(work: => A): A =
    RequestContext.repository.withValue(Some(ctx.repository)) {
      RequestContext.account.withValue(Some(ctx.account))(work)
    }

  /** A cause a file can have for being dropped, which the user is told and the log records in one line, without a stack trace */
  def isExpectedDrop(cause: Throwable): Boolean = cause match
    case _: UnsupportedMediaTypeException | _: ImageException | _: VideoException | _: DuplicateException |
        _: SamePersonDetectedTwiceException =>
      true
    case _ => false

  /** Runs a stage for one asset in its pipeline context; whatever it throws drops the asset with that cause */
  def guarded(stage: String, dataAsset: AssetWithData, ctx: PipelineContext)(
      work: => TDataAssetOrInvalid): TDataAssetOrInvalidWithContext =
    guard(stage, dataAsset.asset.fileName, ctx, InvalidAsset(dataAsset, _))(work)

  /** [[guarded]] for a stage past the one where the file leaves the pipeline: a dropped asset has no staged file left */
  def guarded(stage: String, asset: Asset, ctx: PipelineContext)(work: => TAssetOrInvalid): TAssetOrInvalidWithContext =
    guard(stage, asset.fileName, ctx, cause => InvalidAsset(asset, Some(cause)))(work)

  private def guard[A](stage: String, fileName: String, ctx: PipelineContext, drop: Throwable => InvalidAsset)(
      work: => Either[A, InvalidAsset]): (Either[A, InvalidAsset], PipelineContext) =
    withContext(ctx) {
      val result =
        try work
        catch
          case NonFatal(cause) =>
            // An expected cause is logged once, where the asset leaves the pipeline (AssetErrorLoggingSink)
            if !isExpectedDrop(cause) then logger.error(s"$stage failed for $fileName", cause)
            Right(drop(cause))
      (result, ctx)
    }

  /** A stage over the assets still in the pipeline: each is worked on under [[guarded]], and a dropped asset is passed on */
  def stage(name: String, parallelism: Int)(
      work: AssetWithData => TDataAssetOrInvalid): Flow[TDataAssetOrInvalidWithContext, TDataAssetOrInvalidWithContext, NotUsed] =
    Flow[TDataAssetOrInvalidWithContext].mapAsync(parallelism) {
      case (Left(dataAsset), ctx) => Future.successful(guarded(name, dataAsset, ctx)(work(dataAsset)))
      case dropped => Future.successful(dropped)
    }

  def debugInfo(msg: String): Unit =
    if DEBUG then println(s"(${Thread.currentThread().getName}) $msg")
