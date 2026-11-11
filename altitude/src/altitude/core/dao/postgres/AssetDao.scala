package altitude.core.dao.postgres

import com.typesafe.config.Config

import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.models.UserMetadata

class AssetDao(override val config: Config) extends altitude.core.dao.jdbc.AssetDao(config) with PostgresOverrides:

  override def getUserMetadata(assetId: String): UserMetadata =
    val sql = s"""
      SELECT (${FieldConst.Asset.USER_METADATA}#>>'{}')::text AS ${FieldConst.Asset.USER_METADATA}
         FROM asset
       WHERE ${FieldConst.ID} = ?
         AND ${FieldConst.REPO_ID} = ?
      """

    val rec = executeAndGetOne(sql, List(assetId, RequestContext.getRepository.persistedId))
    UserMetadata.fromJson(getJsonFromColumn(rec(FieldConst.Asset.USER_METADATA)))
