package altitude.core.pipeline.flows

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow

import altitude.core.Altitude
import altitude.core.pipeline.PipelineTypes.TDataAssetOrInvalidWithContext
import altitude.core.pipeline.PipelineTypes.TDataAssetWithContext
import altitude.core.pipeline.PipelineUtils.debugInfo
import altitude.core.pipeline.PipelineUtils.guarded

object CheckMediaTypeFlow:
  def apply(app: Altitude): Flow[TDataAssetWithContext, TDataAssetOrInvalidWithContext, NotUsed] =
    Flow[TDataAssetWithContext].map {
      case (dataAsset, ctx) =>
        guarded("Media type check", dataAsset, ctx) {
          debugInfo(s"\tChecking media type: ${dataAsset.asset.fileName}: ${dataAsset.asset.assetType.toJson}")
          app.service.library.checkMediaType(dataAsset.asset)
          Left(dataAsset)
        }
    }
