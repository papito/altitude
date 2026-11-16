package altitude.core.dao.sql.search

import scalasql.Table

/**
 * The `described` CTE of a search among a part of a description's ranked matches: each asset of the part with its similarity, the
 * cosine of its Scene embedding to the description's. A table to ScalaSql only so the matches can be joined to it; no row is read
 * back as one.
 */
private[search] case class DescribedRow[T[_]](id: T[String], similarity: T[Double])

private[search] object DescribedRow extends Table[DescribedRow]:
  override def tableName: String = "described"
