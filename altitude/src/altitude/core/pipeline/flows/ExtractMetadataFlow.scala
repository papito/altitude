package altitude.core.pipeline.flows

import java.time.LocalDateTime
import java.time.ZoneOffset
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow

import scala.concurrent.ExecutionContext

import altitude.core.Altitude
import altitude.core.models.Asset
import altitude.core.pipeline.PipelineTypes.TDataAssetOrInvalidWithContext
import altitude.core.pipeline.PipelineUtils.debugInfo
import altitude.core.pipeline.PipelineUtils.stage
import altitude.core.util.CaptureDateInputs
import altitude.core.util.CaptureDateResolver
import altitude.core.util.GeoLocationResolver

object ExtractMetadataFlow:
  def apply(app: Altitude): Flow[TDataAssetOrInvalidWithContext, TDataAssetOrInvalidWithContext, NotUsed] =
    given ExecutionContext = app.importDispatcher
    stage("Metadata extraction", app.importParallelism) {
      dataAsset =>
        debugInfo(s"\tExtracting metadata for asset: ${dataAsset.asset.fileName}")
        // Merge upstream synthetic metadata so every resolver input remains available for a later replay.
        val extractedMetadata = dataAsset.asset.extractedMetadata.merge(app.service.metadataExtractor.extract(dataAsset.path))
        val capture = CaptureDateResolver.resolve(
          CaptureDateInputs(extractedMetadata, dataAsset.asset.fileName),
          LocalDateTime.now(ZoneOffset.UTC))
        debugInfo(
          s"\tCapture date for ${dataAsset.asset.fileName}: ${capture.map(c => s"${c.at} (${c.source.dbValue})").getOrElse("unknown")}")
        val point = GeoLocationResolver.resolve(extractedMetadata)
        debugInfo(
          s"\tCoordinates for ${dataAsset.asset.fileName}: ${point.map(p => s"${p.latitude}, ${p.longitude}").getOrElse("none")}")
        val publicMetadata = Asset.getPublicMetadata(extractedMetadata)

        // A file whose type claims an image or a video its decoder cannot read is dropped here, as an ImageException or a
        // VideoException
        val (width, height, durationMs) = app.service.asset.getDimensionsAndDuration(dataAsset)

        val asset: Asset = dataAsset.asset.copy(
          extractedMetadata = extractedMetadata,
          publicMetadata = publicMetadata,
          originalCreatedAt = capture.map(_.at),
          originalCreatedAtSource = capture.map(_.source),
          latitude = point.map(_.latitude),
          longitude = point.map(_.longitude),
          width = width,
          height = height,
          durationMs = durationMs
        )

        Left(dataAsset.copy(asset = asset))
    }
