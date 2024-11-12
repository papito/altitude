package software.altitude.core.service
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.altitude.core.Altitude
import software.altitude.core.FormatException
import software.altitude.core.RequestContext
import software.altitude.core.models._
import software.altitude.core.util.MurmurHash

object AssetImportService {
  private val SUPPORTED_MEDIA_TYPES: Set[String] = Set("video", "image")
}

class AssetImportService(app: Altitude) {
  protected final val logger: Logger = LoggerFactory.getLogger(getClass)

  def detectAssetType(importAsset: ImportAsset): AssetType = {
    logger.debug(s"Detecting media type for: '$importAsset'")
    app.service.metadataExtractor.detectAssetType(importAsset.data)
  }

  def importAsset(importAsset: ImportAsset): Option[Asset] = {
    logger.info(s"Importing file asset '$importAsset'")
    val assetType = detectAssetType(importAsset)

    if (!AssetImportService.SUPPORTED_MEDIA_TYPES.contains(assetType.mediaType)) {
      logger.warn(s"Ignoring ${importAsset.fileName} of type ${assetType.mediaType}")
      return None
    }

    val asset: Asset = Asset(
      userId = RequestContext.account.value.get.persistedId,
      fileName = importAsset.fileName,
      checksum = MurmurHash.hash32(importAsset.data),
      assetType = assetType,
      sizeBytes = importAsset.data.length,
      isTriaged = true,
      folderId = RequestContext.getRepository.rootFolderId)

    val assetWithData = AssetWithData(asset, importAsset.data)

    val storedAsset: Option[Asset] = try {
      Some(app.service.library.add(assetWithData))
    }
    catch {
      case _: FormatException =>
        return None
    }

    storedAsset
  }
}
