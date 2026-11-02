package altitude.core.dao.sql.search

import java.time.LocalDateTime
import scalasql.Table

/**
 * The `sequence_frames` CTE a page grouped by Sequence materializes first: every match that carries a Sequence, with its
 * Sequence, the first capture of that Sequence's matching frames, what it is sorted by as `sort_value` and its capture time as
 * `second_sort_value`. The cursor comparison has to read the first capture, a window over the frames, from outside that window.
 *
 * It is a table to ScalaSql only so that the slice over it can be a typed query, as [[ScoredRow]] is; no row is ever read back as
 * one, so the column types matter only to the compiler.
 */
private[search] case class SequenceFrameRow[T[_]](
    id: T[String],
    sequenceId: T[Option[String]],
    firstCapturedAt: T[Option[LocalDateTime]],
    sortValue: T[Option[String]],
    secondSortValue: T[Option[LocalDateTime]])

private[search] object SequenceFrameRow extends Table[SequenceFrameRow]:
  override def tableName: String = "sequence_frames"
