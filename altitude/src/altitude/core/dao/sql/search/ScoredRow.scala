package altitude.core.dao.sql.search

import java.time.LocalDate
import java.time.LocalDateTime
import scalasql.Table

/**
 * The `scored` CTE a grouped statement materializes first under the Relevance sort: every match with its group's day (none under
 * a Location grouping), its Relevance as `sort_value` and its capture time as `second_sort_value`, so the slices, the cursor
 * comparison and the order read the Relevance instead of computing it again.
 *
 * It is a table to ScalaSql only so that the slices over it can be typed queries, rendered as `FROM scored scored0`; no row is
 * ever read back as one, so the column types matter only to the compiler.
 */
private[search] case class ScoredRow[T[_]](
    id: T[String],
    day: T[Option[LocalDate]],
    sortValue: T[Int],
    secondSortValue: T[Option[LocalDateTime]])

private[search] object ScoredRow extends Table[ScoredRow]:
  override def tableName: String = "scored"
