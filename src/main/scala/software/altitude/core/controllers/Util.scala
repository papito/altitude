package software.altitude.core.controllers

import play.api.libs.json.JsObject
import software.altitude.core.Altitude
import software.altitude.core.Context
import software.altitude.core.models.Asset
import software.altitude.core.models.MetadataField
import software.altitude.core.{Const => C}

object Util {
  def withFormattedMetadata(app: Altitude, asset: Asset, allFields: Option[Map[String, MetadataField]] = None)
                    (implicit ctx: Context): JsObject = {
    val metadata = app.service.metadata.toJson(asset.metadata, allFields)
    asset.modify(C.Asset.METADATA -> metadata)
  }

  def parseFolderIds(folderIds: String): Set[String] = {
    if (folderIds.isEmpty) {
      Set[String]()
    }
    else {
      folderIds
        .split(s"\\${C.Api.MULTI_VALUE_DELIM}")
        .map(_.trim)
        .filter(_.nonEmpty).toSet
    }
  }

}
