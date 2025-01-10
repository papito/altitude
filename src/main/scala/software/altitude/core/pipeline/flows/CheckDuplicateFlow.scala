package software.altitude.core.pipeline.flows

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow

import scala.concurrent.Future

import software.altitude.core.Altitude
import software.altitude.core.DuplicateException
import software.altitude.core.models.Asset
import software.altitude.core.pipeline.PipelineConstants.parallelism
import software.altitude.core.pipeline.PipelineTypes.InvalidAsset
import software.altitude.core.pipeline.PipelineTypes.TDataAssetOrInvalidWithContext
import software.altitude.core.pipeline.PipelineUtils.debugInfo
import software.altitude.core.pipeline.PipelineUtils.setThreadLocalRequestContext

object CheckDuplicateFlow {
  def apply(app: Altitude): Flow[TDataAssetOrInvalidWithContext, TDataAssetOrInvalidWithContext, NotUsed] =
    Flow[TDataAssetOrInvalidWithContext].mapAsync(parallelism) {
      case (Left(dataAsset), ctx) =>
        setThreadLocalRequestContext(ctx)

        debugInfo(s"\tChecking for duplicate for ${dataAsset.asset.fileName}")

        val existing: Option[Asset] = app.service.library.getByChecksum(dataAsset.asset.checksum)

        if (existing.nonEmpty) {
          Future.successful(Right(InvalidAsset(dataAsset.asset, Some(new DuplicateException))), ctx)
        } else {
          Future.successful((Left(dataAsset), ctx))
        }

      case (Right(invalid), ctx) =>
        Future.successful((Right(invalid), ctx))
    }

}
