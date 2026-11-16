package altitude.core.util

/** How a search with several people combines them: the assets with every one of them, or with any */
enum PeopleMatch(val apiValue: String):
  case All extends PeopleMatch("all")
  case Any extends PeopleMatch("any")

object PeopleMatch:
  def fromApiValue(value: String): Option[PeopleMatch] = PeopleMatch.values.find(_.apiValue == value)
