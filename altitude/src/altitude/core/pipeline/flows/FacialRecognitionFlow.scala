package altitude.core.pipeline.flows

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.util.control.NonFatal

import altitude.core.Altitude
import altitude.core.DuplicateException
import altitude.core.SamePersonDetectedTwiceException
import altitude.core.pipeline.PipelineTypes.InvalidAsset
import altitude.core.pipeline.PipelineTypes.TDataAssetOrInvalidWithContext
import altitude.core.pipeline.PipelineUtils.debugInfo
import altitude.core.pipeline.PipelineUtils.setThreadLocalRequestContext

object FacialRecognitionFlow:
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  def apply(app: Altitude): Flow[TDataAssetOrInvalidWithContext, TDataAssetOrInvalidWithContext, NotUsed] =
    Flow[TDataAssetOrInvalidWithContext].mapAsync(app.parallelism) {
      case (Left(dataAsset), ctx) =>
        setThreadLocalRequestContext(ctx)

        debugInfo(s"\tRunning facial recognition ${dataAsset.asset.fileName}")
        try
          app.service.faceRecognition.processAsset(dataAsset)
          Future.successful(Left(dataAsset), ctx)
        catch
          case e: DuplicateException =>
            Future.successful(Right(InvalidAsset(dataAsset, SamePersonDetectedTwiceException(e.message.get))), ctx)
          // A file that cannot be decoded is dropped; the queue runs on
          case NonFatal(e) =>
            logger.error(s"Facial recognition failed for ${dataAsset.asset.fileName}", e)
            Future.successful(Right(InvalidAsset(dataAsset, e)), ctx)
      case (Right(invalid), ctx) => Future.successful(Right(invalid), ctx)
    }
