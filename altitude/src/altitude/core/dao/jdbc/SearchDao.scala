package altitude.core.dao.jdbc

import com.typesafe.config.Config
import java.sql.PreparedStatement
import java.sql.Types
import java.time.LocalDate
import org.apache.commons.dbutils.QueryRunner
import scalasql.Sc
import scalasql.core.Queryable
import scalasql.core.SqlStr
import scalasql.core.TypeMapper

import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.dao.sql.Db
import altitude.core.dao.sql.search.SearchDialect
import altitude.core.dao.sql.search.SearchQueries
import altitude.core.dao.sql.tables.AlbumRow
import altitude.core.dao.sql.tables.AssetRow
import altitude.core.dao.sql.tables.FolderRow
import altitude.core.dao.sql.tables.LocationRow
import altitude.core.dao.sql.tables.PersonRow
import altitude.core.models._
import altitude.core.util.BoundingBox
import altitude.core.util.GroupBy
import altitude.core.util.GroupedSearchPage
import altitude.core.util.GroupedSearchRow
import altitude.core.util.ResolvedSearchText
import altitude.core.util.SearchGroupKey
import altitude.core.util.SearchName
import altitude.core.util.SearchQuery
import altitude.core.util.SearchResult
import altitude.core.util.SearchSource
import altitude.core.util.SearchWords
import altitude.core.util.SortValue

object SearchDao:
  /**
   * The field types a metadata filter can match: the ones with a value column in `metadata_parameter` that the insert writes. A
   * value of any other type is searchable through the Search document only.
   */
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
   * Both engines take the same upsert: an asset has one document, keyed by the table's unique index. An unchanged body is not
   * rewritten, which on SQLite also spares the full-text entry its update trigger.
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

  override def search(searchQuery: SearchQuery): SearchResult =
    import dialect.*

    val isFirstPage = searchQuery.page == 1
    if matchesNothing(searchQuery) then
      return SearchResult(
        Nil,
        Option.when(isFirstPage)(0),
        hasMore = false,
        searchQuery.rpp,
        searchQuery.page,
        searchQuery.searchSort)

    val statement = SearchQueries.flat(searchDialect, searchQuery, RequestContext.getRepository.persistedId)
    // Each row is the asset, then the count of the page's slice, which fetched one row past the page
    val rows = readPage[(AssetRow[Sc], Int)](statement, isFirstPage)
    // A first page carries the overall count on every row; an empty first page has no rows because nothing matches
    val total = Option.when(isFirstPage)(rows.headOption.flatMap(_._2).getOrElse(0))
    val hasMore = rows.headOption.exists(_._1._2 > searchQuery.rpp)

    logger.debug(s"Retrieved [${rows.length}] records, total ${total.getOrElse("not counted")}, more: $hasMore")

    SearchResult(
      records = rows.map { case ((asset, _), _) => toModel(asset) }.toList,
      total = total,
      hasMore = hasMore,
      rpp = searchQuery.rpp,
      page = searchQuery.page,
      sort = searchQuery.searchSort
    )

  override def count(query: SearchQuery): Int =
    import dialect.*

    if matchesNothing(query) then return 0
    val statement = SearchQueries.count(searchDialect, query, RequestContext.getRepository.persistedId)
    Db.read(dialect)(_.runSql[Int](statement)).head

  override def cappedCount(query: SearchQuery): Int =
    import dialect.*

    if matchesNothing(query) then return 0
    val statement = SearchQueries.cappedCount(searchDialect, query, RequestContext.getRepository.persistedId)
    Db.read(dialect)(_.runSql[Int](statement)).head

  override def searchGrouped(query: SearchQuery): GroupedSearchPage =
    import dialect.*
    given TypeMapper[SortValue] = searchDialect.sortValueMapper

    val repositoryId = RequestContext.getRepository.persistedId
    val grouping = query.grouping.getOrElse(throw IllegalArgumentException("A grouped search needs a grouping"))
    val isFirstPage = query.cursor.isEmpty
    if matchesNothing(query) then return GroupedSearchPage(Nil, Option.when(isFirstPage)(0), hasMore = false)

    // Each statement selects the asset columns, then what names the row's group, its two sort keys, the group's count and
    // the candidate count; the shape of the group columns is the grouping's
    val page: IndexedSeq[(GroupedSearchRow, Int, Option[Int])] = grouping.by match
      case GroupBy.DateTaken =>
        val statement = SearchQueries.grouped(searchDialect, query, repositoryId)
        readPage[(AssetRow[Sc], Option[LocalDate], SortValue, SortValue, Int, Int)](statement, isFirstPage).map {
          case ((asset, day, sortValue, secondSortValue, groupTotal, candidates), total) =>
            val row = GroupedSearchRow(toModel(asset), SearchGroupKey.Day(day), sortValue, secondSortValue, groupTotal)
            (row, candidates, total)
        }
      case GroupBy.Location =>
        val statement = SearchQueries.groupedByLocation(searchDialect, query, repositoryId)
        readPage[(AssetRow[Sc], Option[String], Option[String], Option[String], Option[String], SortValue, SortValue, Int, Int)](
          statement,
          isFirstPage).map {
          case ((asset, locationId, pathKey, name, categoryName, sortValue, secondSortValue, groupTotal, candidates), total) =>
            val group = SearchGroupKey.Location(id = locationId, pathKey = pathKey, name = name, categoryName = categoryName)
            (GroupedSearchRow(toModel(asset), group, sortValue, secondSortValue, groupTotal), candidates, total)
        }

    GroupedSearchPage(
      rows = page.map(_._1).toList,
      // A first page carries the overall count on every row; an empty first page has no rows because nothing matches
      total = Option.when(isFirstPage)(page.headOption.flatMap(_._3).getOrElse(0)),
      hasMore = page.headOption.exists(_._2 > query.rpp)
    )

  override def mapCells(query: SearchQuery, bbox: BoundingBox, cellDegrees: Double): List[MapCell] =
    import dialect.*

    if matchesNothing(query) then return Nil
    val statement = SearchQueries.mapCells(searchDialect, query, RequestContext.getRepository.persistedId, bbox, cellDegrees)
    val cells = Db.read(dialect)(_.runSql[(Int, Double, Double, String)](statement)).map(MapCell.apply).toList
    logger.debug(s"Map cells of $cellDegrees degrees in $bbox: ${cells.length} cells over ${cells.map(_.count).sum} points")
    cells

  override def mapLocations(query: SearchQuery, bbox: BoundingBox): List[MapLocation] =
    import dialect.*

    if matchesNothing(query) then return Nil
    val statement = SearchQueries.mapLocations(searchDialect, query, RequestContext.getRepository.persistedId, bbox)
    Db.read(dialect)(_.runSql[(String, String, Option[String], Option[Double], Option[Double], Int)](statement)).toList.map {
      // The kind filter guarantees a pin; a Location row without one cannot exist under the schema's CHECK
      case (id, name, categoryName, latitude, longitude, count) =>
        MapLocation(id, name, categoryName, latitude.get, longitude.get, count)
    }

  override def mapBounds(query: SearchQuery): Option[MapBounds] =
    import dialect.*

    if matchesNothing(query) then return None
    val statement = SearchQueries.mapBounds(searchDialect, query, RequestContext.getRepository.persistedId)
    val (south, north, west, east, count) =
      Db.read(dialect)(_.runSql[(Option[Double], Option[Double], Option[Double], Option[Double], Int)](statement)).head
    Option.when(count > 0)(MapBounds(south = south.get, west = west.get, north = north.get, east = east.get, count = count))

  override def searchNames: Map[SearchSource, Seq[SearchName]] =
    import dialect.*

    val repository = RequestContext.getRepository
    val repositoryId = repository.persistedId

    val people = PersonRow.select
      .filter {
        person =>
          (person.repositoryId `=` repositoryId) && (person.isNamed `=` true) && (person.isHidden `=` false) &&
          (person.isDeleted `=` false) && (person.isBadMatch `=` false)
      }
      .map(person => (person.id, person.name))
    val locations = LocationRow.select
      .filter(location => location.repositoryId `=` repositoryId)
      .map(location => (location.id, location.name, location.kind, location.categoryId))
    val folders = FolderRow.select
      .filter {
        folder =>
          (folder.repositoryId `=` repositoryId) && (folder.isRecycled `=` false) && (folder.id <> repository.rootFolderId)
      }
      .map(folder => (folder.id, folder.name, folder.parentId))
    val albums = AlbumRow.select.filter(album => album.repositoryId `=` repositoryId).map(album => (album.id, album.name))

    // Categories and Locations are one table, told apart by kind
    val (categoryRows, locationRows) =
      Db.read(dialect)(_.run(locations)).partition((_, _, kind, _) => kind == LocationKind.Category.dbValue)

    val names = Map(
      SearchSource.Person -> Db.read(dialect)(_.run(people)).map((id, name) => SearchName(id, name)),
      SearchSource.Location -> locationRows.map((id, name, _, categoryId) => SearchName(id, name, categoryId)),
      SearchSource.Category -> categoryRows.map((id, name, _, _) => SearchName(id, name)),
      SearchSource.Folder -> Db.read(dialect)(_.run(folders)).map((id, name, parentId) => SearchName(id, name, Some(parentId))),
      SearchSource.Album -> Db.read(dialect)(_.run(albums)).map((id, name) => SearchName(id, name))
    )
    logger.debug(s"Search name candidates: ${names.map((source, candidates) => s"$source=${candidates.size}").mkString(", ")}")
    names

  override def probeText(text: ResolvedSearchText, limit: Int): Map[Int, Seq[String]] =
    import dialect.*

    SearchQueries
      .textProbe(searchDialect, text, RequestContext.getRepository.persistedId, limit)
      .fold(Map.empty)(statement => Db.read(dialect)(_.runSql[(Int, String)](statement)).groupMap(_._1)(_._2))

  /**
   * Whether the Search text's probe found that no asset can match it, a positive group having no hit at all: such a search is
   * answered with nothing, without reading the library
   */
  private def matchesNothing(query: SearchQuery): Boolean = query.resolvedText.exists(_.candidates.exists(_.isEmpty))

  /** Runs a page's statement: a first page's rows end with the overall total, which a later page never selects */
  private def readPage[Row](statement: SqlStr, isFirstPage: Boolean)(using
      Queryable.Row[?, Row],
      Queryable.Row[?, (Row, Int)]): IndexedSeq[(Row, Option[Int])] =
    if isFirstPage then Db.read(dialect)(_.runSql[(Row, Int)](statement)).map((row, total) => (row, Some(total)))
    else Db.read(dialect)(_.runSql[Row](statement)).map(row => (row, None))

  /**
   * Writes the asset's Search document, new or not: the words of its file name, then those of every user metadata value, each in
   * every one of its readings ([[SearchWords.variants]])
   */
  protected def writeDocument(asset: Asset): Unit =
    val metadataValues = asset.userMetadata.data.values.flatten.map(_.value)
    val body = (asset.fileName +: metadataValues.toSeq).flatMap(SearchWords.variants).flatten.mkString(" ")
    val written =
      updateByBySql(SearchDao.DOCUMENT_UPSERT_SQL, List(RequestContext.getRepository.persistedId, asset.persistedId, body))
    logger.debug(s"Search document of asset ${asset.persistedId} ${if written > 0 then "written" else "unchanged"}: [$body]")

  override def indexAsset(asset: Asset, metadataFields: Map[String, UserMetadataField]): Unit =
    logger.debug(s"Indexing asset ${asset.persistedId} for search")
    indexMetadata(asset, metadataFields)
    writeDocument(asset)

  override def reindexAsset(asset: Asset, metadataFields: Map[String, UserMetadataField]): Unit =
    clearMetadata(asset.persistedId)
    indexMetadata(asset, metadataFields)
    writeDocument(asset)

  private def clearMetadata(assetId: String): Unit =
    logger.debug(s"Clearing asset $assetId metadata")
    BaseDao.incrWriteQueryCount()
    val sql =
      s"""
         DELETE FROM metadata_parameter
               WHERE ${FieldConst.REPO_ID} = ?
                 AND ${FieldConst.SearchToken.ASSET_ID} = ?
      """
    val bindValues = List[Object](RequestContext.getRepository.persistedId, assetId)
    logger.debug(s"Delete SQL: $sql, with values: $bindValues")
    val runner: QueryRunner = new QueryRunner()
    val numDeleted = runner.update(RequestContext.getConn, sql, bindValues*)
    logger.debug(s"Deleted records: $numDeleted")

  private def indexMetadata(asset: Asset, metadataFields: Map[String, UserMetadataField]): Unit =
    logger.debug(s"Indexing metadata for asset ${asset.persistedId}")
    asset.userMetadata.data.foreach {
      m =>
        val fieldId = m._1
        if metadataFields.contains(fieldId) then
          val field = metadataFields(fieldId)
          val values = m._2
          logger.debug(s"Processing field [${field.nameLowercase}] with values [$values]")
          addParameters(asset = asset, field = field, values = values.map(_.value))
        else logger.error(s"Asset $asset contains metadata field ID [$fieldId] that is not part of field configuration!")
    }

  override def addMetadataValue(asset: Asset, field: UserMetadataField, value: String): Unit =
    addMetadataValues(asset = asset, field = field, values = Set(value))

  override def addMetadataValues(asset: Asset, field: UserMetadataField, values: Set[String]): Unit =
    addParameters(asset = asset, field = field, values = values)
    writeDocument(asset)

  /** One `metadata_parameter` row per value of a faceted field type; nothing for any other type */
  private def addParameters(asset: Asset, field: UserMetadataField, values: Set[String]): Unit =
    if !SearchDao.FACETED_FIELD_TYPES.contains(field.fieldType) then
      logger.debug(s"Field [${field.nameLowercase}] of type ${field.fieldType} has no metadata parameters")
      return

    logger.debug(s"INSERT SQL: ${SearchDao.VALUE_INSERT_SQL}. ARGS: ${values.toString}")
    val preparedStatement: PreparedStatement = RequestContext.getConn.prepareStatement(SearchDao.VALUE_INSERT_SQL)
    values.foreach {
      valueStr =>
        preparedStatement.clearParameters()
        preparedStatement.setString(1, RequestContext.getRepository.persistedId)
        preparedStatement.setString(2, asset.persistedId)
        preparedStatement.setString(3, field.persistedId)
        // keyword
        if field.fieldType == FieldType.KEYWORD then preparedStatement.setString(4, valueStr.toLowerCase)
        else preparedStatement.setNull(4, Types.VARCHAR)
        // number
        if field.fieldType == FieldType.NUMBER then preparedStatement.setDouble(5, valueStr.toDouble)
        else preparedStatement.setNull(5, Types.DOUBLE)
        // boolean
        if field.fieldType == FieldType.BOOL then preparedStatement.setBoolean(6, valueStr.toBoolean)
        else preparedStatement.setNull(6, Types.BOOLEAN)
        BaseDao.incrWriteQueryCount()
        preparedStatement.execute()
    }
