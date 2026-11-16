package altitude.core.util

import altitude.core.Api
import altitude.core.Const
import altitude.core.FieldConst
import altitude.core.models.SceneEmbedding

case class SearchSort(field: String, direction: SortDirection):

  /** Computed from the Search text; no asset column */
  def isRelevance: Boolean = field == Const.Search.SORT_RELEVANCE

  /** How much a still image looks like a Describe search's description; no asset column either */
  def isSimilarity: Boolean = field == Const.Search.SORT_SIMILARITY

  /** By a value a statement computes for each match rather than a column */
  def isScored: Boolean = isRelevance || isSimilarity

  def toJson: ujson.Obj = ujson.Obj(
    Api.Field.SearchSort.DIRECTION -> direction.toString,
    Api.Field.SearchSort.FIELD -> field
  )

  override def toString: String = s"SearchSort(field=$field, direction=${direction.id})"

object SearchSort:
  val Relevance: SearchSort = SearchSort(Const.Search.SORT_RELEVANCE, SortDirection.DESC)

  val Similarity: SearchSort = SearchSort(Const.Search.SORT_SIMILARITY, SortDirection.DESC)

/**
 * `minCosine` is the Describe match threshold, a cosine to an asset's SigLIP 2 Scene embedding; `weakerRatio` the fraction of the
 * nearest ranked asset's cosine under which a ranked asset is a weaker match ([[WeakerMatches]]). `throughIndex` reads the
 * ranking through a vector index rather than one asset at a time, as the DAO decides (`SearchDao.rankingFor`); an engine without
 * one reads every ranking exactly.
 */
case class DescribeRanking(
    embedding: SceneEmbedding,
    minCosine: Double,
    limit: Int,
    weakerRatio: Double,
    throughIndex: Boolean = false):

  /** The cutoff as a statement compares it, as a cosine distance */
  def maxDistance: Double = 1 - minCosine

/** The two parts of a Describe search's ranked matches: those above the line of its weaker matches, and the weaker ones */
enum DescribedPart:
  case Nearer, Weaker

/**
 * A description as one filter of a sorted or grouped Describe search: the search is among one part of the ranked matches of its
 * other filters, and orders them as any search does.
 */
case class Described(ranking: DescribeRanking, part: DescribedPart)

class SearchQuery(
    val text: Option[String] = None,
    override val params: Map[String, Any] = Map(),
    val metadataFilters: Map[String, Any] = Map(),
    val folderIds: Set[String] = Set(),
    val personIds: Set[String] = Set(),
    // Whether the assets hold every one of `personIds` or any of them; it tells nothing apart with fewer than two people
    val peopleMatch: PeopleMatch = PeopleMatch.All,
    val albumIds: Set[String] = Set(),
    val locationIds: Set[String] = Set(),
    // The capture days and the import days the assets fall in; an asset with no capture time is in no Date Taken range
    val takenRange: Option[DateRange] = None,
    val importedRange: Option[DateRange] = None,
    val bbox: Option[BoundingBox] = None,
    // The assets one of the Triage summary's counts shows
    val subset: Option[TriageSubset] = None,
    rpp: Int = 0,
    val searchSort: List[SearchSort] = List(),
    val grouping: Option[SearchGrouping] = None,
    val cursor: Option[SearchCursor] = None,
    val resolvedText: Option[ResolvedSearchText] = None,
    // How far a first page's total counts: one past it means more. Like the two limits below, tuning carried here so a test can
    // reach it, and not part of the cursor's fingerprint.
    val totalCap: Int = Const.Search.TOTAL_CAP,
    // Off for a caller that counts the matches exactly itself (Quick view), so they are not counted twice; not in the fingerprint
    val countsTotal: Boolean = true,
    // How many hits a group of the Search text may have and still be answered from them (SearchService.resolveText)
    val textProbeLimit: Int = Const.Search.TEXT_PROBE_LIMIT,
    // How many matching assets a Describe search ranks one by one on PostgreSQL; past it, it ranks through the vector index
    val describeExactLimit: Int = Const.Search.DESCRIBE_EXACT_LIMIT,
    // Set by `SearchService` for a sorted or grouped Describe search; the cursor's fingerprint takes the description and the
    // part instead (`SearchCursor.describeFingerprint`)
    val described: Option[Described] = None)
  extends Query(params = metadataFilters, rpp = rpp):

  if sort.nonEmpty then throw IllegalArgumentException("Cannot use 'sort' in this context - use 'searchSort'")

  if searchSort.size > 1 then throw IllegalArgumentException("Only one sort currently supported")

  // A grouped page is a bounded, fully ordered slice: its group, then the sort, then the ID
  if grouping.isDefined && searchSort.isEmpty then throw IllegalArgumentException("A grouped search requires a sort")
  if grouping.isDefined && rpp < 1 then throw IllegalArgumentException("A grouped search requires a page size")

  val hasMetadataFilters: Boolean = metadataFilters.nonEmpty

  /** None without a usable term. Lazy, so a query that is only copied (a folder scope, an added filter) is not parsed. */
  lazy val textExpression: Option[SearchExpression] = text.flatMap(SearchText.parse)
  def isText: Boolean = textExpression.isDefined

  // Relevance is computed from the text's terms: without one there is nothing to rank by
  if searchSort.exists(_.isRelevance) && !isText then throw IllegalArgumentException("The Relevance sort requires Search text")

  /**
   * A query with text is resolved (`SearchService.resolveText`) before it reaches the DAO, as its folder scope is; an unresolved
   * one is an error, not a search that silently ignores the names. `text` stays as typed, for the cursor's fingerprint.
   */
  def requireResolvedText: ResolvedSearchText =
    resolvedText.getOrElse(throw IllegalStateException("The Search text was not resolved against the repository's names"))

  /** A first page does, unless the search was asked for without one (`withoutTotal`) */
  def isTotalCounted: Boolean = cursor.isEmpty && countsTotal

  val isGrouped: Boolean = grouping.isDefined
  override val isSorted: Boolean = searchSort.nonEmpty

  override def toString: String =
    s"SearchQuery(text=$text, params: $params, searchSort=${searchSort.headOption}, grouping=$grouping, cursor=${cursor.isDefined}, metadataFilters=$metadataFilters, folderIds=$folderIds, personIds=$personIds, peopleMatch=$peopleMatch, albumIds=$albumIds, locationIds=$locationIds, takenRange=$takenRange, importedRange=$importedRange, subset=$subset, bbox=$bbox, rpp=$rpp)"

  def add_metadata_filter(_filters: (String, Any)*): SearchQuery =
    copyWith(metadataFilters = metadataFilters ++ _filters)

  def withFolderIds(ids: Set[String]): SearchQuery =
    copyWith(folderIds = ids)

  def withResolvedText(resolved: ResolvedSearchText): SearchQuery =
    copyWith(resolvedText = Some(resolved))

  def withTextProbeLimit(limit: Int): SearchQuery =
    copyWith(textProbeLimit = limit)

  def withoutTotal: SearchQuery =
    copyWith(countsTotal = false)

  def describedBy(described: Described): SearchQuery =
    copyWith(described = Some(described))

  /** The search of the filters alone, whose matches a description ranks */
  def undescribed: SearchQuery =
    copyWith(described = None)

  def paged(rpp: Int, cursor: Option[SearchCursor]): SearchQuery =
    copyWith(rpp = rpp, cursor = cursor)

  override def add(_params: (String, Any)*): SearchQuery =
    copyWith(params = params ++ _params)

  // Every copy keeps every other field, so a scoped or filtered copy still describes the same grouped, sorted search
  private def copyWith(
      params: Map[String, Any] = params,
      metadataFilters: Map[String, Any] = metadataFilters,
      folderIds: Set[String] = folderIds,
      resolvedText: Option[ResolvedSearchText] = resolvedText,
      countsTotal: Boolean = countsTotal,
      textProbeLimit: Int = textProbeLimit,
      described: Option[Described] = described,
      rpp: Int = rpp,
      cursor: Option[SearchCursor] = cursor): SearchQuery =
    SearchQuery(
      text = text,
      params = params,
      metadataFilters = metadataFilters,
      folderIds = folderIds,
      personIds = personIds,
      peopleMatch = peopleMatch,
      albumIds = albumIds,
      locationIds = locationIds,
      takenRange = takenRange,
      importedRange = importedRange,
      bbox = bbox,
      subset = subset,
      rpp = rpp,
      searchSort = searchSort,
      grouping = grouping,
      cursor = cursor,
      resolvedText = resolvedText,
      totalCap = totalCap,
      countsTotal = countsTotal,
      textProbeLimit = textProbeLimit,
      describeExactLimit = describeExactLimit,
      described = described
    )

object SearchQuery:
  /** The Triage view's flags: the triaged assets outside the trash, since an asset recycled from triage keeps its triage flag */
  val TRIAGE_VIEW: Map[String, Any] = Map(FieldConst.Asset.IS_TRIAGED -> true, FieldConst.Asset.IS_RECYCLED -> false)
