package altitude.core.dao.sql.search

import scalasql.Table

/**
 * The `described` CTE of a search among a part of a description's ranked matches (`SearchQueries.statement`): each asset of the
 * part with its similarity to the description, the cosine of its SigLIP 2 Scene embedding to the description's.
 *
 * It is a table to ScalaSql only so that the matches can be joined to it for their similarity, as [[ScoredRow]] is; no row is
 * ever read back as one.
 */
private[search] case class DescribedRow[T[_]](id: T[String], similarity: T[Double])

private[search] object DescribedRow extends Table[DescribedRow]:
  override def tableName: String = "described"
