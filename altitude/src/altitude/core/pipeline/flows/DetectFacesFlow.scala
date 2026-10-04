package altitude.core.pipeline.flows

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow

import scala.concurrent.ExecutionContext

import altitude.core.Altitude
import altitude.core.pipeline.PipelineTypes.TDataAssetOrInvalidWithContext
import altitude.core.pipeline.PipelineUtils.debugInfo
import altitude.core.pipeline.PipelineUtils.stage

/** Finds the asset's faces, several assets at once: no database is touched until `RecognizeFacesFlow` */
object DetectFacesFlow:
  def apply(app: Altitude): Flow[TDataAssetOrInvalidWithContext, TDataAssetOrInvalidWithContext, NotUsed] =
    given ExecutionContext = app.importDispatcher
    stage("Face detection", app.importParallelism) {
      dataAsset =>
        debugInfo(s"\tDetecting faces ${dataAsset.asset.fileName}")
        Left(dataAsset.copy(detectedFaces = Some(app.service.faceRecognition.detect(dataAsset))))
    }
