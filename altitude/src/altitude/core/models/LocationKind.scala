package altitude.core.models

import altitude.core.util.JsonCodec

/** What a `location` row is: a named container or a pinned place. Persist dbValue, never the enum name. */
enum LocationKind(val dbValue: String):
  case Category extends LocationKind("category")
  case Location extends LocationKind("location")

object LocationKind:
  def fromDbValue(value: String): Option[LocationKind] = values.find(_.dbValue == value)

  given JsonCodec.ReadWriter[LocationKind] = JsonCodec
    .readwriter[String]
    .bimap(_.dbValue, value => fromDbValue(value).getOrElse(throw IllegalArgumentException(s"Unknown location kind: $value")))
