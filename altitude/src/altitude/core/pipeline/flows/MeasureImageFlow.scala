package altitude.core.pipeline.flows

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow

import scala.concurrent.ExecutionContext

import altitude.core.Altitude
import altitude.core.pipeline.PipelineTypes.TDataAssetOrInvalidWithContext
import altitude.core.pipeline.PipelineUtils.debugInfo
import altitude.core.pipeline.PipelineUtils.stage

/**
 * Measures a still image's Image quality and perceptual hash over the staged file and the faces `DetectFacesFlow` found, several
 * assets at once and with no database touched; the completion stage stores them. A Video and an animated image pass through
 * untouched.
 */
object MeasureImageFlow:
  def apply(app: Altitude): Flow[TDataAssetOrInvalidWithContext, TDataAssetOrInvalidWithContext, NotUsed] =
    given ExecutionContext = app.importDispatcher
    stage("Image measurement", app.importParallelism) {
      dataAsset =>
        debugInfo(s"\tMeasuring ${dataAsset.asset.fileName}")
        Left(dataAsset.copy(asset = app.service.asset.measureImage(dataAsset)))
    }
