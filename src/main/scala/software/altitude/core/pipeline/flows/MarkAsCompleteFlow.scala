package software.altitude.core.pipeline.flows

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow

import scala.concurrent.Future

import software.altitude.core.Altitude
import software.altitude.core.pipeline.PipelineConstants.parallelism
import software.altitude.core.pipeline.PipelineTypes.TAssetOrInvalidWithContext
import software.altitude.core.pipeline.PipelineUtils.debugInfo
import software.altitude.core.pipeline.PipelineUtils.setThreadLocalRequestContext

object MarkAsCompleteFlow {
  def apply(app: Altitude): Flow[TAssetOrInvalidWithContext, TAssetOrInvalidWithContext, NotUsed] =
    Flow[TAssetOrInvalidWithContext].mapAsync(parallelism) {
      case (Left(asset), ctx) =>
        setThreadLocalRequestContext(ctx)

        app.txManager.withTransaction {
          debugInfo(s"\tMarking asset as pipeline-complete ${asset.fileName}")
          val updatedAsset = app.service.asset.markAsCompleted(asset)

          Future.successful((Left(updatedAsset), ctx))
        }
      case (Right(invalid), ctx) =>
        Future.successful((Right(invalid), ctx))
    }

}
