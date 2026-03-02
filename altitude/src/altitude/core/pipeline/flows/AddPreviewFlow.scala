package altitude.core.pipeline.flows

import altitude.core.Altitude
import altitude.core.pipeline.PipelineTypes.TDataAssetOrInvalidWithContext
import altitude.core.pipeline.PipelineUtils.debugInfo
import altitude.core.pipeline.PipelineUtils.setThreadLocalRequestContext
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow

import scala.concurrent.Future

object AddPreviewFlow {
  def apply(app: Altitude): Flow[TDataAssetOrInvalidWithContext, TDataAssetOrInvalidWithContext, NotUsed] =
    Flow[TDataAssetOrInvalidWithContext].mapAsync(app.parallelism) {
      case (Left(dataAsset), ctx) =>
        setThreadLocalRequestContext(ctx)
        debugInfo(s"\tGenerating preview ${dataAsset.asset.fileName}")
        app.service.asset.addPreview(dataAsset)
        Future.successful((Left(dataAsset), ctx))
      case (Right(invalid), ctx) => Future.successful((Right(invalid), ctx))
    }
}
