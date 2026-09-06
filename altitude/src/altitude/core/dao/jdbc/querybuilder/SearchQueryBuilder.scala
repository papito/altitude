package altitude.core.dao.jdbc.querybuilder

import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.util.Query
import altitude.core.util.Query.QueryParam
import altitude.core.util.SearchQuery

object SearchQueryBuilder:
  private val ASSET_TABLE_NAME = "asset"

/** Common code for JDBC search query builders */
abstract class SearchQueryBuilder(selColumnNames: List[String])
  extends SqlQueryBuilder[SearchQuery](selColumnNames, SearchQueryBuilder.ASSET_TABLE_NAME):

  private val metadataParamTable = "metadata_parameter"
  protected val searchDocumentTable = "search_document"

  private val isPipelineProcessedFilter: ClauseComponents =
    ClauseComponents(elements = List(s"$tableName.${FieldConst.Asset.IS_PIPELINE_PROCESSED} = ?"), bindVals = List(true))

  protected def textSearch(searchQuery: SearchQuery): ClauseComponents

  override protected def from(searchQuery: SearchQuery): ClauseComponents =
    ClauseComponents(elements = allTableNames(searchQuery))

  override protected def fromStr(clauseComponents: ClauseComponents): String =
    val tableNames = clauseComponents.elements
    s"FROM ${tableNames.mkString(", ")}"

  /** If we are joining a table - this will also include its name(s) */
  private def allTableNames(searchQuery: SearchQuery): List[String] =
    val _tablesNames = List(SearchQueryBuilder.ASSET_TABLE_NAME) ++
      (if searchQuery.hasMetadataFilters then Set(metadataParamTable) else Set()) ++
      (if searchQuery.isText then Set(searchDocumentTable) else Set())

    _tablesNames

  override def buildSelectSql(query: SearchQuery): SqlQuery =
    if query.isSorted then buildSelectSqlAsSubquery(query)
    else super.buildSelectSql(query)

  private def buildSelectSqlAsSubquery(query: SearchQuery): SqlQuery =
    val allClauses = compileClauses(query)

    val subquerySql: String = s"""
        SELECT ${SearchQueryBuilder.ASSET_TABLE_NAME}.*
          ${fromStr(allClauses(SqlQueryBuilder.FROM))}
        ${whereStr(allClauses(SqlQueryBuilder.WHERE))}
      ${groupByStr(allClauses(SqlQueryBuilder.GROUP_BY))}
        ${havingStr(allClauses(SqlQueryBuilder.HAVING))}
      """

    val sql =
      selectStr(allClauses(SqlQueryBuilder.SELECT)) +
        s" FROM ($subquerySql) AS asset" +
        orderByStr(allClauses(SqlQueryBuilder.ORDER_BY)) +
        limitStr(query) +
        offsetStr(query)

    val bindValClauses = List(
      allClauses(SqlQueryBuilder.WHERE),
      allClauses(SqlQueryBuilder.ORDER_BY)
    )
    val bindVals = bindValClauses.foldLeft(List[Any]())((res, clause) => res ++ clause.bindVals)

    logger.debug(s"Select SQL: $sql with $bindVals")
    SqlQuery(sql, bindVals)

  override protected def where(searchQuery: SearchQuery): ClauseComponents =
    val repoIdElements = allTableNames(searchQuery).map(tableName => s"$tableName.${FieldConst.REPO_ID} = ?")
    val repoIdBindVals = allTableNames(searchQuery).map(_ => RequestContext.getRepository.persistedId)

    ClauseComponents(repoIdElements, repoIdBindVals) +
      textSearch(searchQuery) +
      super.where(searchQuery) +
      isPipelineProcessedFilter +
      folderFilter(searchQuery) +
      metadataFilter(searchQuery) +
      personFilter(searchQuery) +
      albumFilter(searchQuery) +
      searchDocumentJoin(searchQuery) +
      searchParameterJoin(searchQuery)

  /** Generates a SQL "IN" clause for folder IDs */
  private def folderFilter(searchQuery: SearchQuery): ClauseComponents =
    if searchQuery.folderIds.isEmpty then return ClauseComponents()

    // get ? placeholders equal to the number of folder ids
    val folderIdPlaceholders: String = List.fill(searchQuery.folderIds.size)("?").mkString(", ")

    ClauseComponents(
      elements = List(s"${FieldConst.Asset.FOLDER_ID} IN ($folderIdPlaceholders)"),
      bindVals = searchQuery.folderIds.toList
    )

  /** Generates a SQL "IN" clause for people IDs */
  private def personFilter(searchQuery: SearchQuery): ClauseComponents =
    if searchQuery.personIds.isEmpty then return ClauseComponents()

    // get ? placeholders equal to the number of person ids
    val peopleIdPlaceholders: String = List.fill(searchQuery.personIds.size)("?").mkString(", ")

    ClauseComponents(
      elements = List(
        s"""
          asset.id IN (
            SELECT DISTINCT(face.asset_id)
              FROM face, person
             WHERE face.person_id = person.id
               AND person.id IN ($peopleIdPlaceholders))
        """
      ),
      bindVals = searchQuery.personIds.toList
    )

  /** Restricts the results to the assets the given albums point at */
  private def albumFilter(searchQuery: SearchQuery): ClauseComponents =
    if searchQuery.albumIds.isEmpty then return ClauseComponents()

    val albumIdPlaceholders: String = List.fill(searchQuery.albumIds.size)("?").mkString(", ")

    ClauseComponents(
      elements = List(
        s"""
          asset.id IN (
            SELECT album_asset.asset_id
              FROM album_asset
             WHERE album_asset.album_id IN ($albumIdPlaceholders))
        """
      ),
      bindVals = searchQuery.albumIds.toList
    )

  private def metadataFilter(searchQuery: SearchQuery): ClauseComponents =
    val filters = searchQuery.metadataFilters.map {
      case (columnName, value) =>
        value match
          case _: String => "(field_id = ? AND field_value_kw = ?)"
          case _: Boolean => "(field_id = ? AND field_value_bool = ?)"
          case _: Number => "(field_id = ? AND field_value_num = ?)"
          case qParam: QueryParam =>
            qParam.paramType match
              case Query.ParamType.EQ =>
                qParam.values.head match
                  case _: String => "(field_id = ? AND field_value_kw = ?)"
                  case _: Boolean => "(field_id = ? AND field_value_bool = ?)"
                  case _: Number => "(field_id = ? AND field_value_num = ?)"
              case _ => throw IllegalArgumentException(s"This type of parameter is not supported: ${qParam.paramType}")
          case _ => throw IllegalArgumentException(s"This type of parameter is not supported: $value")
    }.toList

    val bindVals = searchQuery.metadataFilters.foldLeft(List[Any]()) {
      (res, el) =>
        val (metadataFieldId, value) = el

        // field id, value binds
        res :+ metadataFieldId :+ (value match {
          case qParam: QueryParam => qParam.values.head
          case _ => value
        })
    }

    if filters.isEmpty then ClauseComponents()
    else ClauseComponents(elements = List("(" + filters.mkString(" OR ") + ")"), bindVals = bindVals)

  protected def searchDocumentJoin(searchQuery: SearchQuery): ClauseComponents =
    if searchQuery.isText then ClauseComponents(elements = List(s"$searchDocumentTable.asset_id = asset.id"))
    else ClauseComponents()

  protected def searchParameterJoin(searchQuery: SearchQuery): ClauseComponents =
    if searchQuery.hasMetadataFilters then ClauseComponents(elements = List(s"$metadataParamTable.asset_id = asset.id"))
    else ClauseComponents()

  override protected def groupBy(searchQuery: SearchQuery): ClauseComponents =
    if !searchQuery.hasMetadataFilters then return ClauseComponents()
    ClauseComponents(elements = List(s"asset.${FieldConst.ID}"))

  override protected def having(searchQuery: SearchQuery): ClauseComponents =
    if !searchQuery.hasMetadataFilters then return ClauseComponents()
    ClauseComponents(elements = List(s"count(asset.${FieldConst.ID}) >= ${searchQuery.metadataFilters.size}"))

  override protected def orderBy(query: SearchQuery): ClauseComponents =
    if !query.isSorted then return ClauseComponents()

    val sort = query.searchSort.head
    val sql = s" ORDER BY $tableName.${sort.field} ${sort.direction}"

    ClauseComponents(List(sql))

  override protected def orderByStr(clauseComponents: ClauseComponents): String =
    if clauseComponents.isEmpty then return ""
    clauseComponents.elements.mkString("")
