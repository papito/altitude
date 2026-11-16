package altitude.core.dao.jdbc

import com.typesafe.config.Config
import java.sql.PreparedStatement
import java.sql.Types
import java.time.LocalDate
import java.time.LocalDateTime
import org.apache.commons.dbutils.QueryRunner
import scalasql.Sc
import scalasql.core.Expr
import scalasql.core.Queryable
import scalasql.core.SqlStr
import scalasql.core.TypeMapper
import scalasql.query.Select

import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.dao.sql.Db
import altitude.core.dao.sql.search.SearchDialect
import altitude.core.dao.sql.search.SearchQueries
import altitude.core.dao.sql.tables.AlbumRow
import altitude.core.dao.sql.tables.AssetRow
import altitude.core.dao.sql.tables.FolderRow
import altitude.core.dao.sql.tables.LocationRow
import altitude.core.models._
import altitude.core.util.BoundingBox
import altitude.core.util.DescribeRanking
import altitude.core.util.GroupBy
import altitude.core.util.GroupedSearchPage
import altitude.core.util.GroupedSearchRow
import altitude.core.util.NameKey
import altitude.core.util.ResolvedSearchText
import altitude.core.util.SearchCursor
import altitude.core.util.SearchGroupKey
import altitude.core.util.SearchName
import altitude.core.util.SearchPage
import altitude.core.util.SearchQuery
import altitude.core.util.SearchRow
import altitude.core.util.SearchSource
import altitude.core.util.SearchWords
import altitude.core.util.SortValue
import altitude.core.util.WeakerMatches

object SearchDao:
  /** The field types with a value column in `metadata_parameter`; other types are searchable through the Search document only */
  private val FACETED_FIELD_TYPES: Set[FieldType] = Set(FieldType.KEYWORD, FieldType.NUMBER, FieldType.BOOL)

  private val VALUE_INSERT_SQL: String = s"""
            INSERT INTO metadata_parameter (
                        ${FieldConst.REPO_ID}, ${FieldConst.SearchToken.ASSET_ID},
                        ${FieldConst.SearchToken.FIELD_ID},
                        ${FieldConst.SearchToken.FIELD_VALUE_KW},
                        ${FieldConst.SearchToken.FIELD_VALUE_NUM},
                        ${FieldConst.SearchToken.FIELD_VALUE_BOOL})
                 VALUES (?, ?, ?, ?, ?, ?)
            """

  /**
   * One document per asset, by the table's unique index. An unchanged body is not rewritten, which on SQLite also spares the
   * full-text entry its update trigger.
   */
  private val DOCUMENT_UPSERT_SQL: String = s"""
            INSERT INTO search_document (${FieldConst.REPO_ID}, ${FieldConst.SearchToken.ASSET_ID}, body)
                 VALUES (?, ?, ?)
            ON CONFLICT (${FieldConst.REPO_ID}, ${FieldConst.SearchToken.ASSET_ID})
            DO UPDATE SET body = excluded.body WHERE search_document.body <> excluded.body
            """
abstract class SearchDao(override val config: Config) extends AssetDao(config) with altitude.core.dao.SearchDao:
  /** What this engine says differently in a search */
  protected def searchDialect: SearchDialect

  /**
   * Ranks exactly, comparing each match's embedding with the description's, which every engine can; an engine with a vector index
   * chooses here between that and its index
   */
  override def rankingFor(query: SearchQuery, ranking: DescribeRanking): DescribeRanking =
    ranking.copy(throughIndex = false)

  /** One statement for both engines (`SearchQueries.described`) around the engine's ranked rows, paged as every other search */
  override def describe(query: SearchQuery, ranking: DescribeRanking): SearchPage =
    import dialect.*

    val isTotalCounted = query.isTotalCounted
    if matchesNothing(query) then return SearchPage(Nil, Option.when(isTotalCounted)(0), hasMore = false)

    val statement =
      SearchQueries.described(searchDialect, query, RequestContext.getRepository.persistedId, rankingFor(query, ranking))
    // One row past the page says whether another follows
    val candidates = readPage[(AssetRow[Sc], Double, Double, Int)](statement, isTotalCounted)
    val rows = candidates.take(query.rpp).toList.map(_._1)

    val page = SearchPage(
      rows = rows.map {
        (asset, distance, _, _) =>
          SearchRow(toModel(asset), day = None, sortValue = SortValue.Real(distance), secondSortValue = SortValue.Null)
      },
      total = Option.when(isTotalCounted)(candidates.headOption.flatMap(_._2).getOrElse(0)),
      hasMore = candidates.length > query.rpp,
      weaker = rows.headOption.flatMap {
        (_, _, weakerUnder, weakerCount) => weakerMatches(rows.map(_._2), query.cursor, weakerUnder, weakerCount)
      }
    )
    logPage(page.rows.length, page.total, page.hasMore)
    page

  /**
   * Where the weaker matches begin on a Describe page (`distances` nearest first): at the first row with a cosine under
   * `weakerUnder` whose previous row (on the page, or the cursor's) is not under it, so only the page that crosses the line names
   * it. A non-positive line, from a ratio of 0 or a nearest row with no positive cosine, is none (`SearchQueries.IS_WEAKER`).
   */
  private def weakerMatches(
      distances: List[Double],
      cursor: Option[SearchCursor],
      weakerUnder: Double,
      count: Int): Option[WeakerMatches] =
    // Compares as the statement does, so a row is on the same side of the line here and in the count
    def isWeaker(distance: Double): Boolean = weakerUnder > 0 && 1 - distance < weakerUnder
    val index = distances.indexWhere(isWeaker)
    val followsNearer = index > 0 || cursor.map(_.sortValue).exists {
      case SortValue.Real(distance) => !isWeaker(distance)
      case _ => false
    }
    Option.when(index >= 0 && followsNearer)(WeakerMatches(index, count))

  override def search(query: SearchQuery): SearchPage =
    import dialect.*
    given TypeMapper[SortValue] = searchDialect.sortValueMapper

    val isTotalCounted = query.isTotalCounted
    if matchesNothing(query) then return SearchPage(Nil, Option.when(isTotalCounted)(0), hasMore = false)

    val statement = SearchQueries.flat(searchDialect, query, RequestContext.getRepository.persistedId)
    // The last column counts the candidates, which run one row past the page
    val page = readPage[(AssetRow[Sc], Option[LocalDate], SortValue, SortValue, Int)](statement, isTotalCounted)
    val total = Option.when(isTotalCounted)(page.headOption.flatMap(_._2).getOrElse(0))
    val hasMore = page.headOption.exists(_._1._5 > query.rpp)
    logPage(page.length, total, hasMore)

    SearchPage(
      rows = page.map {
        case ((asset, day, sortValue, secondSortValue, _), _) =>
          SearchRow(toModel(asset), day, sortValue, secondSortValue)
      }.toList,
      total = total,
      hasMore = hasMore
    )

  override def count(query: SearchQuery): Int =
    import dialect.*

    if matchesNothing(query) then return 0
    val statement = SearchQueries.count(searchDialect, query, RequestContext.getRepository.persistedId)
    val count = Db.read(dialect)(_.runSql[Int](statement)).head
    logger.trace(s"Counted $count records")
    count

  override def countByFolder(): Map[String, Int] =

    val counts = Db.read(dialect)(_.run(SearchQueries.folderCounts(searchDialect, RequestContext.getRepository.persistedId)))
    logger.trace(s"Counted the assets of ${counts.length} folders")
    // The assets in no folder (NULL: triaged ones) are no folder's
    counts.collect { case (Some(folderId), count) => folderId -> count }.toMap

  override def cappedCount(query: SearchQuery): Int =
    import dialect.*

    if matchesNothing(query) then return 0
    val statement = SearchQueries.cappedCount(searchDialect, query, RequestContext.getRepository.persistedId)
    val count = Db.read(dialect)(_.runSql[Int](statement)).head
    logger.trace(s"Counted $count records, capped at ${query.totalCap}")
    count

  override def searchGrouped(query: SearchQuery): GroupedSearchPage =
    import dialect.*
    given TypeMapper[SortValue] = searchDialect.sortValueMapper

    val repositoryId = RequestContext.getRepository.persistedId
    val grouping = query.grouping.getOrElse(throw IllegalArgumentException("A grouped search needs a grouping"))
    val isTotalCounted = query.isTotalCounted
    if matchesNothing(query) then return GroupedSearchPage(Nil, Option.when(isTotalCounted)(0), hasMore = false)

    val page: IndexedSeq[(GroupedSearchRow, Int, Option[Int])] = grouping.by match
      case GroupBy.DateTaken =>
        val statement = SearchQueries.grouped(searchDialect, query, repositoryId)
        readPage[(AssetRow[Sc], Option[LocalDate], SortValue, SortValue, Int, Int)](statement, isTotalCounted).map {
          case ((asset, day, sortValue, secondSortValue, groupTotal, candidates), total) =>
            val row = GroupedSearchRow(toModel(asset), SearchGroupKey.Day(day), sortValue, secondSortValue, groupTotal)
            (row, candidates, total)
        }
      case GroupBy.Location =>
        val statement = SearchQueries.groupedByLocation(searchDialect, query, repositoryId)
        readPage[(AssetRow[Sc], Option[String], Option[String], Option[String], Option[String], SortValue, SortValue, Int, Int)](
          statement,
          isTotalCounted).map {
          case ((asset, locationId, pathKey, name, categoryName, sortValue, secondSortValue, groupTotal, candidates), total) =>
            val group = SearchGroupKey.Location(id = locationId, pathKey = pathKey, name = name, categoryName = categoryName)
            (GroupedSearchRow(toModel(asset), group, sortValue, secondSortValue, groupTotal), candidates, total)
        }
      case GroupBy.Sequence =>
        val statement = SearchQueries.groupedBySequence(searchDialect, query, repositoryId)
        readPage[(AssetRow[Sc], Option[String], Option[LocalDateTime], SortValue, SortValue, Int, Int)](statement, isTotalCounted)
          .map {
            case ((asset, sequenceId, firstCapturedAt, sortValue, secondSortValue, groupTotal, candidates), total) =>
              val group = SearchGroupKey.Sequence(id = sequenceId, firstCapturedAt = firstCapturedAt)
              (GroupedSearchRow(toModel(asset), group, sortValue, secondSortValue, groupTotal), candidates, total)
          }

    val total = Option.when(isTotalCounted)(page.headOption.flatMap(_._3).getOrElse(0))
    val hasMore = page.headOption.exists(_._2 > query.rpp)
    logPage(page.length, total, hasMore)

    GroupedSearchPage(rows = page.map(_._1).toList, total = total, hasMore = hasMore)

  override def mapCells(query: SearchQuery, bbox: BoundingBox, cellDegrees: Double): List[MapCell] =
    import dialect.*

    if matchesNothing(query) then return Nil
    val statement = SearchQueries.mapCells(searchDialect, query, RequestContext.getRepository.persistedId, bbox, cellDegrees)
    val cells = Db.read(dialect)(_.runSql[(Int, Double, Double, String, Int)](statement)).map(MapCell.apply).toList
    logger.trace(s"Map cells of $cellDegrees degrees in $bbox: ${cells.length} cells over ${cells.map(_.count).sum} points")
    cells

  override def mapLocations(query: SearchQuery, bbox: BoundingBox): List[MapLocation] =
    import dialect.*

    if matchesNothing(query) then return Nil
    val statement = SearchQueries.mapLocations(searchDialect, query, RequestContext.getRepository.persistedId, bbox)
    val locations =
      Db.read(dialect)(_.runSql[(String, String, Option[String], Option[Double], Option[Double], Int)](statement)).toList.map {
        // The kind filter guarantees a pin; a Location row without one cannot exist under the schema's CHECK
        case (id, name, categoryName, latitude, longitude, count) =>
          MapLocation(id, name, categoryName, latitude.get, longitude.get, count)
      }
    logger.trace(s"Map Locations in $bbox: ${locations.length} pinned")
    locations

  override def mapBounds(query: SearchQuery): Option[MapBounds] =
    import dialect.*

    if matchesNothing(query) then return None
    val statement = SearchQueries.mapBounds(searchDialect, query, RequestContext.getRepository.persistedId)
    val (south, north, west, east, count) =
      Db.read(dialect)(_.runSql[(Option[Double], Option[Double], Option[Double], Option[Double], Int)](statement)).head
    logger.trace(s"Map bounds over $count points")
    Option.when(count > 0)(MapBounds(south = south.get, west = west.get, north = north.get, east = east.get, count = count))

  override def searchNames(sources: Set[SearchSource]): Map[SearchSource, Seq[SearchName]] =
    import dialect.*

    val repository = RequestContext.getRepository
    val repositoryId = repository.persistedId

    // Categories and Locations are one table, told apart by kind: one read gives both, and either
    lazy val (categoryRows, locationRows) =
      val locations = LocationRow.select
        .filter(location => location.repositoryId `=` repositoryId)
        .map(location => (location.id, location.name, location.kind, location.categoryId))
      Db.read(dialect)(_.run(locations)).partition((_, _, kind, _) => kind == LocationKind.Category.dbValue)

    def candidates(source: SearchSource): Seq[SearchName] = source match
      case SearchSource.Person =>
        val people = SearchQueries.searchablePeople(searchDialect, repositoryId)
        Db.read(dialect)(_.run(people)).map((id, name) => SearchName(id, name))
      case SearchSource.Location => locationRows.map((id, name, _, categoryId) => SearchName(id, name, categoryId))
      case SearchSource.Category => categoryRows.map((id, name, _, _) => SearchName(id, name))
      case SearchSource.Folder =>
        val folders = FolderRow.select
          .filter {
            folder =>
              (folder.repositoryId `=` repositoryId) && (folder.isRecycled `=` false) && (folder.id <> repository.rootFolderId)
          }
          .map(folder => (folder.id, folder.name, folder.parentId))
        Db.read(dialect)(_.run(folders)).map((id, name, parentId) => SearchName(id, name, Some(parentId)))
      case SearchSource.Album =>
        val albums = AlbumRow.select.filter(album => album.repositoryId `=` repositoryId).map(album => (album.id, album.name))
        Db.read(dialect)(_.run(albums)).map((id, name) => SearchName(id, name))
      case SearchSource.Document => throw IllegalArgumentException("The Search document is no name source")

    // In the sources' own order, so one set of sources is one sequence of reads
    val names = SearchSource.values.filter(sources.contains).map(source => source -> candidates(source)).toMap
    logger.trace(s"Search name candidates: ${names.map((source, candidates) => s"$source=${candidates.size}").mkString(", ")}")
    names

  override def probeText(text: ResolvedSearchText, limit: Int): Map[Int, Seq[String]] =
    import dialect.*

    val hits = SearchQueries
      .textProbe(searchDialect, text, RequestContext.getRepository.persistedId, limit)
      .fold(Map.empty)(statement => Db.read(dialect)(_.runSql[(Int, String)](statement)).groupMap(_._1)(_._2))
    val hitsByGroup = hits.toSeq.sortBy(_._1).map((group, ids) => s"$group=${ids.size}").mkString(", ")
    logger.trace(s"Probed the Search text, hits by group: $hitsByGroup")
    hits

  /** The Search text's probe left a positive group with no hit, so no asset can match and the library is not read */
  private def matchesNothing(query: SearchQuery): Boolean = query.resolvedText.exists(_.candidates.exists(_.isEmpty))

  private def logPage(numOfRows: Int, total: Option[Int], hasMore: Boolean): Unit =
    logger.trace(s"Retrieved [$numOfRows] records, total ${total.getOrElse("not counted")}, more: $hasMore")

  /**
   * Runs a page's statement. A page that counts the total carries it at the end of every row (no other page selects it); an empty
   * page has no rows to carry it because nothing matched.
   */
  private def readPage[Row](statement: SqlStr, isTotalCounted: Boolean)(using
      Queryable.Row[?, Row],
      Queryable.Row[?, (Row, Int)]): IndexedSeq[(Row, Option[Int])] =
    if isTotalCounted then Db.read(dialect)(_.runSql[(Row, Int)](statement)).map((row, total) => (row, Some(total)))
    else Db.read(dialect)(_.runSql[Row](statement)).map(row => (row, None))

  /** The words of the file name and every user metadata value, each in all its readings ([[SearchWords.variants]]) */
  protected def writeDocument(asset: Asset): Unit =
    val metadataValues = asset.userMetadata.data.values.flatten.map(_.value)
    val body = (asset.fileName +: metadataValues.toSeq).flatMap(SearchWords.variants).flatten.mkString(" ")
    val written =
      updateByBySql(SearchDao.DOCUMENT_UPSERT_SQL, List(RequestContext.getRepository.persistedId, asset.persistedId, body))
    logger.trace(s"Search document of asset ${asset.persistedId} ${if written > 0 then "written" else "unchanged"}: [$body]")

  override def indexAsset(asset: Asset, metadataFields: Map[String, UserMetadataField]): Unit =
    logger.trace(s"Indexing asset ${asset.persistedId} for search")
    indexMetadata(asset, metadataFields)
    writeDocument(asset)

  override def reindexAsset(asset: Asset, metadataFields: Map[String, UserMetadataField]): Unit =
    clearMetadata(asset.persistedId)
    indexMetadata(asset, metadataFields)
    writeDocument(asset)

  private def clearMetadata(assetId: String): Unit =
    logger.trace(s"Clearing asset $assetId metadata")
    BaseDao.incrWriteQueryCount()
    val sql =
      s"""
         DELETE FROM metadata_parameter
               WHERE ${FieldConst.REPO_ID} = ?
                 AND ${FieldConst.SearchToken.ASSET_ID} = ?
      """
    val bindValues = List[Object](RequestContext.getRepository.persistedId, assetId)
    logger.trace(s"Delete SQL: $sql, with values: $bindValues")
    val runner: QueryRunner = new QueryRunner()
    val numDeleted = runner.update(RequestContext.getConn, sql, bindValues*)
    logger.trace(s"Deleted records: $numDeleted")

  private def indexMetadata(asset: Asset, metadataFields: Map[String, UserMetadataField]): Unit =
    logger.trace(s"Indexing metadata for asset ${asset.persistedId}")
    asset.userMetadata.data.foreach {
      m =>
        val fieldId = m._1
        if metadataFields.contains(fieldId) then
          val field = metadataFields(fieldId)
          val values = m._2
          logger.trace(s"Processing field [${field.nameKey}] with values [$values]")
          addParameters(asset = asset, field = field, values = values.map(_.value))
        else logger.error(s"Asset $asset contains metadata field ID [$fieldId] that is not part of field configuration!")
    }

  override def addMetadataValue(asset: Asset, field: UserMetadataField, value: String): Unit =
    addParameters(asset = asset, field = field, values = Set(value))
    writeDocument(asset)

  /** One `metadata_parameter` row per value of a faceted field type; nothing for any other type */
  private def addParameters(asset: Asset, field: UserMetadataField, values: Set[String]): Unit =
    if !SearchDao.FACETED_FIELD_TYPES.contains(field.fieldType) then
      logger.trace(s"Field [${field.nameKey}] of type ${field.fieldType} has no metadata parameters")
      return

    logger.trace(s"INSERT SQL: ${SearchDao.VALUE_INSERT_SQL}. ARGS: ${values.toString}")
    val preparedStatement: PreparedStatement = RequestContext.getConn.prepareStatement(SearchDao.VALUE_INSERT_SQL)
    values.foreach {
      valueStr =>
        preparedStatement.clearParameters()
        preparedStatement.setString(1, RequestContext.getRepository.persistedId)
        preparedStatement.setString(2, asset.persistedId)
        preparedStatement.setString(3, field.persistedId)
        // keyword, under its name key, which a keyword filter is folded to as well (`SearchQueries.valueMatch`)
        if field.fieldType == FieldType.KEYWORD then preparedStatement.setString(4, NameKey.of(valueStr))
        else preparedStatement.setNull(4, Types.VARCHAR)
        if field.fieldType == FieldType.NUMBER then preparedStatement.setDouble(5, valueStr.toDouble)
        else preparedStatement.setNull(5, Types.DOUBLE)
        // boolean, from any form the field accepts ("1", "TRUE")
        if field.fieldType == FieldType.BOOL then preparedStatement.setBoolean(6, UserMetadataValue.asBoolean(valueStr).get)
        else preparedStatement.setNull(6, Types.BOOLEAN)
        BaseDao.incrWriteQueryCount()
        preparedStatement.execute()
    }
