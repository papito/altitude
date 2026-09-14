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
import altitude.core.dao.sql.tables.AssetRow
import altitude.core.models._
import altitude.core.util.BoundingBox
import altitude.core.util.GroupBy
import altitude.core.util.GroupedSearchPage
import altitude.core.util.GroupedSearchRow
import altitude.core.util.SearchGroupKey
import altitude.core.util.SearchQuery
import altitude.core.util.SearchResult
import altitude.core.util.SortValue

object SearchDao:
  private val VALUE_INSERT_SQL: String = s"""
            INSERT INTO metadata_parameter (
                        ${FieldConst.REPO_ID}, ${FieldConst.SearchToken.ASSET_ID},
                        ${FieldConst.SearchToken.FIELD_ID},
                        ${FieldConst.SearchToken.FIELD_VALUE_KW},
                        ${FieldConst.SearchToken.FIELD_VALUE_NUM},
                        ${FieldConst.SearchToken.FIELD_VALUE_BOOL})
                 VALUES (?, ?, ?, ?, ?, ?)
            """
abstract class SearchDao(override val config: Config) extends AssetDao(config) with altitude.core.dao.SearchDao:
  /** What this engine says differently in a search */
  protected def searchDialect: SearchDialect

  override def search(searchQuery: SearchQuery): SearchResult =
    val page = SearchQueries.flat(searchDialect, searchQuery, RequestContext.getRepository.persistedId)
    val rows = Db.read(dialect)(_.run(page))
    val total: Int = rows.headOption.map(_._2).getOrElse(0)

    logger.debug(s"Found [$total] records. Retrieved [${rows.length}] records")

    SearchResult(
      records = rows.map((row, _) => toModel(row)).toList,
      total = total,
      rpp = searchQuery.rpp,
      page = searchQuery.page,
      sort = searchQuery.searchSort)

  override def count(query: SearchQuery): Int =
    val statement = SearchQueries.count(searchDialect, query, RequestContext.getRepository.persistedId)
    Db.read(dialect)(_.run(statement))

  override def searchGrouped(query: SearchQuery): GroupedSearchPage =
    import dialect.*
    given TypeMapper[SortValue] = searchDialect.sortValueMapper

    val repositoryId = RequestContext.getRepository.persistedId
    val grouping = query.grouping.getOrElse(throw IllegalArgumentException("A grouped search needs a grouping"))
    val isFirstPage = query.cursor.isEmpty

    // Each statement selects the asset columns, then what names the row's group, its sort key, the group's count and the
    // candidate count; the shape of the group columns is the grouping's
    val page: IndexedSeq[(GroupedSearchRow, Int, Option[Int])] = grouping.by match
      case GroupBy.DateTaken =>
        val statement = SearchQueries.grouped(searchDialect, query, repositoryId)
        readGrouped[(AssetRow[Sc], Option[LocalDate], SortValue, Int, Int)](statement, isFirstPage).map {
          case ((asset, day, sortValue, groupTotal, candidates), total) =>
            (GroupedSearchRow(toModel(asset), SearchGroupKey.Day(day), sortValue, groupTotal), candidates, total)
        }
      case GroupBy.Location =>
        val statement = SearchQueries.groupedByLocation(searchDialect, query, repositoryId)
        readGrouped[(AssetRow[Sc], Option[String], Option[String], Option[String], Option[String], SortValue, Int, Int)](
          statement,
          isFirstPage).map {
          case ((asset, locationId, pathKey, name, categoryName, sortValue, groupTotal, candidates), total) =>
            val group = SearchGroupKey.Location(id = locationId, pathKey = pathKey, name = name, categoryName = categoryName)
            (GroupedSearchRow(toModel(asset), group, sortValue, groupTotal), candidates, total)
        }

    GroupedSearchPage(
      rows = page.map(_._1).toList,
      // A first page carries the overall count on every row; an empty first page has no rows because nothing matches
      total = Option.when(isFirstPage)(page.headOption.flatMap(_._3).getOrElse(0)),
      hasMore = page.headOption.exists(_._2 > query.rpp)
    )

  override def mapCells(query: SearchQuery, bbox: BoundingBox, cellDegrees: Double): List[MapCell] =
    import dialect.*

    val statement = SearchQueries.mapCells(searchDialect, query, RequestContext.getRepository.persistedId, bbox, cellDegrees)
    val cells = Db.read(dialect)(_.runSql[(Int, Double, Double, String)](statement)).map(MapCell.apply).toList
    logger.debug(s"Map cells of $cellDegrees degrees in $bbox: ${cells.length} cells over ${cells.map(_.count).sum} points")
    cells

  override def mapLocations(query: SearchQuery, bbox: BoundingBox): List[MapLocation] =
    val select = SearchQueries.mapLocations(searchDialect, query, RequestContext.getRepository.persistedId, bbox)
    Db.read(dialect)(_.run(select)).toList.map {
      // The kind filter guarantees a pin; a Location row without one cannot exist under the schema's CHECK
      case (id, name, categoryName, latitude, longitude, count) =>
        MapLocation(id, name, categoryName, latitude.get, longitude.get, count)
    }

  override def mapBounds(query: SearchQuery): Option[MapBounds] =
    import dialect.*

    val statement = SearchQueries.mapBounds(searchDialect, query, RequestContext.getRepository.persistedId)
    val (south, north, west, east, count) =
      Db.read(dialect)(_.runSql[(Option[Double], Option[Double], Option[Double], Option[Double], Int)](statement)).head
    Option.when(count > 0)(MapBounds(south = south.get, west = west.get, north = north.get, east = east.get, count = count))

  /** Runs a grouped statement: a first page's rows end with the overall total, which a page reached by cursor never selects */
  private def readGrouped[Row](statement: SqlStr, isFirstPage: Boolean)(using
      Queryable.Row[?, Row],
      Queryable.Row[?, (Row, Int)]): IndexedSeq[(Row, Option[Int])] =
    if isFirstPage then Db.read(dialect)(_.runSql[(Row, Int)](statement)).map((row, total) => (row, Some(total)))
    else Db.read(dialect)(_.runSql[Row](statement)).map(row => (row, None))

  protected def addSearchDocument(asset: Asset): Unit =
    throw NotImplementedError()

  protected def replaceSearchDocument(asset: Asset): Unit =
    throw NotImplementedError()

  override def indexAsset(asset: Asset, metadataFields: Map[String, UserMetadataField]): Unit =
    logger.debug(s"Indexing asset ${asset.persistedId} for search")
    indexMetadata(asset, metadataFields)
    addSearchDocument(asset)

  def reindexAsset(asset: Asset, metadataFields: Map[String, UserMetadataField]): Unit =
    clearMetadata(asset.persistedId)
    indexMetadata(asset, metadataFields)
    replaceSearchDocument(asset)

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
          addMetadataValues(asset = asset, field = field, values = values.map(_.value))
        else logger.error(s"Asset $asset contains metadata field ID [$fieldId] that is not part of field configuration!")
    }

  override def addMetadataValue(asset: Asset, field: UserMetadataField, value: String): Unit =
    addMetadataValues(asset = asset, field = field, values = Set(value))

  override def addMetadataValues(asset: Asset, field: UserMetadataField, values: Set[String]): Unit =
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
    replaceSearchDocument(asset)
