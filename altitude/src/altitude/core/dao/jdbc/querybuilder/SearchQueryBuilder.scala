package altitude.core.dao.jdbc.querybuilder

import java.time.LocalDate

import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.util.GroupBy
import altitude.core.util.Query
import altitude.core.util.Query.QueryParam
import altitude.core.util.SearchCursor
import altitude.core.util.SearchGrouping
import altitude.core.util.SearchQuery
import altitude.core.util.SearchSort
import altitude.core.util.SortDirection
import altitude.core.util.SortValue

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

  /** The calendar-day expression a grouped search orders, compares and counts by; it must match the engine's date index */
  protected def dayExpression(groupBy: GroupBy): String

  /** The ORDER BY term for the sort within a day. Engines may decorate it to steer their planner. */
  protected def secondarySortExpression(sort: SearchSort, grouping: SearchGrouping): String

  /** Whether the engine's schema lets this timestamp column be null (legacy rows) */
  protected def isNullableTimestamp(field: String): Boolean

  /** Where the engine natively places nulls for this direction; the cursor comparison must agree with the ORDER BY */
  protected def nullsFirst(direction: SortDirection): Boolean

  /** A calendar day as a bind value comparable with the engine's day expression */
  protected def dayBindValue(day: LocalDate): Any

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

  /**
   * One statement for a grouped page of asset IDs: the ordered page slice, the count of every match, and the full-day count of
   * each day on the page. All three branches share the exact same FROM/WHERE/GROUP BY/HAVING, so counts can never drift from the
   * IDs. The page slice fetches one row past the page to detect continuation. Every branch is materialized: the candidates
   * because they are read twice, the counts so the day count runs once per distinct day, not once per page row.
   *
   * Ordering is day, then the sort, then the ID as a deterministic tiebreaker. Nulls fall where the engine puts them natively; an
   * explicit NULLS clause would forfeit index-ordered reads on both engines.
   */
  def buildIdSearchSql(query: SearchQuery): SqlQuery =
    val grouping = query.grouping.getOrElse(throw IllegalArgumentException("An ID search needs a grouping"))
    val sort = query.searchSort.head
    val clauses = compileClauses(query)
    val day = dayExpression(grouping.by)

    // A row without the grouping timestamp has no day to belong to (only possible for legacy SQLite import times)
    val dayPresent =
      if isNullableTimestamp(grouping.by.field) then ClauseComponents(List(s"$tableName.${grouping.by.field} IS NOT NULL"))
      else ClauseComponents()
    val matchWhere = clauses(SqlQueryBuilder.WHERE) + dayPresent
    val from = fromStr(clauses(SqlQueryBuilder.FROM))
    val groupBy = groupByStr(clauses(SqlQueryBuilder.GROUP_BY))
    val having = havingStr(clauses(SqlQueryBuilder.HAVING))

    // The one relation every branch selects from: the matching assets, deduplicated the same way in each
    def matching(select: String, where: ClauseComponents): String =
      s"SELECT $select $from ${whereStr(where)} $groupBy $having"

    def orderBy(dayTerm: String, sortTerm: String, idTerm: String): String =
      s"$dayTerm ${grouping.direction}, $sortTerm ${sort.direction}, $idTerm ASC"

    val candidatesOrder = orderBy(day, secondarySortExpression(sort, grouping), s"$tableName.${FieldConst.ID}")
    // A cursor continues from a position; a page number is an offset
    val continuation = query.cursor.map(cursorPredicate(_, grouping, sort, day)).getOrElse(ClauseComponents())
    val offset = if query.cursor.isDefined then "" else s" OFFSET ${(query.page - 1) * query.rpp}"
    val candidatesWhere = matchWhere + continuation

    val sql = s"""
      WITH candidates AS MATERIALIZED (
        ${matching(s"$tableName.${FieldConst.ID} AS id, $day AS day, $tableName.${sort.field} AS sort_value", candidatesWhere)}
        ORDER BY $candidatesOrder
        LIMIT ${query.rpp + 1}$offset
      ), page AS MATERIALIZED (
        SELECT id, day, sort_value FROM candidates ORDER BY ${orderBy("day", "sort_value", "id")} LIMIT ${query.rpp}
      ), total AS MATERIALIZED (
        SELECT count(*) AS n FROM (${matching(s"$tableName.${FieldConst.ID}", matchWhere)}) AS m
      ), day_counts AS MATERIALIZED (
        SELECT p.day AS day,
               (SELECT count(*) FROM (${matching(s"$tableName.${FieldConst.ID}", matchWhere + ClauseComponents(List(s"$day = p.day")))}) AS m) AS n
          FROM (SELECT DISTINCT day FROM page) AS p
      )
      SELECT p.id AS id, p.day AS day, p.sort_value AS sort_value, t.n AS total, d.n AS day_total,
             (SELECT count(*) FROM candidates) AS candidate_count
        FROM total AS t
             LEFT JOIN page AS p ON 1 = 1
             LEFT JOIN day_counts AS d ON d.day = p.day
       ORDER BY ${orderBy("p.day", "p.sort_value", "p.id")}
    """

    // Bind values in order of appearance: candidates, total, day_counts
    SqlQuery(sql, candidatesWhere.bindVals ++ matchWhere.bindVals ++ matchWhere.bindVals)

  /**
   * Rows strictly after the cursor's anchor in page order, as lexicographic comparisons with independent directions: an earlier
   * day, or the same day and a later sort value, or the same sort value and a greater ID. A redundant inclusive bound on the day
   * lets the day index seek straight to the boundary. Where the sort column can be null, nulls sit where the engine natively
   * orders them and the comparison honors that placement.
   */
  private def cursorPredicate(cursor: SearchCursor, grouping: SearchGrouping, sort: SearchSort, day: String): ClauseComponents =
    val dayOp = if grouping.direction == SortDirection.DESC then "<" else ">"
    val sortOp = if sort.direction == SortDirection.DESC then "<" else ">"
    val sortColumn = s"$tableName.${sort.field}"
    val id = s"$tableName.${FieldConst.ID}"

    val afterBySort: ClauseComponents = cursor.sortValue match
      case SortValue.Null if nullsFirst(sort.direction) =>
        ClauseComponents(List(s"($sortColumn IS NOT NULL OR $id > ?)"), List(cursor.id))
      case SortValue.Null =>
        ClauseComponents(List(s"($sortColumn IS NULL AND $id > ?)"), List(cursor.id))
      case value =>
        val nullsAfter = if isNullableTimestamp(sort.field) && !nullsFirst(sort.direction) then s" OR $sortColumn IS NULL" else ""
        ClauseComponents(
          List(s"($sortColumn $sortOp ? OR ($sortColumn = ? AND $id > ?)$nullsAfter)"),
          List(value.bindValue, value.bindValue, cursor.id))

    val dayValue = dayBindValue(cursor.day)
    ClauseComponents(
      List(s"$day $dayOp= ?", s"($day $dayOp ? OR ${afterBySort.elements.head})"),
      List(dayValue, dayValue) ++ afterBySort.bindVals)

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
