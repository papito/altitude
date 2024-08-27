package software.altitude.core.dao.postgres

import com.typesafe.config.Config
import software.altitude.core.dao.jdbc.BaseDao
import software.altitude.core.models.Field
import software.altitude.core.models.Metadata

object AssetDao {
    val DEFAULT_SQL_COLS_FOR_SELECT: List[String] = List(
      "asset.*",
      s"(asset.${Field.Asset.METADATA}#>>'{}')::text AS ${Field.Asset.METADATA}",
      s"(asset.${Field.Asset.EXTRACTED_METADATA}#>>'{}')::text AS ${Field.Asset.EXTRACTED_METADATA}",
      BaseDao.totalRecsWindowFunction
    )
}

class AssetDao(override val config: Config) extends software.altitude.core.dao.jdbc.AssetDao(config) with PostgresOverrides {
  override protected def columnsForSelect: List[String] = AssetDao.DEFAULT_SQL_COLS_FOR_SELECT

  override def getMetadata(assetId: String): Option[Metadata] = {
    val sql = s"""
      SELECT (${Field.Asset.METADATA}#>>'{}')::text AS ${Field.Asset.METADATA}
         FROM $tableName
       WHERE ${Field.ID} = ?
      """

    val rec = executeAndGetOne(sql, List(assetId))
    val metadataJson = getJsonFromColumn(rec(Field.Asset.METADATA))
    val metadata = Metadata.fromJson(metadataJson)
    Some(metadata)
  }
}
