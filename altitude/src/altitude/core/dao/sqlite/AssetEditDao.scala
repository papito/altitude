package altitude.core.dao.sqlite

import com.typesafe.config.Config

import altitude.core.FieldConst
import altitude.core.dao.jdbc

class AssetEditDao(override val config: Config) extends jdbc.AssetEditDao(config) with SqliteOverrides:
  override protected def headNotRendered: String =
    s"${FieldConst.AssetEdit.RENDERED_SEQ} IS NOT ${FieldConst.AssetEdit.HEAD_SEQ}"
