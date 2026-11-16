package altitude.core.dao.sql.search

import java.time.LocalDateTime
import scalasql.Table

/**
 * The `sequence_frames` CTE a page grouped by Sequence materializes first: every match in a Sequence, with the first capture of
 * that Sequence's matching frames. It is materialized because the cursor comparison has to read that first capture, a window over
 * the frames, from outside the window.
 *
 * It is a ScalaSql table only so the slice over it can be a typed query; no row is read back as one, so the column types matter
 * only to the compiler.
 */
private[search] case class SequenceFrameRow[T[_]](
    id: T[String],
    sequenceId: T[Option[String]],
    firstCapturedAt: T[Option[LocalDateTime]],
    sortValue: T[Option[String]],
    secondSortValue: T[Option[LocalDateTime]])

private[search] object SequenceFrameRow extends Table[SequenceFrameRow]:
  override def tableName: String = "sequence_frames"
