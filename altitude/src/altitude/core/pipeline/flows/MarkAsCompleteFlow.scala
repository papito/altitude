package altitude.core.pipeline.flows

import altitude.core.Altitude
import altitude.core.pipeline.PipelineTypes.TAssetOrInvalidWithContext
import altitude.core.pipeline.PipelineUtils.debugInfo
import altitude.core.pipeline.PipelineUtils.setThreadLocalRequestContext
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow

import scala.concurrent.Future

object MarkAsCompleteFlow {
  def apply(app: Altitude): Flow[TAssetOrInvalidWithContext, TAssetOrInvalidWithContext, NotUsed] =
    Flow[TAssetOrInvalidWithContext].mapAsync(app.parallelism) {
      case (Left(asset), ctx) =>
        setThreadLocalRequestContext(ctx)

        app.txManager.withTransaction {
          debugInfo(s"\tMarking asset as pipeline-complete ${asset.fileName}")
          val updatedAsset = app.service.asset.markAsCompleted(asset)

          // Only add the stats if the asset is at the end of the pipeline
          // (Incomplete assets are purged at startup)
          app.service.stats.addAsset(asset)

          Future.successful((Left(updatedAsset), ctx))
        }
      case (Right(invalid), ctx) =>
        Future.successful((Right(invalid), ctx))
    }

}
