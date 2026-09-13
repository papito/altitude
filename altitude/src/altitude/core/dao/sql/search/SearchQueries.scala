package altitude.core.dao.sql.search

import java.time.LocalDate
import scalasql.Sc
import scalasql.Table
import scalasql.core.Context
import scalasql.core.Expr
import scalasql.core.SqlStr
import scalasql.core.SqlStr.SqlStringSyntax
import scalasql.core.TypeMapper
import scalasql.core.WithSqlExpr
import scalasql.query.Select
import scalasql.query.SqlWindow

import altitude.core.dao.sql.Columns
import altitude.core.dao.sql.Db
import altitude.core.dao.sql.DynamicFilter
import altitude.core.dao.sql.tables.AlbumAssetRow
import altitude.core.dao.sql.tables.AssetRow
import altitude.core.dao.sql.tables.FaceRow
import altitude.core.dao.sql.tables.MetadataParameterRow
import altitude.core.dao.sql.tables.PersonRow
import altitude.core.dao.sql.tables.SearchDocumentRow
import altitude.core.util.Query
import altitude.core.util.Query.QueryParam
import altitude.core.util.SearchCursor
import altitude.core.util.SearchGrouping
import altitude.core.util.SearchQuery
import altitude.core.util.SearchSort
import altitude.core.util.SortDirection
import altitude.core.util.SortValue

/**
 * A search as typed relations.
 *
 * Every shape of search - the flat page, each slice of a grouped page, each of its counts - starts from the same [[matching]]
 * relation, so a count can never disagree with the rows it is counting.
 *
 * Each of the joined-in filters is a semi-join on `asset.id` rather than a comma join: a search then reads `FROM asset` alone,
 * with no `GROUP BY`/`HAVING` on the outer query and no duplicate rows to deduplicate.
 *
 * These are pure functions of their arguments; the repository is passed in rather than read from `RequestContext`.
 */
object SearchQueries:

  /** Every asset a search matches, unordered and unpaged */
  def matching(engine: SearchDialect, query: SearchQuery, repositoryId: String): Select[AssetRow[Expr], AssetRow[Sc]] =
    import engine.dialect.*

    AssetRow.select
      .filter(asset => (asset.repositoryId `=` repositoryId) && (asset.isPipelineProcessed `=` true))
      .filter(asset => DynamicFilter(AssetRow, Columns.of(AssetRow, asset, engine.dialect), query, engine.dialect))
      .filterIf(query.folderIds.nonEmpty)(asset => Columns.isIn(asset.folderId, query.folderIds, engine.dialect))
      .filterIf(query.isText)(asset => textFilter(engine, asset, query.text.get, repositoryId))
      .filterIf(query.hasMetadataFilters)(asset => metadataFilter(engine, asset, query, repositoryId))
      .filterIf(query.personIds.nonEmpty)(asset => personFilter(engine, asset, query.personIds))
      .filterIf(query.albumIds.nonEmpty)(asset => albumFilter(engine, asset, query.albumIds))

  /**
   * One page of a flat search, with the count of every match carried on each row.
   *
   * The window count is added before the ordering and the page, so it counts the whole match rather than the page. A missing
   * capture time creates a large tie group, so the ID is always the last ordering term: without it an offset page is not
   * deterministic. The last `sortBy` call is the leading `ORDER BY` term, and every one of them has to precede the page.
   */
  def flat(
      engine: SearchDialect,
      query: SearchQuery,
      repositoryId: String): Select[(AssetRow[Expr], SqlWindow[Int]), (AssetRow[Sc], Int)] =
    import engine.dialect.*

    val counted = matching(engine, query, repositoryId).mapAggregate((asset, aggregate) => (asset, aggregate.size.over))

    val sorted = query.searchSort.headOption.fold(counted) {
      sort =>
        val byId = counted.sortBy(_._1.id).asc
        val ordered = byId.sortBy(row => Columns.required(AssetRow, row._1, sort.field, engine.dialect))
        if sort.direction == SortDirection.DESC then ordered.desc else ordered.asc
    }

    if query.rpp > 0 then sorted.drop((query.page - 1) * query.rpp).take(query.rpp) else sorted

  /**
   * One statement for a grouped page: the ordered page slice joined back to its asset rows, the full-day count of each day on the
   * page and, on a first page, the count of every match. Every branch renders from the same [[matching]] relation, so counts can
   * never drift from the rows and every bind value travels with the fragment that needs it. The candidate slices are narrow (ID,
   * day, sort key) and fetch one row past the page to detect continuation; only the page's rows are joined to the asset table.
   * Every branch is materialized: the candidates because they are read twice, the counts so a day count runs once per distinct
   * day, not once per page row.
   *
   * A page reached by cursor skips the overall count: it is the dominant cost of the statement on a large library, and the footer
   * total was set by the first page. Ordering is day, then the sort, then the ID as a deterministic tiebreaker. Nulls fall where
   * the engine puts them natively; an explicit NULLS clause would forfeit index-ordered reads on both engines.
   *
   * The shell is hand-written on purpose: `MATERIALIZED`, the guarded `LIMIT CASE`, SQLite's planner hint and native null
   * placement are all tuned against the two engines' plans, and none of them can be expressed through a typed query.
   */
  def grouped(engine: SearchDialect, query: SearchQuery, repositoryId: String): SqlStr =
    import engine.dialect.*
    given TypeMapper[SortValue] = engine.sortValueMapper

    val grouping = query.grouping.getOrElse(throw IllegalArgumentException("A grouped search needs a grouping"))
    val sort = query.searchSort.head
    val base = matching(engine, query, repositoryId)
    val asset = WithSqlExpr.get(base)

    // A grouping timestamp the schema lets be null gives its rows their own group, at the engine's native null position
    val ownGroup = engine.isNullableTimestamp(grouping.by.field)
    val isFirstPage = query.cursor.isEmpty

    def day(row: AssetRow[Expr]): Expr[Option[LocalDate]] = engine.day(row, grouping.by)
    def isUndated(row: AssetRow[Expr]): Expr[Boolean] = Expr[Boolean](implicit ctx => sql"${day(row)} IS NULL")

    /** The narrow candidate relation, ordered and sliced in the context that names its own columns */
    def candidates(extra: Option[AssetRow[Expr] => Expr[Boolean]], limit: SqlStr): SqlStr =
      val projected = extra
        .fold(base)(base.filter)
        .map(row => (row.id, day(row), Expr[SortValue](implicit ctx => sql"${sortColumn(engine, row, sort)}")))

      val ordered = Select.withExprSuffix(
        Select.toSimpleFrom(projected),
        false,
        ctx =>
          given Context = ctx
          sql" ORDER BY ${day(asset)} ${towards(grouping.direction)}" +
            sql", ${engine.secondarySort(asset, sort, grouping)} ${towards(sort.direction)}, ${asset.id} ASC" + limit
      )

      Db.render(ordered, engine.dialect).withCompleteQuery(false)

    /** The matching IDs alone, for a count that has to agree with the rows */
    def matchingIds(extra: Option[AssetRow[Expr] => Expr[Boolean]]): SqlStr =
      Db.render(extra.fold(base)(base.filter).map(_.id), engine.dialect).withCompleteQuery(false)

    val continuation = query.cursor.map(cursor => (row: AssetRow[Expr]) => cursorPredicate(engine, cursor, grouping, sort, row))
    val dated = candidates(continuation, SqlStr.raw(s" LIMIT ${query.rpp + 1}"))

    // A day range never reaches NULL. Only a transition from dated rows to the trailing null group needs a second slice.
    // Put its guard in LIMIT: SQLite short-circuits a zero limit, but would scan the null block for a WHERE guard.
    val needsUndatedSlice = ownGroup && !engine.nullsFirst(grouping.direction) && query.cursor.exists(_.day.isDefined)
    val candidatesCte =
      if needsUndatedSlice then
        val guard = s" LIMIT CASE WHEN (SELECT count(*) FROM dated) > ${query.rpp} THEN 0 ELSE ${query.rpp + 1} END"
        val undated = candidates(Some(isUndated), SqlStr.raw(guard))
        sql"""dated $narrowColumns AS MATERIALIZED ($dated), undated $narrowColumns AS MATERIALIZED ($undated),
          candidates $narrowColumns AS MATERIALIZED (
            SELECT id, day, sort_value FROM dated UNION ALL SELECT id, day, sort_value FROM undated)"""
      else sql"candidates $narrowColumns AS MATERIALIZED ($dated)"

    // Separate equality and IS NULL probes keep both counts on the day index. The null driver is empty on dated-only pages.
    val isCursorDay = (row: AssetRow[Expr]) => Expr[Boolean](implicit ctx => sql"${day(row)} = p.day")
    val datedDriver = if ownGroup then SqlStr.raw(" WHERE day IS NOT NULL") else SqlStr.empty
    val nullCount =
      if ownGroup then sql""" UNION ALL SELECT p.day AS day,
          (SELECT count(*) FROM (${matchingIds(Some(isUndated))}) AS m) AS n
          FROM (SELECT DISTINCT day FROM page WHERE day IS NULL) AS p"""
      else SqlStr.empty
    val nullJoin = if ownGroup then SqlStr.raw(" OR (d.day IS NULL AND p.day IS NULL)") else SqlStr.empty

    val totalCte =
      if isFirstPage then sql", total AS MATERIALIZED (SELECT count(*) AS n FROM (${matchingIds(None)}) AS m)"
      else SqlStr.empty
    val totalColumn = if isFirstPage then SqlStr.raw(", t.n AS total") else SqlStr.empty
    val totalJoin = if isFirstPage then SqlStr.raw("CROSS JOIN total AS t") else SqlStr.empty

    def order(prefix: String): SqlStr =
      SqlStr.raw(s"$prefix.day ${grouping.direction}, $prefix.sort_value ${sort.direction}, $prefix.id ASC")

    sql"""
      WITH $candidatesCte, page $narrowColumns AS MATERIALIZED (
        SELECT id, day, sort_value FROM candidates
         ORDER BY ${SqlStr.raw(s"day ${grouping.direction}, sort_value ${sort.direction}, id ASC")}
         LIMIT ${SqlStr.raw(query.rpp.toString)}
      ), day_counts AS MATERIALIZED (
        SELECT p.day AS day,
               (SELECT count(*) FROM (${matchingIds(Some(isCursorDay))}) AS m) AS n
          FROM (SELECT DISTINCT day FROM page$datedDriver) AS p$nullCount
      )$totalCte
      SELECT $assetColumns, p.day AS day, p.sort_value AS sort_value, d.n AS day_total,
             (SELECT count(*) FROM candidates) AS candidate_count$totalColumn
        FROM page AS p
             JOIN asset ON asset.id = p.id
             LEFT JOIN day_counts AS d ON d.day = p.day$nullJoin
             $totalJoin
       ORDER BY ${order("p")}
    """

  /**
   * Rows strictly after the cursor's anchor in page order, as lexicographic comparisons with independent directions: an earlier
   * day, or the same day and a later sort value, or the same sort value and a greater ID. A redundant inclusive bound on the day
   * lets the day index seek straight to the boundary. Where the sort column can be null, nulls sit where the engine natively
   * orders them and the comparison honors that placement.
   */
  private def cursorPredicate(
      engine: SearchDialect,
      cursor: SearchCursor,
      grouping: SearchGrouping,
      sort: SearchSort,
      row: AssetRow[Expr]): Expr[Boolean] =
    import engine.dialect.*
    given TypeMapper[SortValue] = engine.sortValueMapper

    val dayOp = SqlStr.raw(if grouping.direction == SortDirection.DESC then "<" else ">")
    val sortOp = SqlStr.raw(if sort.direction == SortDirection.DESC then "<" else ">")
    val day = engine.day(row, grouping.by)
    val column = sortColumn(engine, row, sort)
    val id = row.id

    // Sorting by capture time inside its null group reduces to ID order; every capture sort value there is NULL.
    val afterBySort: Expr[Boolean] =
      if cursor.day.isEmpty && sort.field == grouping.by.field then Expr[Boolean](implicit ctx => sql"$id > ${cursor.id}")
      else
        cursor.sortValue match
          case SortValue.Null if engine.nullsFirst(sort.direction) =>
            Expr[Boolean](implicit ctx => sql"($column IS NOT NULL OR $id > ${cursor.id})")
          case SortValue.Null =>
            Expr[Boolean](implicit ctx => sql"($column IS NULL AND $id > ${cursor.id})")
          case value =>
            Expr[Boolean] {
              implicit ctx =>
                val nullsAfter =
                  if engine.isNullableTimestamp(sort.field) && !engine.nullsFirst(sort.direction) then sql" OR $column IS NULL"
                  else SqlStr.empty
                sql"($column $sortOp $value OR ($column = $value AND $id > ${cursor.id})$nullsAfter)"
            }

    cursor.day match
      case Some(cursorDay) =>
        // The redundant inclusive bound seeks to the cursor day; a trailing null block is supplied by the guarded slice.
        Expr[Boolean](implicit ctx => sql"$day $dayOp= $cursorDay AND ($day $dayOp $cursorDay OR $afterBySort)")
      case None if engine.nullsFirst(grouping.direction) =>
        // All dated days follow the leading null group, regardless of the secondary sort.
        Expr[Boolean](implicit ctx => sql"($day IS NOT NULL OR $afterBySort)")
      case None =>
        Expr[Boolean](implicit ctx => sql"$day IS NULL AND $afterBySort")

  /** The column a search sorts within a day by */
  private def sortColumn(engine: SearchDialect, row: AssetRow[Expr], sort: SearchSort): Expr[?] =
    Columns.required(AssetRow, row, sort.field, engine.dialect)

  private def towards(direction: SortDirection): SqlStr = SqlStr.raw(direction.toString)

  /** The candidate relation's columns. Naming them on the CTE keeps the projection free to alias its own columns however. */
  private val narrowColumns: SqlStr = SqlStr.raw("(id, day, sort_value)")

  /** The asset columns the grouped statement selects, in the order [[AssetRow]] declares them, so a row reads back positionally */
  private val assetColumns: SqlStr =
    SqlStr.raw(Table.labels(AssetRow).map(Db.config.columnNameMapper).map(name => s"asset.$name").mkString(", "))

  /** Assets whose search document matches the text */
  private def textFilter(engine: SearchDialect, asset: AssetRow[Expr], text: String, repositoryId: String): Expr[Boolean] =
    import engine.dialect.*

    SearchDocumentRow.select
      .filter(document => (document.repositoryId `=` repositoryId) && engine.textMatch(document, text))
      .map(_.assetId)
      .contains(asset.id)

  /**
   * Assets carrying every one of the requested metadata values.
   *
   * One row of `metadata_parameter` holds one value, so an asset matching `n` filters has `n` matching rows: the filters are
   * OR-ed and the count of what survives has to reach the number of filters.
   */
  private def metadataFilter(
      engine: SearchDialect,
      asset: AssetRow[Expr],
      query: SearchQuery,
      repositoryId: String): Expr[Boolean] =
    import engine.dialect.*

    MetadataParameterRow.select
      .filter {
        parameter =>
          val values = query.metadataFilters.toList.map((fieldId, value) => valueMatch(engine, parameter, fieldId, value))
          (parameter.repositoryId `=` repositoryId) && values.reduce(_ || _)
      }
      .groupBy(_.assetId)(_.size)
      .filter(_._2 >= query.metadataFilters.size)
      .map(_._1)
      .contains(asset.id)

  /** One metadata filter: the value goes into the column of its type, so searching a keyword field by number matches nothing */
  private def valueMatch(
      engine: SearchDialect,
      parameter: MetadataParameterRow[Expr],
      fieldId: String,
      value: Any): Expr[Boolean] =
    import engine.dialect.*

    val plain = value match
      case param: QueryParam if param.paramType == Query.ParamType.EQ => param.values.head
      case param: QueryParam => throw IllegalArgumentException(s"This type of parameter is not supported: ${param.paramType}")
      case other => other

    val column = plain match
      case _: String => parameter.fieldValueKw
      case _: Boolean => parameter.fieldValueBool
      case _: Number => parameter.fieldValueNum
      case other => throw IllegalArgumentException(s"This type of parameter is not supported: $other")

    (parameter.fieldId `=` fieldId) && Columns.equalTo(column, plain, engine.dialect)

  /** Assets any of the given people appear in */
  private def personFilter(engine: SearchDialect, asset: AssetRow[Expr], personIds: Set[String]): Expr[Boolean] =
    import engine.dialect.*

    FaceRow.select
      .join(PersonRow)((face, person) => face.personId `=` person.id)
      .filter((_, person) => Columns.isIn(person.id, personIds, engine.dialect))
      .map((face, _) => face.assetId)
      .contains(asset.id)

  /** Assets any of the given albums point at */
  private def albumFilter(engine: SearchDialect, asset: AssetRow[Expr], albumIds: Set[String]): Expr[Boolean] =
    import engine.dialect.*

    AlbumAssetRow.select
      .filter(link => Columns.isIn(link.albumId, albumIds, engine.dialect))
      .map(_.assetId)
      .contains(asset.id)
