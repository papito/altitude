package altitude.core.pipeline.flows

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow

import altitude.core.Altitude
import altitude.core.pipeline.PipelineTypes.TDataAssetOrInvalidWithContext
import altitude.core.pipeline.PipelineUtils.debugInfo
import altitude.core.pipeline.PipelineUtils.stage

object AddPreviewFlow:
  def apply(app: Altitude): Flow[TDataAssetOrInvalidWithContext, TDataAssetOrInvalidWithContext, NotUsed] =
    stage("Preview", app.parallelism) {
      dataAsset =>
        debugInfo(s"\tGenerating preview ${dataAsset.asset.fileName}")
        app.service.asset.addPreview(dataAsset)
        Left(dataAsset)
    }
