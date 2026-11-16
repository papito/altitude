package altitude.core.dao.sql.search

import scalasql.Table

/**
 * The `scanned` CTE of a Describe search on SQLite: sqlite-vector's exact scan of every asset's Scene embedding, whatever its
 * repository, as the asset's rowid and its cosine distance from the description's. A table to ScalaSql only so the matching
 * assets can be joined to it in a typed query; no row is read back as one.
 */
private[search] case class ScannedRow[T[_]](assetRowid: T[Long], distance: T[Double])

private[search] object ScannedRow extends Table[ScannedRow]:
  override def tableName: String = "scanned"
