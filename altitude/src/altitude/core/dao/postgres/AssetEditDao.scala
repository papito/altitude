package altitude.core.dao.postgres

import com.typesafe.config.Config

import altitude.core.FieldConst
import altitude.core.dao.jdbc

class AssetEditDao(override val config: Config) extends jdbc.AssetEditDao(config) with PostgresOverrides:
  override protected def versionNotRendered: String =
    s"${FieldConst.AssetEdit.RENDERED_VERSION} IS DISTINCT FROM ${FieldConst.AssetEdit.EDIT_VERSION}"
