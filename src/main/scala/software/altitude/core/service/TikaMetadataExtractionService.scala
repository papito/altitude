package software.altitude.core.service

import org.apache.tika.detect.DefaultDetector
import org.apache.tika.detect.Detector
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.{Metadata => TikaMetadata}
import org.apache.tika.mime.{MediaType => TikaMediaType}
import org.apache.tika.parser.AutoDetectParser
import org.apache.tika.sax.BodyContentHandler
import org.slf4j.LoggerFactory
import software.altitude.core.models.AssetType
import software.altitude.core.models.ImportAsset
import software.altitude.core.models.Metadata
import software.altitude.core.models.MetadataValue

import java.io.InputStream

class TikaMetadataExtractionService extends MetadataExtractionService {
  private final val log = LoggerFactory.getLogger(getClass)

  override def extract(importAsset: ImportAsset, mediaType: AssetType, asRaw: Boolean = false): Metadata = {
    val raw: Option[TikaMetadata] = extractMetadata(importAsset)

    val metadata = rawToMetadata(raw)

    // make it nice and neat out of the horrible mess that this probably is
    if (asRaw) metadata else normalize(metadata)
  }

  private def rawToMetadata(raw: Option[TikaMetadata]): Metadata = {
    if (raw.isEmpty) {
      Metadata()
    }

    val data = scala.collection.mutable.Map[String, Set[String]]()

    for (name <- raw.get.names()) {
      data(name) = Set(raw.get.get(name).trim)
    }

    Metadata(data.toMap)
  }

  private def extractMetadata(importAsset: ImportAsset): Option[TikaMetadata] = {
    log.info(s"Extracting metadata for '$importAsset'")
    try {
      val metadata = new TikaMetadata()
      val inputStream = TikaInputStream.get(importAsset.data, metadata)
      val parser = new AutoDetectParser()
      val handler = new BodyContentHandler()

      // new ImageMetadataExtractor(metadata).parseTiff(tis.getFile());
      parser.parse(inputStream, handler, metadata)
      inputStream.close()
      Some(metadata)
    }
    catch {
      case ex: Exception =>
        ex.printStackTrace()
        log.error(s"Error extracting metadata for '$importAsset': ${ex.toString}")
        None
      }
  }

private def normalize(metadata: Metadata): Metadata = {
    val normalizedData = scala.collection.mutable.Map[String, Set[MetadataValue]]()


    FIELD_REFERENCE.foreach { case (destField, srcFields) =>
      if (srcFields.isEmpty && metadata.contains(destField)) {
        normalizedData(destField) = metadata.data(destField)
      }
      else {
        // find all the fields that exist in metadata
        val existingSrcFields = srcFields.filter(metadata.contains)
        // the field on TOP is it
        if (existingSrcFields.nonEmpty) {
          normalizedData(destField) = metadata.data(existingSrcFields.head)
        }
      }
    }

    // copy over the raw fields in
    Metadata(normalizedData.toMap)
  }

  def detectAssetTypeFromStream(is: InputStream): AssetType = {
    val metadata: TikaMetadata = new TikaMetadata

    val detector: Detector = new DefaultDetector
    val tikaMediaType: TikaMediaType = detector.detect(is, metadata)

    val assetType = AssetType(
      mediaType = tikaMediaType.getType,
      mediaSubtype = tikaMediaType.getSubtype,
      mime = tikaMediaType.getBaseType.toString)

    assetType
  }
}
