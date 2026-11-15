package altitude.core.dao.sql.search

import scalasql.Table

/**
 * The `scanned` CTE of a Describe search on SQLite: sqlite-vector's exact scan of the SigLIP 2 Scene embeddings, one row for each
 * row of `asset` that has one, whatever its repository, with the asset's rowid and its cosine distance from the description's
 * embedding.
 *
 * It is a table to ScalaSql only so that the ranked rows can be a typed query, the matching assets joined to it, as [[ScoredRow]]
 * is; no row is ever read back as one.
 */
private[search] case class ScannedRow[T[_]](assetRowid: T[Long], distance: T[Double])

private[search] object ScannedRow extends Table[ScannedRow]:
  override def tableName: String = "scanned"
