package altitude.core.pipeline.flows

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow

import altitude.core.Altitude
import altitude.core.DuplicateException
import altitude.core.SamePersonDetectedTwiceException
import altitude.core.pipeline.PipelineTypes.InvalidAsset
import altitude.core.pipeline.PipelineTypes.TDataAssetOrInvalidWithContext
import altitude.core.pipeline.PipelineUtils.debugInfo
import altitude.core.pipeline.PipelineUtils.stage

object FacialRecognitionFlow:
  def apply(app: Altitude): Flow[TDataAssetOrInvalidWithContext, TDataAssetOrInvalidWithContext, NotUsed] =
    stage("Facial recognition", app.parallelism) {
      dataAsset =>
        debugInfo(s"\tRunning facial recognition ${dataAsset.asset.fileName}")
        try
          app.service.faceRecognition.processAsset(dataAsset)
          Left(dataAsset)
        catch
          // The same face crop twice in one asset: the user is told why rather than shown a duplicate asset
          case e: DuplicateException =>
            val message = e.message.getOrElse(s"The same face was detected twice in ${dataAsset.asset.fileName}")
            Right(InvalidAsset(dataAsset, SamePersonDetectedTwiceException(message)))
    }
