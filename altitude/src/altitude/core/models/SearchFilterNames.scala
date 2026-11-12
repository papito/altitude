package altitude.core.models

import altitude.core.util.DateRange
import altitude.core.util.PeopleMatch

/**
 * A folder or a Location as the advanced search panel names it: its ID and its path, the names from the top level down to its
 * own, which is the last. A folder's path opens with its top-level ancestor, the root left out; a Location's with its Category,
 * when it has one.
 */
case class NamedPath(id: String, path: List[String]):

  /** The names above its own, the top-level one first */
  def ancestors: List[String] = path.init

  def name: String = path.last

  /** The path as one line, `Trips › 2024 › Lisbon` */
  def label: String = path.mkString(NamedPath.SEPARATOR)

object NamedPath:
  val SEPARATOR = " › "

/**
 * What the IDs of a search name in its repository, for the chips of the advanced search panel: the folder, the people and the
 * Locations, each list in the order its IDs were given in. An ID that names nothing there has no entry.
 */
case class SearchFilterNames(folder: Option[NamedPath], people: List[Person], locations: List[NamedPath])

/**
 * The filters of a filtered search as its results title names them, one chip each (includes/search_results): the Search text,
 * when it has a usable term, what its IDs name in the repository (an ID that names nothing has no chip), how several people
 * combine, and the two date ranges.
 */
case class SearchFilters(
    text: Option[String],
    names: SearchFilterNames,
    peopleMatch: PeopleMatch,
    takenRange: Option[DateRange],
    importedRange: Option[DateRange])
