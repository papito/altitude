package altitude.core.dao.sql.search

import java.time.LocalDate
import java.time.LocalDateTime
import scalasql.Table

/**
 * The `scored` CTE a statement under a scored sort (Relevance or similarity) materializes first: every match with its day (under
 * a day grouping), its score as `sort_value` and its capture time as `second_sort_value`, so the slices, the cursor comparison
 * and the order read the score instead of computing it again. A table to ScalaSql only so the slices over it can be typed
 * queries; no row is read back as one, so the column types matter only to the compiler.
 */
private[search] case class ScoredRow[T[_]](
    id: T[String],
    day: T[Option[LocalDate]],
    sortValue: T[Int],
    secondSortValue: T[Option[LocalDateTime]])

private[search] object ScoredRow extends Table[ScoredRow]:
  override def tableName: String = "scored"
