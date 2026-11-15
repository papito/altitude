package altitude.core.service

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.collection.mutable

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.IllegalOperationException
import altitude.core.RequestContext
import altitude.core.SearchCursorException
import altitude.core.dao.SearchDao
import altitude.core.models.Asset
import altitude.core.models.CountedAssets
import altitude.core.models.ListedPerson
import altitude.core.models.MapBounds
import altitude.core.models.MapCells
import altitude.core.models.NamedPath
import altitude.core.models.SceneModel
import altitude.core.models.SearchFilterNames
import altitude.core.models.Settings
import altitude.core.models.UserMetadataField
import altitude.core.transactions.TransactionManager
import altitude.core.util.BoundingBox
import altitude.core.util.Described
import altitude.core.util.DescribedPart
import altitude.core.util.DescribeRanking
import altitude.core.util.GroupedSearchResult
import altitude.core.util.GroupedSearchRow
import altitude.core.util.NameKey
import altitude.core.util.ResolvedSearchGroup
import altitude.core.util.ResolvedSearchTerm
import altitude.core.util.ResolvedSearchText
import altitude.core.util.SearchCursor
import altitude.core.util.SearchExpression
import altitude.core.util.SearchName
import altitude.core.util.SearchPage
import altitude.core.util.SearchQuery
import altitude.core.util.SearchResult
import altitude.core.util.SearchRow
import altitude.core.util.SearchSort
import altitude.core.util.SearchSource
import altitude.core.util.SearchTerm
import altitude.core.util.SearchText
import altitude.core.util.SearchWords
import altitude.core.util.SortValue
import altitude.core.util.Util
import altitude.core.util.WeakerMatches

object SearchService:

  /** The map's zoom levels: a web-mercator tile pyramid, from the whole world in one tile to street level */
  val MIN_ZOOM = 0
  val MAX_ZOOM = 20

  /**
   * A cell is a quarter of a tile's width at the zoom, about 64 pixels on screen, on both axes; a zoom outside the range is
   * clamped
   */
  def cellDegrees(zoom: Int): Double = 360.0 / math.pow(2, zoom.max(MIN_ZOOM).min(MAX_ZOOM)) / 4

class SearchService(val app: Altitude):
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)
  private val searchDao: SearchDao = app.DAO.search
  protected val txManager: TransactionManager = app.txManager

  def indexAsset(asset: Asset): Unit =
    require(asset.id.isDefined, "Asset ID cannot be empty")
    logger.trace(s"Indexing asset $asset")

    txManager.withTransaction {
      val metadataFields: Map[String, UserMetadataField] = app.service.metadata.getAllFields
      searchDao.indexAsset(asset, metadataFields)
    }

  def reindexAsset(asset: Asset): Unit =
    logger.trace(s"Reindexing asset $asset")

    txManager.withTransaction {
      val metadataFields: Map[String, UserMetadataField] = app.service.metadata.getAllFields
      searchDao.reindexAsset(asset, metadataFields)
    }

  /**
   * The Search text with each term resolved against the names of the repository's people, Locations, Categories, folders and
   * albums, none of which is stored in a Search document: the candidates of every name source are read in one read-only
   * transaction and matched in memory by the readings of their [[SearchWords]], the rule a document is matched by. A Category's
   * matches become the IDs of its Locations, and a folder's the folder with every folder below it, so each source is a plain ID
   * filter for the search that follows.
   *
   * In the same transaction the resolution is probed ([[probed]]), which decides how the search matches the text.
   */
  def resolveText(expression: SearchExpression, probeLimit: Int = Const.Search.TEXT_PROBE_LIMIT): ResolvedSearchText =
    txManager.asReadOnly {
      val started = System.currentTimeMillis
      val names = searchDao.searchNames(SearchSource.names)
      // The readings of each name once, however many terms there are
      val variants = names.view.mapValues(_.map(name => name.id -> SearchWords.variants(name.name))).toMap
      val locationsByCategory = names(SearchSource.Location).groupMap(_.parentId)(_.id)
      val foldersByParent = names(SearchSource.Folder).groupMap(_.parentId)(_.id)

      /** The folders with every folder below them, level by level; a folder already found is never revisited */
      @tailrec def withDescendants(found: Set[String], level: Set[String]): Set[String] =
        val children = level.flatMap(id => foldersByParent.getOrElse(Some(id), Nil)) -- found
        if children.isEmpty then found else withDescendants(found ++ children, children)

      def resolve(term: SearchTerm): ResolvedSearchTerm =
        val hits = variants.view.mapValues(_.collect { case (id, nameVariants) if term.isIn(nameVariants) => id }.toSet).toMap
        val ids = hits
          .updated(SearchSource.Category, hits(SearchSource.Category).flatMap(id => locationsByCategory.getOrElse(Some(id), Nil)))
          .updated(SearchSource.Folder, withDescendants(hits(SearchSource.Folder), hits(SearchSource.Folder)))
        ResolvedSearchTerm(term, ids.filter((_, matching) => matching.nonEmpty))

      val resolved =
        probed(
          ResolvedSearchText(expression.groups.map(group => ResolvedSearchGroup(group.alternatives.map(resolve)))),
          probeLimit)

      logger.trace(
        s"Resolved the Search text against ${names.values.map(_.size).sum} names in ${System.currentTimeMillis - started}ms: " +
          resolved.groups
            .flatMap(_.alternatives)
            .map(
              term =>
                s"[${term.term.variants.map(_.mkString(" ")).mkString(" | ")}] " +
                  term.ids.map((source, ids) => s"$source=${ids.size}").mkString(" "))
            .mkString(", ") +
          resolved.candidates.fold(". Broad: matched over the library")(
            candidates => s". Selective: ${candidates.size} candidates"))

      resolved
    }

  /**
   * The search as every statement over it needs it, resolved against the repository as it is now: its folder as the folder with
   * every folder below it, and its Search text against the repository's names ([[resolveText]]). Both are resolved afresh on
   * every request and every page, in the caller's transaction, before the query reaches the DAO.
   */
  def withResolvedScope(query: SearchQuery): SearchQuery =
    txManager.asReadOnly {
      withResolvedText(withResolvedFolderScope(query))
    }

  /** The names a Search text matches are resolved on every request, so a rename, a merge or a move shows in the next search */
  private def withResolvedText(query: SearchQuery): SearchQuery =
    query.textExpression.fold(query)(expression => query.withResolvedText(resolveText(expression, query.textProbeLimit)))

  /** Folder membership is resolved on every request: a folder filter means the folder and all of its current descendants */
  private def withResolvedFolderScope(query: SearchQuery): SearchQuery =
    if query.folderIds.isEmpty then return query
    if query.folderIds.size > 1 then throw IllegalOperationException("Currently cannot search in multiple folders at once")

    val folderId = query.folderIds.head

    // If the root folder is selected, treat it as "no folder filter" so that
    // triaged assets (which have an empty folderId) are included — matching the default view.
    if app.service.folder.isRootFolder(folderId) then query.withFolderIds(Set.empty)
    else
      val allFolders = app.service.folder.getChildrenRecursive(rootId = folderId)
      val allFolderIds = (folderId :: allFolders.map(_.persistedId)).toSet
      query.withFolderIds(allFolderIds)

  /**
   * The folders the advanced search panel offers for the text typed so far ([[offered]]), each by its path, in path order: a
   * folder directly before the folders below it, as the folder tree lists them. The root and the recycled folders are no option.
   */
  def folderOptions(typed: String): List[NamedPath] =
    txManager.asReadOnly {
      val folders = searchDao.searchNames(Set(SearchSource.Folder))(SearchSource.Folder)
      val path = folderPaths(folders)
      offered(folders, typed)(folder => path(folder.id)).map(folder => NamedPath(folder.id, path(folder.id)))
    }

  /**
   * The Locations the advanced search panel offers for the text typed so far ([[offered]]), each with its Category's name before
   * its own, by their own names. A Category is no option, and text naming one offers none of its Locations.
   */
  def locationOptions(typed: String): List[NamedPath] =
    txManager.asReadOnly {
      val names = searchDao.searchNames(Set(SearchSource.Location, SearchSource.Category))
      val categories = names(SearchSource.Category).map(category => category.id -> category.name).toMap
      offered(names(SearchSource.Location), typed)(location => List(location.name)).map(
        location => NamedPath(location.id, location.parentId.flatMap(categories.get).toList :+ location.name))
    }

  /**
   * The people the advanced search panel offers for the text typed so far ([[offered]]), by name: the people a Search text
   * resolves names against, named and neither hidden, merged away nor a bad match, each with their cover and the number of assets
   * a search of them finds among the `counted` assets, the ones the search covers: the sorted assets, the triaged ones, or both.
   * A Person the search would find nothing for is no option, as the People tab lists none (`PersonService.getListedSearchable`).
   */
  def peopleOptions(typed: String, counted: CountedAssets): List[ListedPerson] =
    txManager.asReadOnly {
      val listed =
        app.service.person.getListedSearchable(counted).map(listed => listed.person.persistedId -> listed).toMap
      // A searchable Person is named
      val names = listed.values.toSeq.map(listed => SearchName(listed.person.persistedId, listed.person.name.get))
      offered(names, typed)(person => List(person.name)).map(person => listed(person.id))
    }

  /**
   * What the IDs of a search name in the repository as it is now, each read by its ID in one snapshot, for the chips of the
   * advanced search panel and of a filtered search's results title. The folder is any but the root, which is the whole
   * repository, and a recycled one; a person is any live one, hidden or with no name included, since a search can be scoped to
   * them; a Location is no Category. An ID that names none of these (one that no longer exists, another repository's) has no
   * entry.
   */
  def filterNames(folderId: Option[String], personIds: List[String], locationIds: List[String]): SearchFilterNames =
    txManager.asReadOnly {
      val folders = app.service.folder
      // The path ends with the folder itself; an ID that names no folder has none
      val folderPath = folderId.filterNot(folders.isRootFolder).map(folders.getPath).filter(_.lastOption.exists(!_.isRecycled))
      val people = app.service.person.getLive(personIds.toSet).map(person => person.persistedId -> person).toMap
      val locations =
        app.service.location.getLocationsByIds(locationIds.toSet).map(location => location.persistedId -> location).toMap

      val found = SearchFilterNames(
        folder = folderPath.map(path => NamedPath(path.last.persistedId, path.map(_.name))),
        people = personIds.flatMap(people.get),
        locations = locationIds
          .flatMap(locations.get)
          .map(location => NamedPath(location.persistedId, location.categoryName.toList :+ location.name))
      )
      logger.debug(
        s"Search filters named: ${found.folder.size} of ${folderId.size} folders, ${found.people.size} of " +
          s"${personIds.size} people and ${found.locations.size} of ${locationIds.size} Locations")
      found
    }

  /**
   * The names that have the typed text, in the order of their `sortKey`s (names, compared by their name keys, segment by
   * segment), the first `Const.Search.MAX_FILTER_OPTIONS` of them. The text is read as a Search text is and matched as one
   * matches a name ([[SearchTerm.isIn]] over the readings of the name's [[SearchWords]]): every group of its terms must be in the
   * name itself, an excluded term must not, and text with no usable term, nothing typed included, is in every name.
   *
   * Each name's key is built once, and the first of them are picked without sorting the rest ([[firstOf]]), so a Library's whole
   * folder tree, which nothing typed matches, costs one pass over it.
   */
  private def offered(names: Seq[SearchName], typed: String)(sortKey: SearchName => List[String]): List[SearchName] =
    val started = System.currentTimeMillis
    val matching = SearchText.parse(typed).fold(names) {
      expression =>
        names.filter {
          name =>
            val readings = SearchWords.variants(name.name)
            expression.groups.forall(_.alternatives.exists(term => term.isIn(readings) != term.isExcluded))
        }
    }
    // A segment's name key is computed once, however many paths it is a segment of
    val segmentKeys = mutable.Map.empty[String, String]
    val keyed =
      matching.map(name => sortKey(name).map(segment => segmentKeys.getOrElseUpdate(segment, NameKey.of(segment))) -> name)
    val byKey = Ordering.by[(List[String], SearchName), List[String]](_._1)(using Ordering.Implicits.seqOrdering)
    val options = firstOf(keyed, Const.Search.MAX_FILTER_OPTIONS)(using byKey).map(_._2)
    logger.trace(
      s"Advanced search options: ${options.size} offered of ${matching.size} matching among ${names.size} names, " +
        s"in ${System.currentTimeMillis - started}ms")
    options

  /**
   * The first `count` of `items` in their order, as sorting them all and taking that many gives, items that compare equal in the
   * order they came in: one pass that keeps the first so far in a heap with the last of them on top, so the rest are never sorted
   */
  private def firstOf[A](items: Seq[A], count: Int)(using order: Ordering[A]): List[A] =
    // An item with its place among the items, which orders the ones that compare equal
    val byOrderThenPlace = Ordering.Tuple2(using order, Ordering.Int)
    val first = mutable.PriorityQueue.empty[(A, Int)](using byOrderThenPlace)
    items.iterator.zipWithIndex.foreach {
      placed =>
        if first.size < count then first.enqueue(placed)
        else if byOrderThenPlace.lt(placed, first.head) then
          first.dequeue(): Unit
          first.enqueue(placed)
    }
    first.dequeueAll.reverseIterator.map(_._1).toList

  /** Each folder's path by its ID: the names from its top-level ancestor down to its own, the root, which is no name, left out */
  private def folderPaths(folders: Seq[SearchName]): String => List[String] =
    val byId = folders.map(folder => folder.id -> folder).toMap

    def path(id: String): List[String] =
      byId.get(id).fold(Nil)(folder => folder.parentId.fold(Nil)(path) :+ folder.name)

    path

  /**
   * The resolution with what its probe found: each positive group whose hits number at most the limit is complete, and the
   * candidates are the intersection of the complete groups' hits. Text with no complete group, or with no positive group at all,
   * which is not probed, is left broad.
   */
  private def probed(text: ResolvedSearchText, limit: Int): ResolvedSearchText =
    val positive = text.groups.indices.filter(text.groups(_).isPositive)
    if positive.isEmpty then return text

    val hits = searchDao.probeText(text, limit)
    val complete = positive.filter(index => hits.getOrElse(index, Nil).size <= limit).toSet
    ResolvedSearchText(
      groups = text.groups.zipWithIndex.map((group, index) => group.copy(isComplete = complete.contains(index))),
      candidates = Option.when(complete.nonEmpty)(complete.map(index => hits.getOrElse(index, Nil).toSet).reduce(_ intersect _))
    )

  /**
   * A flat page: the DAO returns the rows, the continuation cursor is assembled here ([[cursorAt]]). Sorted by capture time the
   * search is ordered by day first, and the cursor carries the last image's day.
   */
  def search(query: SearchQuery, scopeFingerprint: String): SearchResult =
    val started = System.currentTimeMillis
    val page = txManager.asReadOnly {
      searchDao.search(query)
    }

    val nextCursor = Option.when(page.hasMore) {
      val last = page.rows.last
      cursorAt(query, scopeFingerprint, last.day.map(_.toString), None, last.asset, last.sortValue, last.secondSortValue)
    }
    val result = SearchResult(page.rows.map(_.asset), page.total, nextCursor, query.rpp, query.searchSort)

    logger.trace(
      s"Search page: ${result.records.length} assets" +
        result.total.map(total => s" of $total matching").getOrElse(", total not counted") +
        s", in ${System.currentTimeMillis - started}ms")
    result

  /**
   * One page of a Describe search on the flat grid: the still images of the context repository that `query` matches and that look
   * like `description`, the words that say what a photo shows. The query is the search's filters, its view's flags, its page size
   * and its cursor, with no grouping ([[describeGrouped]] reads a grouped one). The filters decide which assets are ranked and
   * nothing of where they stand; with no filter the search ranks the whole view. The page is the `query.rpp` assets after the
   * cursor, or the first ones without it, and a first page carries the total.
   *
   * With no sort the order is likeness, most alike first, which the query does not name: the assets are ranked by the cosine
   * distance of their SigLIP 2 Scene embedding from the description's, in the database (`SearchDao.describe`), and the page that
   * holds the first weaker match says where (`SearchResult.weaker`). With a sort the matches are in two parts, each in the sort's
   * order ([[describedInParts]]).
   *
   * The description is embedded by SigLIP 2's text tower (`DescribeEmbedder`), before the transaction, since a pass holds no
   * connection. Inside it the query is resolved as any search's is ([[withResolvedScope]]). Three Settings, read from one
   * snapshot ([[rankingBy]]), say where the results end, whatever the filters, and where the weaker matches begin. The cursor's
   * fingerprint is the description with what identifies the query to a cursor (`SearchCursor.describeFingerprint`): a cursor of
   * any other search is refused (`SearchCursorException`). Every page ranks again, so the results are live, as any search's are.
   *
   * An embedder that embeds nothing, a test instance's whose suite has not turned the model on, finds nothing.
   */
  def describe(description: String, query: SearchQuery): SearchResult =
    require(query.rpp >= 1, "A page of a Describe search holds at least one asset")
    require(!query.isGrouped, "A grouped Describe search is read as one")
    require(!query.searchSort.exists(_.isSimilarity), "Likeness is the order of a Describe search that names no sort")
    val started = System.currentTimeMillis

    val result =
      if query.isSorted then
        val page = describedInParts[SearchRow](description, query)(
          flatPart,
          (part, scope, last) =>
            cursorAt(part, scope, last.day.map(_.toString), None, last.asset, last.sortValue, last.secondSortValue)
        )
        SearchResult(
          page.rows.map(_.asset),
          page.total,
          page.nextCursor,
          query.rpp,
          query.searchSort,
          page.weaker.map((index, count) => WeakerMatches(index, count)))
      else
        val scope =
          SearchCursor.describeFingerprint(description, query, RequestContext.getRepository.persistedId, app.dataSourceType)
        query.cursor.foreach(_.requireScope(scope))

        val page = rankingBy(description).fold(SearchPage(Nil, Option.when(query.isTotalCounted)(0), hasMore = false)) {
          ranking => txManager.withVectorSearch(ranking.limit)(searchDao.describe(withResolvedScope(query), ranking))
        }
        val nextCursor = Option.when(page.hasMore) {
          val last = page.rows.last
          SearchCursor(key = None, groupId = None, sortValue = last.sortValue, id = last.asset.persistedId, scope = scope)
        }
        SearchResult(page.rows.map(_.asset), page.total, nextCursor, query.rpp, List(SearchSort.Similarity), page.weaker)

    logDescribed(description, query, result.records.length, result.total, result.weaker, started)
    result

  /**
   * One page of a grouped Describe search: the matches of [[describe]] in two parts, each grouped and sorted as the query says
   * ([[describedInParts]]), so a group with matches in both parts is in the page twice, once in each. Under the similarity sort a
   * group's assets are most alike first. The first weaker match's group is named by `GroupedSearchResult.weaker`, its place among
   * the page's groups with the count of the weaker matches.
   */
  def describeGrouped(description: String, query: SearchQuery): GroupedSearchResult =
    require(query.isGrouped, "A flat Describe search is read as one")
    val started = System.currentTimeMillis

    val page = describedInParts[GroupedSearchRow](description, query)(
      groupedPart,
      (part, scope, last) =>
        cursorAt(part, scope, last.group.cursorKey, last.group.cursorGroupId, last.asset, last.sortValue, last.secondSortValue)
    )

    // Each part's rows are grouped apart: a group in both parts is two groups of the page
    val (nearer, weaker) = page.rows.splitAt(page.weaker.fold(page.rows.length)(_._1))
    val nearerGroups = GroupedSearchResult.groupsOf(nearer)
    val groups = nearerGroups ++ GroupedSearchResult.groupsOf(weaker)
    val result = GroupedSearchResult(
      groups = groups,
      total = page.total,
      grouping = query.grouping.get,
      sort = query.searchSort.head,
      nextCursor = page.nextCursor,
      // The cursor's group is continued only by rows of the part the cursor is in
      continuesGroup = page.continuesCursor && query.cursor.exists(cursor => groups.headOption.exists(_.key.continues(cursor))),
      weaker = page.weaker.map((_, count) => WeakerMatches(nearerGroups.length, count))
    )

    logDescribed(description, query, result.assets.length, result.total, result.weaker, started)
    result

  /** The rows of one part's page, whether the part has more after them and, on a page that counts it, the part's total */
  private case class PartPage[Row](rows: List[Row], total: Option[Int], hasMore: Boolean)

  /** A flat page of a search among one part of a description's matches, read in the transaction of [[describedInParts]] */
  private def flatPart(part: SearchQuery): PartPage[SearchRow] =
    val page = searchDao.search(part)
    PartPage(page.rows, page.total, page.hasMore)

  /** A grouped page of a search among one part of a description's matches, read in the transaction of [[describedInParts]] */
  private def groupedPart(part: SearchQuery): PartPage[GroupedSearchRow] =
    val page = searchDao.searchGrouped(part)
    PartPage(page.rows, page.total, page.hasMore)

  /**
   * A page of a Describe search in two parts. `weaker` is, on the page that holds the first weaker match, how many of the page's
   * rows come before it and how many weaker matches the search has; `continuesCursor` whether the page's first row follows the
   * cursor's in the cursor's own part.
   */
  private case class DescribedPage[Row](
      rows: List[Row],
      total: Option[Int],
      nextCursor: Option[SearchCursor],
      weaker: Option[(Int, Int)],
      continuesCursor: Boolean)

  /**
   * A page of a Describe search that is sorted or grouped. Its matches are in two parts, the ones above the line of the weaker
   * matches and then the weaker ones (`DescribedPart`), and each part is a search of its own among the description's ranked
   * matches of that part (`SearchQuery.described`), ordered and grouped as `query` says: `read` reads a page of such a search and
   * `cursorAfter` makes the cursor that continues one after a row.
   *
   * A cursor says which part it continues by its fingerprint, the description's with the part
   * (`SearchCursor.describeFingerprint`); a cursor of neither is refused. A page of the first part that ends it goes on with the
   * weaker matches from their start, as many as it has room for, and says where they begin; a page with no room left still looks
   * for one, and when there is one its cursor is the last row's of the first part, which the next page, finding nothing after it,
   * continues from into the weaker matches. So a part's pages are continued by that part's own cursors, and no page names a place
   * in both.
   *
   * A first page's total is the two parts' together: the first part's, and the weaker matches', counted when the page does not
   * reach them. The ranking is read as the engine reads it for the query's matches once, for every statement of the page
   * (`SearchDao.rankingFor`), each of which ranks again.
   */
  private def describedInParts[Row](description: String, query: SearchQuery)(
      read: SearchQuery => PartPage[Row],
      cursorAfter: (SearchQuery, String, Row) => SearchCursor): DescribedPage[Row] =
    def scopeOf(part: DescribedPart): String =
      SearchCursor.describeFingerprint(
        description,
        query,
        RequestContext.getRepository.persistedId,
        app.dataSourceType,
        Some(part))
    val continued = query.cursor.map {
      cursor =>
        DescribedPart.values
          .find(part => cursor.scope == scopeOf(part))
          .getOrElse(throw SearchCursorException("The cursor does not belong to this search"))
    }

    rankingBy(description).fold(DescribedPage[Row](Nil, Option.when(query.isTotalCounted)(0), None, None, false)) {
      embedded =>
        txManager.withVectorSearch(embedded.limit) {
          val resolved = withResolvedScope(query)
          val ranking = searchDao.rankingFor(resolved, embedded)
          def among(part: DescribedPart): SearchQuery = resolved.describedBy(Described(ranking, part))
          def continuing(part: DescribedPart, page: PartPage[Row]): Option[SearchCursor] =
            Option.when(page.hasMore)(cursorAfter(among(part), scopeOf(part), page.rows.last))

          if continued.contains(DescribedPart.Weaker) then
            val weaker = read(among(DescribedPart.Weaker))
            DescribedPage(
              weaker.rows,
              None,
              continuing(DescribedPart.Weaker, weaker),
              None,
              continuesCursor = weaker.rows.nonEmpty)
          else
            val nearer = read(among(DescribedPart.Nearer))
            if nearer.hasMore then
              val total = nearer.total.map(_ + searchDao.count(among(DescribedPart.Weaker)))
              DescribedPage(nearer.rows, total, continuing(DescribedPart.Nearer, nearer), None, continuesCursor = true)
            else
              // The first part ends on this page: the weaker matches follow from their start, counted, as a first page is
              val room = query.rpp - nearer.rows.length
              val weaker = read(among(DescribedPart.Weaker).paged(room.max(1), None))
              val shown = if room > 0 then weaker.rows else Nil
              DescribedPage(
                rows = nearer.rows ++ shown,
                total = nearer.total.map(_ + weaker.total.getOrElse(0)),
                nextCursor =
                  if room > 0 then continuing(DescribedPart.Weaker, weaker)
                  else
                    Option.when(weaker.rows.nonEmpty)(
                      cursorAfter(among(DescribedPart.Nearer), scopeOf(DescribedPart.Nearer), nearer.rows.last))
                ,
                weaker = Option.when(shown.nonEmpty)((nearer.rows.length, weaker.total.getOrElse(0))),
                continuesCursor = nearer.rows.nonEmpty
              )
        }
    }

  /**
   * What a description ranks by, from one snapshot of the Settings: its embedding; the Describe match threshold, the least
   * probability by the model that an image matches, as the least cosine (`SceneModel.siglip2Cosine`); the Describe result limit,
   * the most assets ranked; and the Describe weaker matches ratio. None when the embedder embeds nothing, a test instance's whose
   * suite has not turned the model on.
   */
  private def rankingBy(description: String): Option[DescribeRanking] =
    val settings = app.settings.current
    app.describeEmbedder
      .embed(description)
      .map(
        DescribeRanking(
          _,
          SceneModel.siglip2Cosine(settings.searchDescribeMinProbability),
          settings.searchDescribeMaxResults,
          settings.searchDescribeWeakerMatchRatio))

  /** A page of a Describe search, logged with its description: escaped, since one is typed, line breaks and all */
  private def logDescribed(
      description: String,
      query: SearchQuery,
      assets: Int,
      total: Option[Int],
      weaker: Option[WeakerMatches],
      started: Long): Unit =
    val settings = app.settings.current
    logger.debug(
      s"Describe search [${Util.printable(description, Const.Search.MAX_DESCRIPTION_LENGTH)}]" +
        query.grouping.fold("")(grouping => s" grouped by ${grouping.by}") +
        query.searchSort.headOption.fold("")(sort => s" sorted $sort") +
        s": $assets assets" +
        total.map(total => s" of $total").getOrElse(", total not counted") +
        weaker.map(weaker => s", the ${weaker.count} weaker matches from place ${weaker.index} of the page").getOrElse("") +
        s" at a probability of ${settings.value(Settings.SEARCH_DESCRIBE_MIN_PROBABILITY)} or more, " +
        s"limit ${settings.searchDescribeMaxResults}, in ${System.currentTimeMillis - started}ms")

  /**
   * The cursor that continues a search after the image at this position. It carries the scope fingerprint of the search as
   * requested and, under a scored sort, the Relevance or the similarity, which orders by the capture time next, that too.
   */
  private def cursorAt(
      query: SearchQuery,
      scopeFingerprint: String,
      key: Option[String],
      groupId: Option[String],
      asset: Asset,
      sortValue: SortValue,
      secondSortValue: SortValue): SearchCursor =
    SearchCursor(
      key = key,
      groupId = groupId,
      sortValue = sortValue,
      id = asset.persistedId,
      scope = scopeFingerprint,
      secondSortValue = Option.when(query.searchSort.exists(_.isScored))(secondSortValue)
    )

  def count(query: SearchQuery): Int =
    val started = System.currentTimeMillis
    val count = txManager.asReadOnly {
      searchDao.count(query)
    }
    logger.trace(s"Counted $count matching assets in ${System.currentTimeMillis - started}ms")
    count

  def countByFolder(): Map[String, Int] =
    txManager.asReadOnly {
      searchDao.countByFolder()
    }

  def cappedCount(query: SearchQuery): Int =
    val started = System.currentTimeMillis
    val count = txManager.asReadOnly {
      searchDao.cappedCount(query)
    }
    logger.trace(s"Counted $count matching assets, capped at ${query.totalCap}, in ${System.currentTimeMillis - started}ms")
    count

  /** What the map draws for a viewport at a zoom: the cells over the plotted points in the box, and the Locations pinned in it */
  def mapCells(query: SearchQuery, bbox: BoundingBox, zoom: Int): MapCells =
    val started = System.currentTimeMillis
    // Both aggregates read one snapshot
    val result = txManager.asReadOnly {
      MapCells(
        cells = searchDao.mapCells(query, bbox, SearchService.cellDegrees(zoom)),
        locations = searchDao.mapLocations(query, bbox))
    }
    logger.trace(
      s"Map at zoom $zoom in $bbox: ${result.cells.length} cells, ${result.locations.length} Locations, " +
        s"in ${System.currentTimeMillis - started}ms")
    result

  /** The box around every point the search plots, for fitting the map to a result; nothing when nothing is plotted */
  def mapBounds(query: SearchQuery): Option[MapBounds] =
    val started = System.currentTimeMillis
    val bounds = txManager.asReadOnly {
      searchDao.mapBounds(query)
    }
    logger.trace(s"Map bounds ${bounds.getOrElse("of nothing plotted")}, in ${System.currentTimeMillis - started}ms")
    bounds

  /**
   * A grouped page: the DAO returns the rows and counts, the groups and the continuation cursor are assembled here. The cursor
   * points at the last returned image ([[cursorAt]]).
   */
  def searchGrouped(query: SearchQuery, scopeFingerprint: String): GroupedSearchResult =
    val started = System.currentTimeMillis
    val page = txManager.asReadOnly {
      searchDao.searchGrouped(query)
    }

    val nextCursor = Option.when(page.hasMore) {
      val last = page.rows.last
      cursorAt(
        query,
        scopeFingerprint,
        last.group.cursorKey,
        last.group.cursorGroupId,
        last.asset,
        last.sortValue,
        last.secondSortValue)
    }

    val groups = GroupedSearchResult.groupsOf(page.rows)
    val result = GroupedSearchResult(
      groups = groups,
      total = page.total,
      grouping = query.grouping.get,
      sort = query.searchSort.head,
      nextCursor = nextCursor,
      continuesGroup = query.cursor.exists(cursor => groups.headOption.exists(_.key.continues(cursor)))
    )

    logger.trace(
      s"Grouped search by ${result.grouping.by} ${result.grouping.direction}, sorted ${result.sort}: " +
        s"${result.assets.length} images in ${result.groups.length} groups" +
        result.total.map(total => s" of $total matching").getOrElse(", total not counted") +
        s", in ${System.currentTimeMillis - started}ms")

    result

  /**
   * Indexes a value just added to the asset, of any field type: a metadata parameter when the type is faceted, and the asset's
   * Search document rewritten from the asset as given, which must already carry the value
   */
  def addMetadataValue(asset: Asset, field: UserMetadataField, value: String): Unit =
    txManager.withTransaction {
      searchDao.addMetadataValue(asset, field, value)
    }
