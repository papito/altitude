package altitude.core.dao.postgres

import com.typesafe.config.Config

import altitude.core.FieldConst
import altitude.core.models.UserMetadata

class AssetDao(override val config: Config) extends altitude.core.dao.jdbc.AssetDao(config) with PostgresOverrides:

  override def getUserMetadata(assetId: String): Option[UserMetadata] =
    val sql = s"""
      SELECT (${FieldConst.Asset.USER_METADATA}#>>'{}')::text AS ${FieldConst.Asset.USER_METADATA}
         FROM asset
       WHERE ${FieldConst.ID} = ?
      """

    val rec = executeAndGetOne(sql, List(assetId))
    val metadataJson = getJsonFromColumn(rec(FieldConst.Asset.USER_METADATA))
    val metadata = UserMetadata.fromJson(metadataJson)
    Some(metadata)
