package software.altitude.core.dao.postgres

import com.typesafe.config.Config
import software.altitude.core.FieldConst
import software.altitude.core.dao.jdbc.BaseDao
import software.altitude.core.models.UserMetadata

object AssetDao {
    val DEFAULT_SQL_COLS_FOR_SELECT: List[String] = List(
      "asset.*",
      s"(asset.${FieldConst.Asset.USER_METADATA}#>>'{}')::text AS ${FieldConst.Asset.USER_METADATA}",
      s"(asset.${FieldConst.Asset.EXTRACTED_METADATA}#>>'{}')::text AS ${FieldConst.Asset.EXTRACTED_METADATA}",
      BaseDao.totalRecsWindowFunction
    )
}

class AssetDao(override val config: Config) extends software.altitude.core.dao.jdbc.AssetDao(config) with PostgresOverrides {
  override protected def columnsForSelect: List[String] = AssetDao.DEFAULT_SQL_COLS_FOR_SELECT

  override def getUserMetadata(assetId: String): Option[UserMetadata] = {
    val sql = s"""
      SELECT (${FieldConst.Asset.USER_METADATA}#>>'{}')::text AS ${FieldConst.Asset.USER_METADATA}
         FROM $tableName
       WHERE ${FieldConst.ID} = ?
      """

    val rec = executeAndGetOne(sql, List(assetId))
    val metadataJson = getJsonFromColumn(rec(FieldConst.Asset.USER_METADATA))
    val metadata = UserMetadata.fromJson(metadataJson)
    Some(metadata)
  }
}
