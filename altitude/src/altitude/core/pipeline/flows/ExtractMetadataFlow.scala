package altitude.core.pipeline.flows

import java.time.LocalDateTime
import java.time.ZoneOffset
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow

import scala.concurrent.Future

import altitude.core.Altitude
import altitude.core.models.Asset
import altitude.core.pipeline.PipelineTypes.TDataAssetOrInvalidWithContext
import altitude.core.pipeline.PipelineUtils.debugInfo
import altitude.core.pipeline.PipelineUtils.setThreadLocalRequestContext
import altitude.core.util.CaptureDateInputs
import altitude.core.util.CaptureDateResolver

object ExtractMetadataFlow:
  def apply(app: Altitude): Flow[TDataAssetOrInvalidWithContext, TDataAssetOrInvalidWithContext, NotUsed] =
    Flow[TDataAssetOrInvalidWithContext].mapAsync(app.parallelism) {
      case (Left(dataAsset), ctx) =>
        setThreadLocalRequestContext(ctx)

        debugInfo(s"\tExtracting metadata for asset: ${dataAsset.asset.fileName}")
        // Merge upstream synthetic metadata so every resolver input remains available for a later replay.
        val extractedMetadata = dataAsset.asset.extractedMetadata.merge(app.service.metadataExtractor.extract(dataAsset.data))
        val capture = CaptureDateResolver.resolve(
          CaptureDateInputs(extractedMetadata, dataAsset.asset.fileName),
          LocalDateTime.now(ZoneOffset.UTC))
        debugInfo(
          s"\tCapture date for ${dataAsset.asset.fileName}: ${capture.map(c => s"${c.at} (${c.source.dbValue})").getOrElse("unknown")}")
        val publicMetadata = Asset.getPublicMetadata(extractedMetadata)
        val (width, height) = app.service.asset.getDimensions(dataAsset)

        val asset: Asset = dataAsset.asset.copy(
          extractedMetadata = extractedMetadata,
          publicMetadata = publicMetadata,
          originalCreatedAt = capture.map(_.at),
          originalCreatedAtSource = capture.map(_.source),
          width = width,
          height = height
        )

        Future.successful((Left(dataAsset.copy(asset = asset)), ctx))
      case (Right(invalid), ctx) => Future.successful((Right(invalid), ctx))
    }
