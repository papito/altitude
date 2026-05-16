package altitude.core.pipeline.flows

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow

import scala.concurrent.Future

import altitude.core.Altitude
import altitude.core.DuplicateException
import altitude.core.models.Asset
import altitude.core.pipeline.PipelineTypes.InvalidAsset
import altitude.core.pipeline.PipelineTypes.TDataAssetOrInvalidWithContext
import altitude.core.pipeline.PipelineUtils.debugInfo
import altitude.core.pipeline.PipelineUtils.setThreadLocalRequestContext

object CheckDuplicateFlow:
  def apply(app: Altitude): Flow[TDataAssetOrInvalidWithContext, TDataAssetOrInvalidWithContext, NotUsed] =
    Flow[TDataAssetOrInvalidWithContext].mapAsync(app.parallelism) {
      case (Left(dataAsset), ctx) =>
        setThreadLocalRequestContext(ctx)

        debugInfo(s"\tChecking for duplicate for ${dataAsset.asset.fileName}")

        val existing: Option[Asset] = app.service.asset.getByChecksum(dataAsset.asset.checksum)

        if existing.isDefined then Future.successful(Right(InvalidAsset(dataAsset.asset, Some(DuplicateException()))), ctx)
        else Future.successful((Left(dataAsset), ctx))

      case (Right(invalid), ctx) =>
        Future.successful((Right(invalid), ctx))
    }
