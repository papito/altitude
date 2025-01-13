package software.altitude.core.pipeline.flows

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.altitude.core.Altitude
import software.altitude.core.UnsupportedMediaTypeException
import software.altitude.core.models.AssetWithData
import software.altitude.core.pipeline.PipelineTypes.InvalidAsset
import software.altitude.core.pipeline.PipelineTypes.PipelineContext
import software.altitude.core.pipeline.PipelineTypes.TDataAssetOrInvalidWithContext
import software.altitude.core.pipeline.PipelineTypes.TDataAssetWithContext
import software.altitude.core.pipeline.PipelineUtils.debugInfo

object CheckMediaTypeFlow {
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  def apply(app: Altitude): Flow[TDataAssetWithContext, TDataAssetOrInvalidWithContext, NotUsed] =
    Flow[(AssetWithData, PipelineContext)].map {
      case (dataAsset, ctx) =>
        debugInfo(s"\tChecking media type: ${dataAsset.asset.fileName}: ${dataAsset.asset.assetType.toJson}")

        try {
          app.service.library.checkMediaType(dataAsset.asset)
          (Left(dataAsset), ctx)
        } catch {
          case e: UnsupportedMediaTypeException =>
            logger.error(
              s"Unsupported media type \"${dataAsset.asset.assetType.mediaType}\" for asset ${dataAsset.asset.fileName}")
            (Right(InvalidAsset(dataAsset.asset, Some(e))), ctx)
        }
    }
}
