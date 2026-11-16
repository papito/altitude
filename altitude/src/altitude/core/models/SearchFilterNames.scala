package altitude.core.models

import altitude.core.util.DateRange
import altitude.core.util.PeopleMatch

/**
 * A folder or a Location as the advanced search panel names it, `path` running from the top level down to its own name. A
 * folder's path leaves out the root; a Location's opens with its Category, when it has one.
 */
case class NamedPath(id: String, path: List[String]):

  def ancestors: List[String] = path.init

  def name: String = path.last

  /** The path as one line, `Trips › 2024 › Lisbon` */
  def label: String = path.mkString(NamedPath.SEPARATOR)

object NamedPath:
  val SEPARATOR = " › "

/** What a search's IDs name in its repository, each list in the order the IDs were given; an ID that names nothing is left out */
case class SearchFilterNames(folder: Option[NamedPath], people: List[Person], locations: List[NamedPath])

/** The filters a filtered search's results title names, one chip each; `text` is set only when it has a usable term */
case class SearchFilters(
    description: Option[String],
    text: Option[String],
    names: SearchFilterNames,
    peopleMatch: PeopleMatch,
    takenRange: Option[DateRange],
    importedRange: Option[DateRange]):

  /** Whether another filter narrows the description's results, so an empty result is said to be these filters', not the Library's */
  def narrowsDescription: Boolean =
    text.isDefined || names.folder.isDefined || names.people.nonEmpty || names.locations.nonEmpty || takenRange.isDefined ||
      importedRange.isDefined
