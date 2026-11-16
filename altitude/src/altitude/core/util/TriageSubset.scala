package altitude.core.util

/**
 * A Triage subset as a search scope (`subset`): the assets with a Face of an Unknown person, or those no Location points at. Only
 * Triage offers it, but it narrows any view like a folder, Person, album or Location does. `Const.search.subsets` in
 * `static/js/constants.js` repeats the API values.
 */
enum TriageSubset(val apiValue: String):
  case UnknownPeople extends TriageSubset("unknownPeople")
  case NoLocation extends TriageSubset("noLocation")

object TriageSubset:
  def fromApiValue(value: String): Option[TriageSubset] = TriageSubset.values.find(_.apiValue == value)
