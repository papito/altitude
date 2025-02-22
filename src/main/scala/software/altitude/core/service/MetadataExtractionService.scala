package software.altitude.core.service

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.Directory
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.apache.tika.detect.DefaultDetector
import org.apache.tika.detect.Detector
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.{ Metadata => TikaMetadata }
import org.apache.tika.mime.{ MediaType => TikaMediaType }
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters._

import software.altitude.core.models.AssetType
import software.altitude.core.models.ExtractedMetadata

class MetadataExtractionService {
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  def extract(data: Array[Byte]): ExtractedMetadata = {
    val extractedMetadata = ExtractedMetadata()

    try {
      val rawMetadata: com.drew.metadata.Metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(data))

      for (directory: Directory <- rawMetadata.getDirectories.asScala) {
        // println(directory.getName)
        for (tag <- directory.getTags.asScala) {
          // println(s"\t${tag.getTagName} : ${tag.getTagType} -> ${tag.getDescription}")
          extractedMetadata.addValue(directory.getName, tag.getTagName, tag.getDescription)
        }
      }

      extractedMetadata
    } catch {
      case e: Exception =>
        logger.error("Error extracting metadata", e)
        ExtractedMetadata()
    }
  }

  def detectAssetType(data: Array[Byte]): AssetType = {
    var inputStream: Option[InputStream] = None

    try {
      val metadata: TikaMetadata = new TikaMetadata
      inputStream = Some(TikaInputStream.get(data, metadata))

      val detector: Detector = new DefaultDetector
      val tikaMediaType: TikaMediaType = detector.detect(inputStream.get, metadata)

      val assetType =
        AssetType(
          mediaType = tikaMediaType.getType,
          mediaSubtype = tikaMediaType.getSubtype,
          mime = tikaMediaType.getBaseType.toString)

      assetType
    } finally {
      if (inputStream.isDefined) inputStream.get.close()
    }
  }
}
