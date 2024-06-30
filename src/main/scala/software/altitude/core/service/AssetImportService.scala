package software.altitude.core.service

import org.apache.commons.codec.digest.DigestUtils
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.{Metadata => TikaMetadata}
import org.slf4j.LoggerFactory
import play.api.libs.json.Json
import software.altitude.core.Altitude
import software.altitude.core.FormatException
import software.altitude.core.MetadataExtractorException
import software.altitude.core.RequestContext
import software.altitude.core.models._

import java.io.InputStream

object AssetImportService {
  private val SUPPORTED_MEDIA_TYPES: Set[String] = Set("audio", "image")
}

class AssetImportService(app: Altitude) {
  private final val log = LoggerFactory.getLogger(getClass)

  def detectAssetType(importAsset: ImportAsset): AssetType = {
    log.debug(s"Detecting media type for: '$importAsset'")

    var inputStream: Option[InputStream] = None

    try {
      val metadata: TikaMetadata = new TikaMetadata
      inputStream = Some(TikaInputStream.get(importAsset.data, metadata))
      app.service.metadataExtractor.detectAssetTypeFromStream(inputStream.get)
    }
    finally {
      if (inputStream.isDefined) inputStream.get.close()
    }
  }

  def importAsset(importAsset: ImportAsset): Option[Asset] = {
    log.info(s"Importing file asset '$importAsset'")
    val assetType = detectAssetType(importAsset)

    if (!AssetImportService.SUPPORTED_MEDIA_TYPES.contains(assetType.mediaType)) {
      log.warn(s"Ignoring ${importAsset.fileName} of type ${assetType.mediaType}")
      return None
    }

    var metadataParserException: Option[Exception] = None
    val extractedMetadata: Metadata = try {
      app.service.metadataExtractor.extract(importAsset, assetType)
    }
    catch {
      case ex: Exception =>
        metadataParserException = Some(ex)
        Json.obj()
    }

    val asset: Asset = Asset(
      userId = RequestContext.account.value.get.persistedId,
      data = importAsset.data,
      fileName = importAsset.fileName,
      checksum = getChecksum(importAsset),
      assetType = assetType,
      sizeBytes = importAsset.data.length,
      isTriaged = true,
      folderId = RequestContext.getRepository.rootFolderId,
      extractedMetadata = extractedMetadata)

    val storedAsset: Option[Asset] = try {
      Some(app.service.library.add(asset))
    }
    catch {
      case _: FormatException =>
        return None
    }

    // if there was a parser error, throw exception, the caller needs to know there was an error
    if (metadataParserException.isDefined) {
      throw MetadataExtractorException(asset, metadataParserException.get)
    }

    storedAsset
  }

  private def getChecksum(importAsset: ImportAsset): String =
    DigestUtils.sha1Hex(importAsset.data)
}
