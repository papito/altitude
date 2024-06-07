package software.altitude.core.service

import software.altitude.core.models.AssetType
import software.altitude.core.models.ImportAsset
import software.altitude.core.models.Metadata

import java.io.InputStream

abstract class MetadataExtractionService {

  /**
   * This structure defines how we construct the final metadata object.
   * Metadata can have a lot of fields that are related or identical
   */
  val FIELD_REFERENCE: Map[String, List[String]] = Map(
    /*
        FINAL_FIELD_1 -> [
          POSSIBLE_FIELD_1_PRIORITY_1,
          POSSIBLE_FIELD_1_PRIORITY_2]
        FINAL_FIELD_2 -> [
          POSSIBLE_FIELD_2_PRIORITY_1,
          POSSIBLE_FIELD_2_PRIORITY_2,
          POSSIBLE_FIELD_2_PRIORITY_3]
     */
    "Image Width" -> List(
      "tiff:ImageWidth",
      "exif:ImageWidth",
      "Image Width"
    ),

    "Image Height" -> List(
      "tiff:ImageLength",
      "exif:Image Height",
      "Image Height"
    ),

    "Make" -> List(
      "tiff:Make",
      "exif:Make",
      "Make"
    ),

    "Model" -> List(
      "tiff:Model",
      "exif:Model",
      "Model"
    ),

    "Software" -> List(),

    "Lens" -> List(
      "Lens Information",
      "Lens",
      "Lens Model"
    ),

    "Iso Speed" -> List(
      "exif:IsoSpeedRatings",
      "ISO Speed Ratings"),

    "Focal Length" -> List(
      "exif:FocalLength",
      "Focal Length",
      "Aperture Value"
    ),

    "F-Number" -> List(
      "exif:FNumber",
      "F-Number"),

    "Exposure Time" -> List(
      "exif:ExposureTime",
      "Exposure Time"
    ),

    "Flash" -> List(
      "exif:Flash",
      "Flash"
    ),

    "User Comment" -> List(
      "User Comment",
      "w:comments",
      "JPEG Comment",
      "Comments",
      "Comment")
  )

  def extract(importAsset: ImportAsset, assetType: AssetType, asRaw: Boolean): Metadata
  def detectAssetTypeFromStream(is: InputStream): AssetType
}
