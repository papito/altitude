package altitude.core.util

import java.time.LocalDateTime
import java.time.OffsetDateTime

/**
 * A typed sort-key value read from a grouped search page row, kept exactly as the engine returned it so a continuation cursor can
 * bind it back for comparison against the stored column without any conversion.
 */
enum SortValue:
  case Text(value: String)
  case Num(value: Long)

  /** A wall-clock timestamp (PostgreSQL `timestamp`). SQLite timestamps stay `Text` in their stored format. */
  case LocalTimestamp(value: LocalDateTime)

  /** An instant (PostgreSQL `timestamptz`) */
  case UtcInstant(value: OffsetDateTime)
  case Null

  def bindValue: Any = this match
    case Text(value) => value
    case Num(value) => value
    case LocalTimestamp(value) => value
    case UtcInstant(value) => value
    case Null => throw IllegalStateException("A null sort value is compared with IS NULL, never bound")
