package altitude.core.pipeline.flows

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow

import scala.concurrent.ExecutionContext

import altitude.core.Altitude
import altitude.core.pipeline.PipelineTypes.TDataAssetOrInvalidWithContext
import altitude.core.pipeline.PipelineUtils.debugInfo
import altitude.core.pipeline.PipelineUtils.stage

/**
 * Measures a still image's Image quality, perceptual hash and each detected face's sharpness, several assets at once with no
 * database touched; later stages store them. A Video or an animated image passes through untouched.
 */
object MeasureImageFlow:
  def apply(app: Altitude): Flow[TDataAssetOrInvalidWithContext, TDataAssetOrInvalidWithContext, NotUsed] =
    given ExecutionContext = app.importDispatcher
    stage("Image measurement", app.importParallelism) {
      dataAsset =>
        debugInfo(s"\tMeasuring ${dataAsset.asset.fileName}")
        Left(app.service.asset.measureImage(dataAsset))
    }
