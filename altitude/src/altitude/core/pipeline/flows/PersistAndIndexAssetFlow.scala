package altitude.core.pipeline.flows

import altitude.core.Altitude
import altitude.core.DuplicateException
import altitude.core.pipeline.PipelineConstants.parallelism
import altitude.core.pipeline.PipelineTypes.InvalidAsset
import altitude.core.pipeline.PipelineTypes.TDataAssetOrInvalidWithContext
import altitude.core.pipeline.PipelineUtils.debugInfo
import altitude.core.pipeline.PipelineUtils.setThreadLocalRequestContext
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow

import scala.concurrent.Future

object PersistAndIndexAssetFlow {
  def apply(app: Altitude): Flow[TDataAssetOrInvalidWithContext, TDataAssetOrInvalidWithContext, NotUsed] =
    Flow[TDataAssetOrInvalidWithContext].mapAsync(parallelism) {
      case (Left(dataAsset), ctx) =>
        setThreadLocalRequestContext(ctx)

        app.txManager.withFaceVector {
          try {
            debugInfo(s"\tPersisting asset ${dataAsset.asset.fileName}")
            app.service.asset.add(dataAsset.asset)
            debugInfo(s"\tIndexing asset ${dataAsset.asset.fileName}")
            app.service.search.indexAsset(dataAsset.asset)
            app.service.faceRecognition.processAsset(dataAsset)

            Future.successful((Left(dataAsset), ctx))
          } catch {
            case e: DuplicateException =>
              Future.successful(Right(InvalidAsset(dataAsset.asset, Some(e))), ctx)
          }
        }
      case (Right(invalid), ctx) =>
        Future.successful((Right(invalid), ctx))
    }

}
