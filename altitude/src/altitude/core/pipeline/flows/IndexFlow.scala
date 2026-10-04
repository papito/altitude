package altitude.core.pipeline.flows

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow

import scala.concurrent.Future

import altitude.core.Altitude
import altitude.core.DuplicateException
import altitude.core.pipeline.PipelineTypes.InvalidAsset
import altitude.core.pipeline.PipelineTypes.TDataAssetOrInvalidWithContext
import altitude.core.pipeline.PipelineUtils.debugInfo
import altitude.core.pipeline.PipelineUtils.setThreadLocalRequestContext

object IndexFlow:
  def apply(app: Altitude): Flow[TDataAssetOrInvalidWithContext, TDataAssetOrInvalidWithContext, NotUsed] =
    Flow[TDataAssetOrInvalidWithContext].mapAsync(app.parallelism) {
      case (Left(dataAsset), ctx) =>
        setThreadLocalRequestContext(ctx)

        try {
          debugInfo(s"\tPersisting and indexing asset ${dataAsset.asset.fileName}")
          val persisted = app.service.library.persistAndIndex(dataAsset.asset)
          Future.successful((Left(dataAsset.copy(asset = persisted)), ctx))
        } catch {
          case e: DuplicateException =>
            Future.successful(Right(InvalidAsset(dataAsset, e)), ctx)
        }
      case (Right(invalid), ctx) =>
        Future.successful((Right(invalid), ctx))
    }
