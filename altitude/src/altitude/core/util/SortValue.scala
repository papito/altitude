package altitude.core.util

import java.time.LocalDateTime
import java.time.OffsetDateTime

/**
 * A sort-key value read from a search page row, kept exactly as the engine returned it so a cursor can bind it back against the
 * stored column without conversion.
 */
enum SortValue:
  case Text(value: String)
  case Num(value: Long)

  /** The cosine distance a Describe search orders by, computed by the engine */
  case Real(value: Double)

  /** A wall-clock timestamp (PostgreSQL `timestamp`). SQLite timestamps stay `Text` in their stored format. */
  case LocalTimestamp(value: LocalDateTime)

  /** An instant (PostgreSQL `timestamptz`) */
  case UtcInstant(value: OffsetDateTime)
  case Null

  def bindValue: Any = this match
    case Text(value) => value
    case Num(value) => value
    case Real(value) => value
    case LocalTimestamp(value) => value
    case UtcInstant(value) => value
    case Null => throw IllegalStateException("A null sort value is compared with IS NULL, never bound")
