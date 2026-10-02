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

/** A candidate of a name source: `parentId` is a folder's parent, or the Category of a Location that has one */
case class SearchName(id: String, name: String, parentId: Option[String] = None)

/**
 * A term with what it matched among the names: for each name source with a match, the IDs the search filters that source's
 * relation by. They are person IDs for [[SearchSource.Person]], Location IDs for [[SearchSource.Location]] and for
 * [[SearchSource.Category]] (the Locations of the Categories the term matched), folder IDs for [[SearchSource.Folder]] (the
 * folders the term matched and every folder below them) and album IDs for [[SearchSource.Album]]. A source without a match has no
 * entry, and [[SearchSource.Document]] never has one: the engine matches the document itself.
 */
case class ResolvedSearchTerm(term: SearchTerm, ids: Map[SearchSource, Set[String]])

/** A [[SearchGroup]] with its terms resolved */
case class ResolvedSearchGroup(alternatives: Seq[ResolvedSearchTerm])

/**
 * A [[SearchExpression]] with its terms resolved against the names of the repository as they were when it was read. It is what a
 * search matches text by, and is resolved afresh for every search and every page of one.
 */
case class ResolvedSearchText(groups: Seq[ResolvedSearchGroup])
