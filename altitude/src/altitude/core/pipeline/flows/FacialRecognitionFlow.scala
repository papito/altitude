package altitude.core.pipeline.flows

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow

import scala.concurrent.Future

import altitude.core.Altitude
import altitude.core.DuplicateException
import altitude.core.SamePersonDetectedTwiceException
import altitude.core.pipeline.PipelineTypes.InvalidAsset
import altitude.core.pipeline.PipelineTypes.TDataAssetOrInvalidWithContext
import altitude.core.pipeline.PipelineUtils.debugInfo
import altitude.core.pipeline.PipelineUtils.setThreadLocalRequestContext

object FacialRecognitionFlow:
  def apply(app: Altitude): Flow[TDataAssetOrInvalidWithContext, TDataAssetOrInvalidWithContext, NotUsed] =
    Flow[TDataAssetOrInvalidWithContext].mapAsync(app.parallelism) {
      case (Left(dataAsset), ctx) =>
        setThreadLocalRequestContext(ctx)

        debugInfo(s"\tRunning facial recognition ${dataAsset.asset.fileName}")
        try {
          app.service.faceRecognition.processAsset(dataAsset)
        } catch {
          case e: DuplicateException =>
            Future.successful(Right(InvalidAsset(dataAsset.asset, Some(SamePersonDetectedTwiceException(e.message.get)))), ctx)
        }
        Future.successful(Left(dataAsset), ctx)
      case (Right(invalid), ctx) => Future.successful(Right(invalid), ctx)
    }
