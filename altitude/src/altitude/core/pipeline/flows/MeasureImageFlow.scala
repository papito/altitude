package altitude.core.pipeline.flows

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow

import scala.concurrent.ExecutionContext

import altitude.core.Altitude
import altitude.core.pipeline.PipelineTypes.TDataAssetOrInvalidWithContext
import altitude.core.pipeline.PipelineUtils.debugInfo
import altitude.core.pipeline.PipelineUtils.stage

/**
 * Measures a still image's Image quality and perceptual hash over the staged file and the faces `DetectFacesFlow` found, and each
 * of those faces' sharpness, several assets at once and with no database touched. `RecognizeFacesFlow`, next, stores the faces
 * with their sharpness, and the completion stage the metrics and the hash. A Video and an animated image pass through untouched.
 */
object MeasureImageFlow:
  def apply(app: Altitude): Flow[TDataAssetOrInvalidWithContext, TDataAssetOrInvalidWithContext, NotUsed] =
    given ExecutionContext = app.importDispatcher
    stage("Image measurement", app.importParallelism) {
      dataAsset =>
        debugInfo(s"\tMeasuring ${dataAsset.asset.fileName}")
        Left(app.service.asset.measureImage(dataAsset))
    }
