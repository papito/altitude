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

object IndexAndFaceRecFlow:
  def apply(app: Altitude): Flow[TDataAssetOrInvalidWithContext, TDataAssetOrInvalidWithContext, NotUsed] =
    Flow[TDataAssetOrInvalidWithContext].mapAsync(app.parallelism) {
      case (Left(dataAsset), ctx) =>
        setThreadLocalRequestContext(ctx)

        app.txManager.withFaceVector {
          try {
            debugInfo(s"\tPersisting asset ${dataAsset.asset.fileName}")
            val persisted = app.service.asset.add(dataAsset.asset)
            val persistedData = dataAsset.copy(asset = persisted)
            debugInfo(s"\tIndexing asset ${dataAsset.asset.fileName}")
            app.service.search.indexAsset(persisted)
            app.service.faceRecognition.processAsset(persistedData)

            Future.successful((Left(persistedData), ctx))
          } catch {
            case e: DuplicateException =>
              Future.successful(Right(InvalidAsset(dataAsset.asset, Some(e))), ctx)
          }
        }
      case (Right(invalid), ctx) =>
        Future.successful((Right(invalid), ctx))
    }
