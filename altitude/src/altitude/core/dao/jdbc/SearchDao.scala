package altitude.core.dao.jdbc

import com.typesafe.config.Config
import java.sql.PreparedStatement
import java.sql.Types
import java.time.LocalDate
import org.apache.commons.dbutils.QueryRunner
import scalasql.Sc
import scalasql.core.TypeMapper

import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.dao.sql.Db
import altitude.core.dao.sql.search.SearchDialect
import altitude.core.dao.sql.search.SearchQueries
import altitude.core.dao.sql.tables.AssetRow
import altitude.core.models._
import altitude.core.util.GroupedSearchPage
import altitude.core.util.GroupedSearchRow
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

  override def searchGrouped(query: SearchQuery): GroupedSearchPage =
    import dialect.*
    given TypeMapper[SortValue] = searchDialect.sortValueMapper

    val statement = SearchQueries.grouped(searchDialect, query, RequestContext.getRepository.persistedId)

    // The asset columns, then the page's day and sort key, the day's count, the candidate count and the first page's total
    val recs: IndexedSeq[(AssetRow[Sc], Option[LocalDate], SortValue, Int, Int, Option[Int])] =
      if query.cursor.isEmpty then
        Db.read(dialect)(_.runSql[(AssetRow[Sc], Option[LocalDate], SortValue, Int, Int, Int)](statement))
          .map((asset, day, sortValue, dayTotal, candidates, total) => (asset, day, sortValue, dayTotal, candidates, Some(total)))
      else
        // A page reached by cursor never asks for the overall count, so the statement does not select it
        Db.read(dialect)(_.runSql[(AssetRow[Sc], Option[LocalDate], SortValue, Int, Int)](statement))
          .map((asset, day, sortValue, dayTotal, candidates) => (asset, day, sortValue, dayTotal, candidates, None))

    GroupedSearchPage(
      rows =
        recs.map((asset, day, sortValue, dayTotal, _, _) => GroupedSearchRow(toModel(asset), day, sortValue, dayTotal)).toList,
      // A first page carries the overall count on every row; an empty first page has no rows because nothing matches
      total = Option.when(query.cursor.isEmpty)(recs.headOption.flatMap(_._6).getOrElse(0)),
      hasMore = recs.headOption.exists(_._5 > query.rpp)
    )

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
