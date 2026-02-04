package altitude.core.pipeline.flows

import altitude.core.Altitude
import altitude.core.models.Asset
import altitude.core.pipeline.PipelineConstants.parallelism
import altitude.core.pipeline.PipelineTypes.TDataAssetOrInvalidWithContext
import altitude.core.pipeline.PipelineUtils.debugInfo
import altitude.core.pipeline.PipelineUtils.setThreadLocalRequestContext
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow

import scala.concurrent.Future

object ExtractMetadataFlow {
  def apply(app: Altitude): Flow[TDataAssetOrInvalidWithContext, TDataAssetOrInvalidWithContext, NotUsed] =
    Flow[TDataAssetOrInvalidWithContext].mapAsync(parallelism) {
      case (Left(dataAsset), ctx) =>
        setThreadLocalRequestContext(ctx)

        debugInfo(s"\tExtracting metadata for asset: ${dataAsset.asset.fileName}")
        // val userMetadata = app.service.metadata.cleanAndValidate(dataAsset.asset.userMetadata)
        val extractedMetadata = app.service.metadataExtractor.extract(dataAsset.data)
        val publicMetadata = Asset.getPublicMetadata(extractedMetadata)
        val (width, height) = app.service.asset.getDimensions(dataAsset)

        val asset: Asset = dataAsset.asset.copy(
          extractedMetadata = extractedMetadata,
          publicMetadata = publicMetadata,
          // userMetadata = userMetadata,
          width = width,
          height = height
        )

        Future.successful((Left(dataAsset.copy(asset = asset)), ctx))
      case (Right(invalid), ctx) => Future.successful((Right(invalid), ctx))
    }
}
