package altitude.core.util

/**
 * A Triage subset, the assets one of the Triage summary's counts shows, as a search scope (`subset`): those with a Face of an
 * Unknown person, or those no Location points at. Only Triage offers it, but it narrows any view, and it is a place like a
 * folder, Person, album or Location. `static/js/constants.js` (`Const.search.subsets`) repeats the API values for the client.
 */
enum TriageSubset(val apiValue: String):
  case UnknownPeople extends TriageSubset("unknownPeople")
  case NoLocation extends TriageSubset("noLocation")

object TriageSubset:
  def fromApiValue(value: String): Option[TriageSubset] = TriageSubset.values.find(_.apiValue == value)
