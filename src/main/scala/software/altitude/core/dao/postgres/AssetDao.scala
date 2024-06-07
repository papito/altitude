package software.altitude.core.dao.postgres

import play.api.libs.json.JsObject
import play.api.libs.json.Json
import software.altitude.core.AltitudeCoreApp
import software.altitude.core.Context
import software.altitude.core.models.Metadata
import software.altitude.core.transactions.TransactionId
import software.altitude.core.{Const => C}

object AssetDao {
    val DEFAULT_SQL_COLS_FOR_SELECT: List[String] = List(
      "asset.*",
      s"(asset.${C.Asset.METADATA}#>>'{}')::text as ${C.Asset.METADATA}",
      s"(asset.${C.Asset.EXTRACTED_METADATA}#>>'{}')::text as ${C.Asset.EXTRACTED_METADATA}",
      "EXTRACT(EPOCH FROM asset.created_at) AS created_at",
      "EXTRACT(EPOCH FROM asset.updated_at) AS updated_at"
    )
}

class AssetDao(app: AltitudeCoreApp) extends software.altitude.core.dao.jdbc.AssetDao(app) with Postgres {
  override protected def defaultSqlColsForSelect: List[String] = AssetDao.DEFAULT_SQL_COLS_FOR_SELECT

  override def getMetadata(assetId: String)(implicit ctx: Context, txId: TransactionId): Option[Metadata] = {
    val sql = s"""
      SELECT (${C.Asset.METADATA}#>>'{}')::text as ${C.Asset.METADATA}
         FROM $tableName
       WHERE ${C.Base.REPO_ID} = ? AND ${C.Asset.ID} = ?
      """

    oneBySqlQuery(sql, List(ctx.repo.id.get, assetId)) match {
      case Some(rec) =>
        val metadataCol = rec(C.Asset.METADATA)
        val metadataJsonStr: String = if (metadataCol == null) "{}" else metadataCol.asInstanceOf[String]
        val metadataJson = Json.parse(metadataJsonStr).as[JsObject]
        val metadata = Metadata.fromJson(metadataJson)
        Some(metadata)
      case None => None
    }
  }
}
