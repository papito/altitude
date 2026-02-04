package altitude.core.pipeline.flows

import altitude.core.Altitude
import altitude.core.pipeline.PipelineConstants.parallelism
import altitude.core.pipeline.PipelineTypes.TDataAssetOrInvalidWithContext
import altitude.core.pipeline.PipelineUtils.debugInfo
import altitude.core.pipeline.PipelineUtils.setThreadLocalRequestContext
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow

import scala.concurrent.Future

object FileStoreFlow {
  def apply(app: Altitude): Flow[TDataAssetOrInvalidWithContext, TDataAssetOrInvalidWithContext, NotUsed] =
    Flow[TDataAssetOrInvalidWithContext].mapAsync(parallelism) {
      case (Left(dataAsset), ctx) =>
        setThreadLocalRequestContext(ctx)

        debugInfo(s"\tStoring asset ${dataAsset.asset.fileName}")
        app.service.fileStore.addAsset(dataAsset)
        Future.successful((Left(dataAsset), ctx))
      case (Right(invalid), ctx) => Future.successful((Right(invalid), ctx))
    }
}
