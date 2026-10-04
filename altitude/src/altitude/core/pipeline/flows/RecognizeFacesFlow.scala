package altitude.core.pipeline.flows

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow

import scala.concurrent.ExecutionContext

import altitude.core.Altitude
import altitude.core.DuplicateException
import altitude.core.SamePersonDetectedTwiceException
import altitude.core.pipeline.PipelineTypes.InvalidAsset
import altitude.core.pipeline.PipelineTypes.TDataAssetOrInvalidWithContext
import altitude.core.pipeline.PipelineUtils.debugInfo
import altitude.core.pipeline.PipelineUtils.stage

/**
 * Matches the faces `DetectFacesFlow` found to people and stores them, one asset at a time and in upload order, so a face can
 * join the person an earlier asset of the batch started
 */
object RecognizeFacesFlow:
  def apply(app: Altitude): Flow[TDataAssetOrInvalidWithContext, TDataAssetOrInvalidWithContext, NotUsed] =
    given ExecutionContext = app.importDispatcher
    stage("Face recognition", parallelism = 1) {
      dataAsset =>
        debugInfo(s"\tRecognizing faces ${dataAsset.asset.fileName}")
        val detected = dataAsset.detectedFaces.getOrElse(
          throw IllegalStateException(s"The faces of ${dataAsset.asset.fileName} were not detected"))
        try
          app.service.faceRecognition.recognizeAndStore(dataAsset.asset, detected)
          Left(dataAsset)
        catch
          // The same face crop twice in one asset: the user is told why rather than shown a duplicate asset
          case e: DuplicateException =>
            val message = e.message.getOrElse(s"The same face was detected twice in ${dataAsset.asset.fileName}")
            Right(InvalidAsset(dataAsset, SamePersonDetectedTwiceException(message)))
    }
