package software.altitude.core.dao.postgres

import com.typesafe.config.Config
import software.altitude.core.dao.jdbc.BaseDao
import software.altitude.core.models.Field
import software.altitude.core.models.UserMetadata

object AssetDao {
    val DEFAULT_SQL_COLS_FOR_SELECT: List[String] = List(
      "asset.*",
      s"(asset.${Field.Asset.USER_METADATA}#>>'{}')::text AS ${Field.Asset.USER_METADATA}",
      s"(asset.${Field.Asset.EXTRACTED_METADATA}#>>'{}')::text AS ${Field.Asset.EXTRACTED_METADATA}",
      BaseDao.totalRecsWindowFunction
    )
}

class AssetDao(override val config: Config) extends software.altitude.core.dao.jdbc.AssetDao(config) with PostgresOverrides {
  override protected def columnsForSelect: List[String] = AssetDao.DEFAULT_SQL_COLS_FOR_SELECT

  override def getUserMetadata(assetId: String): Option[UserMetadata] = {
    val sql = s"""
      SELECT (${Field.Asset.USER_METADATA}#>>'{}')::text AS ${Field.Asset.USER_METADATA}
         FROM $tableName
       WHERE ${Field.ID} = ?
      """

    val rec = executeAndGetOne(sql, List(assetId))
    val metadataJson = getJsonFromColumn(rec(Field.Asset.USER_METADATA))
    val metadata = UserMetadata.fromJson(metadataJson)
    Some(metadata)
  }
}
