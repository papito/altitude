package altitude.core.service

import com.drew.imaging.ImageMetadataReader
import com.drew.lang.KeyValuePair
import com.drew.metadata.Directory
import com.drew.metadata.png.PngDirectory
import com.drew.metadata.xmp.XmpDirectory
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

import altitude.core.models.AssetType
import altitude.core.models.ExtractedMetadata

class MetadataExtractionService:
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  def extract(data: Array[Byte]): ExtractedMetadata =
    val extractedMetadata = ExtractedMetadata()

    try
      val rawMetadata: com.drew.metadata.Metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(data))

      for (directory: Directory <- rawMetadata.getDirectories.asScala) {
        for (tag <- directory.getTags.asScala) {
          // Each PNG text chunk is a separate directory with the same name. Keep its keys instead of overwriting Textual Data.
          if directory.isInstanceOf[PngDirectory] && tag.getTagType == PngDirectory.TAG_TEXTUAL_DATA then
            directory.getObject(PngDirectory.TAG_TEXTUAL_DATA).asInstanceOf[java.util.List[KeyValuePair]].asScala.foreach {
              pair => addValue(extractedMetadata, directory.getName, pair.getKey, pair.getValue.toString)
            }
          else addValue(extractedMetadata, directory.getName, tag.getTagName, tag.getDescription)
        }
        // XMP's ordinary tags contain only the property count; the replayable property paths live in a separate map.
        directory match
          case xmp: XmpDirectory =>
            xmp.getXmpProperties.asScala.foreach {
              case (key, value) => addValue(extractedMetadata, directory.getName, key, value)
            }
          case _ => ()
      }

      extractedMetadata
    catch
      case e: Exception =>
        logger.error("Error extracting metadata", e)
        ExtractedMetadata()

  /**
   * A tag whose description cannot be derived is skipped: a GPS coordinate without its ref tag, for one, describes as null, and a
   * null value would not survive the JSON column. Null characters are removed from the rest, since Postgres, for one, rejects
   * them inside a string.
   */
  private def addValue(extractedMetadata: ExtractedMetadata, directoryName: String, key: String, value: String): Unit =
    Option(value).foreach(v => extractedMetadata.addValue(directoryName, key, v.replace("\u0000", "")))

  def detectAssetType(data: Array[Byte]): AssetType =
    var inputStream: Option[InputStream] = None

    try
      val metadata: TikaMetadata = new TikaMetadata
      inputStream = Some(TikaInputStream.get(data, metadata))

      val detector: Detector = new DefaultDetector
      val tikaMediaType: TikaMediaType = detector.detect(inputStream.get, metadata)

      AssetType(
        mediaType = tikaMediaType.getType,
        mediaSubtype = tikaMediaType.getSubtype,
        mime = tikaMediaType.getBaseType.toString)

    finally if inputStream.isDefined then inputStream.get.close()
