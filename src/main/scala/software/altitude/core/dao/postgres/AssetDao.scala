package software.altitude.core.dao.postgres

import software.altitude.core.Configuration
import software.altitude.core.dao.jdbc.BaseDao
import software.altitude.core.models.Metadata
import software.altitude.core.{Const => C}

object AssetDao {
    val DEFAULT_SQL_COLS_FOR_SELECT: List[String] = List(
      "asset.*",
      s"(asset.${C.Asset.METADATA}#>>'{}')::text AS ${C.Asset.METADATA}",
      s"(asset.${C.Asset.EXTRACTED_METADATA}#>>'{}')::text AS ${C.Asset.EXTRACTED_METADATA}",
      BaseDao.totalRecsWindowFunction
    )
}

class AssetDao(override val config: Configuration) extends software.altitude.core.dao.jdbc.AssetDao(config) with PostgresOverrides {
  override protected def columnsForSelect: List[String] = AssetDao.DEFAULT_SQL_COLS_FOR_SELECT

  override def getMetadata(assetId: String): Option[Metadata] = {
    val sql = s"""
      SELECT (${C.Asset.METADATA}#>>'{}')::text AS ${C.Asset.METADATA}
         FROM $tableName
       WHERE ${C.Asset.ID} = ?
      """

    val rec = getOneRawRecordBySql(sql, List(assetId))
    val metadataJson = getJsonFromColumn(rec(C.Asset.METADATA))
    val metadata = Metadata.fromJson(metadataJson)
    Some(metadata)
  }
}
