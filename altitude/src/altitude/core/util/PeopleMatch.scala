package altitude.core.util

/**
 * How a search with several people chosen combines them (`peopleMatch`): the assets every one of them appears in, or those any of
 * them does. With one person the two are the same search.
 */
enum PeopleMatch(val apiValue: String):
  case All extends PeopleMatch("all")
  case Any extends PeopleMatch("any")
