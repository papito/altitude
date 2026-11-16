package altitude.core.util

/** Where a term of the Search text can match, with the Relevance a match there is worth */
enum SearchSource(val relevance: Int):
  case Person extends SearchSource(5)
  case Location extends SearchSource(4)
  case Category extends SearchSource(3)
  case Folder extends SearchSource(2)
  case Album extends SearchSource(2)

  /** The asset's own Search document: its file name and user metadata values */
  case Document extends SearchSource(1)

object SearchSource:

  /** The repository's names, read and matched before a search runs */
  val names: Set[SearchSource] = values.toSet - Document

/** A candidate of a name source: `parentId` is a folder's parent, or the Category of a Location that has one */
case class SearchName(id: String, name: String, parentId: Option[String] = None)

/**
 * For each name source with a match, the IDs the search filters that source's relation by: a Category gives its Locations' IDs,
 * and a folder adds every folder below it. A source without a match has no entry, and [[SearchSource.Document]] never has one:
 * the engine matches the document itself.
 */
case class ResolvedSearchTerm(term: SearchTerm, ids: Map[SearchSource, Set[String]])

/**
 * A [[SearchGroup]] with its terms resolved. A complete group is a positive one whose every possible match the probe read, no
 * more of them than its limit: the candidates of the text are within those matches, so a search tests it no further.
 */
case class ResolvedSearchGroup(alternatives: Seq[ResolvedSearchTerm], isComplete: Boolean = false):

  /** A group none of whose alternatives is excluded: the only kind whose matches can be read as a set of assets */
  def isPositive: Boolean = alternatives.forall(!_.term.isExcluded)

/**
 * A [[SearchExpression]] resolved against the repository's names as they were when read, afresh for every search and every page.
 *
 * `candidates`, the intersection of the complete groups' matches, are the only assets the search reads (the selective path), and
 * an empty set answers it with nothing. Without them, the text is matched over the whole library (the broad path).
 */
case class ResolvedSearchText(groups: Seq[ResolvedSearchGroup], candidates: Option[Set[String]] = None)
