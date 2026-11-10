package altitude.core.util

/**
 * A tally of the Triage summary as a search scope (`subset`): the assets with a Face of an Unknown person, or those no Location
 * points at. Only Triage offers it, but it narrows any view, and it is a place like a folder, Person, album or Location.
 */
enum TriageSubset(val apiValue: String):
  case UnknownPeople extends TriageSubset("unknownPeople")
  case NoLocation extends TriageSubset("noLocation")

object TriageSubset:
  def fromApiValue(value: String): Option[TriageSubset] = TriageSubset.values.find(_.apiValue == value)
