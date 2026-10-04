package altitude.core.dao.sql.search

import java.time.LocalDate
import scalasql.Sc
import scalasql.Table
import scalasql.core.Context
import scalasql.core.Expr
import scalasql.core.JoinNullable
import scalasql.core.SqlStr
import scalasql.core.SqlStr.SqlStringSyntax
import scalasql.core.TypeMapper
import scalasql.core.WithSqlExpr
import scalasql.query.Select

import altitude.core.FieldConst
import altitude.core.dao.sql.Columns
import altitude.core.dao.sql.Db
import altitude.core.dao.sql.DynamicFilter
import altitude.core.dao.sql.tables.AlbumAssetRow
import altitude.core.dao.sql.tables.AssetRow
import altitude.core.dao.sql.tables.FaceRow
import altitude.core.dao.sql.tables.LocationAssetRow
import altitude.core.dao.sql.tables.LocationRow
import altitude.core.dao.sql.tables.MetadataParameterRow
import altitude.core.dao.sql.tables.PersonRow
import altitude.core.dao.sql.tables.SearchDocumentRow
import altitude.core.models.LocationKind
import altitude.core.util.BoundingBox
import altitude.core.util.Query
import altitude.core.util.Query.QueryParam
import altitude.core.util.ResolvedSearchGroup
import altitude.core.util.ResolvedSearchTerm
import altitude.core.util.ResolvedSearchText
import altitude.core.util.SearchCursor
import altitude.core.util.SearchQuery
import altitude.core.util.SearchSort
import altitude.core.util.SearchSource
import altitude.core.util.SortDirection
import altitude.core.util.SortValue

/**
 * A search as typed relations, rendered into hand-written statements.
 *
 * Every shape of search - the flat page, each slice of a grouped page, each of its counts, the map's aggregates - starts from the
 * same [[matching]] relation, so a count can never disagree with the rows it is counting. Each is a hand-written shell over that
 * typed relation, and every shell is built through [[statement]], which puts the Search text's CTEs at its head: the relation
 * tests membership in them, so a shell cannot leave them out.
 *
 * Each of the joined-in filters is a semi-join on `asset.id` rather than a comma join: a search then reads `FROM asset` alone,
 * with no `GROUP BY`/`HAVING` on the outer query and no duplicate rows to deduplicate.
 *
 * These are pure functions of their arguments; the repository is passed in rather than read from `RequestContext`.
 */
object SearchQueries:

  /**
   * Separates a category's name from its Location's in a path key: the control character U+0001. It sorts below every printable
   * character, so the composite key orders exactly as the pair (category name, Location name) would, and a Location's key cannot
   * collide with a top-level one.
   */
  private val PATH_SEPARATOR: String = 1.toChar.toString

  /** Every asset a search matches, unordered and unpaged; with Search text it refers to the text's CTEs ([[statement]]) */
  def matching(engine: SearchDialect, query: SearchQuery, repositoryId: String): Select[AssetRow[Expr], AssetRow[Sc]] =
    import engine.dialect.*

    AssetRow.select
      .filter(asset => (asset.repositoryId `=` repositoryId) && (asset.isPipelineProcessed `=` true))
      .filter(asset => DynamicFilter(AssetRow, Columns.of(AssetRow, asset, engine.dialect), query, engine.dialect))
      .filterIf(query.folderIds.nonEmpty)(asset => folderFilter(engine, asset, query.folderIds))
      .filterIf(query.isText)(asset => textFilter(engine, asset, query.requireResolvedText))
      .filterIf(query.hasMetadataFilters)(asset => metadataFilter(engine, asset, query, repositoryId))
      .filterIf(query.personIds.nonEmpty)(asset => personFilter(engine, asset, query.personIds))
      .filterIf(query.albumIds.nonEmpty)(asset => albumFilter(engine, asset, query.albumIds))
      .filterIf(query.locationIds.nonEmpty)(asset => locationFilter(engine, asset, query.locationIds))
      .filterIf(query.bbox.isDefined)(asset => bboxFilter(engine, asset, query, repositoryId, query.bbox.get))

  /** The people a Search text resolves names against, as (ID, name): named, visible, live and not a bad match */
  def searchablePeople(engine: SearchDialect, repositoryId: String): Select[(Expr[String], Expr[String]), (String, String)] =
    import engine.dialect.*

    PersonRow.select
      .filter {
        person =>
          (person.repositoryId `=` repositoryId) && (person.isNamed `=` true) && (person.isHidden `=` false) &&
          (person.isDeleted `=` false) && (person.isBadMatch `=` false)
      }
      .map(person => (person.id, person.name))

  /**
   * How many assets each folder of the repository holds itself, as (folder ID, count): the default view's matches grouped by
   * folder, so a folder's count is what clicking it shows. It reads the capture-day index alone, which carries the folder.
   */
  def folderCounts(engine: SearchDialect, repositoryId: String): Select[(Expr[String], Expr[Int]), (String, Int)] =
    import engine.dialect.*

    val defaultView = new SearchQuery(params = Map(FieldConst.Asset.IS_RECYCLED -> false))
    matching(engine, defaultView, repositoryId).groupBy(_.folderId)(_.size)

  /** The count of every match, exact however many there are: what merging people recounts a person's assets by */
  def count(engine: SearchDialect, query: SearchQuery, repositoryId: String): SqlStr =
    statement(
      engine,
      query,
      Nil,
      sql"SELECT count(*) FROM (${matchingIds(engine, matching(engine, query, repositoryId), None)}) AS m")

  /**
   * The count of the matches up to the query's cap, for a total the results UI shows: a result one past the cap means more than
   * the cap, and no more of them is read
   */
  def cappedCount(engine: SearchDialect, query: SearchQuery, repositoryId: String): SqlStr =
    statement(
      engine,
      query,
      Nil,
      sql"SELECT count(*) FROM (${cappedIds(engine, matching(engine, query, repositoryId), query.totalCap)}) AS m")

  /**
   * One page of a flat search: which assets are on it is decided over narrow rows, then only those are read in full.
   *
   * The `candidates` slice selects each match's ID, sort value and second sort value (the capture time under the Relevance sort,
   * which orders by it next), ordered by the sort, then the ID, as a grouped page is within a group: a missing capture time
   * creates a large tie group, and without the ID a page is not deterministic. It fetches one row past the page to say whether
   * another page follows, and a page past the first starts after its cursor's anchor ([[afterBySort]]), so no page reads the rows
   * of the pages before it. A first page also counts the matches, up to the cap.
   *
   * Sorted by capture time, the candidates are the day-ordered ones of a page grouped by day ([[dayCandidates]]), the days in the
   * sort's direction: the capture day followed by the capture time is the order of the capture time alone, and it is the order of
   * the capture-day index, which the engines would not read in order for the time alone. Under the Relevance sort the candidates
   * read `scored` ([[scoredCte]]), so the cursor compares a Relevance computed once.
   *
   * A range of sort values never reaches NULL, so a sort that puts the rows without a value last reads them in a second slice
   * ([[candidatesCte]]), as a page ordered by day does.
   */
  def flat(engine: SearchDialect, query: SearchQuery, repositoryId: String): SqlStr =
    import engine.dialect.*
    given TypeMapper[SortValue] = engine.sortValueMapper

    if query.rpp < 1 then throw IllegalArgumentException("A flat page needs a page size")

    val base = matching(engine, query, repositoryId)
    val asset = WithSqlExpr.get(base)
    val sort = query.searchSort.headOption
    val byCaptureDay = sort.filter(_.field == FieldConst.Asset.ORIGINAL_CREATED_AT)
    val isRelevance = sort.exists(_.isRelevance)

    // Where the sort puts the rows without a value last, a continuation from a row that has one reads them in a second slice
    val needsNullSlice = query.cursor.exists(_.sortValue != SortValue.Null) &&
      sort.exists(by => engine.isNullableTimestamp(by.field) && !engine.nullsFirst(by.direction))

    /** The candidates of a sort that is not by capture time: after the cursor's anchor, in the sort's order, then the ID's */
    def candidatesOf[Q, R](relation: Select[Q, R], position: Position): SqlStr =
      def slice(filter: Option[Expr[Boolean]], limit: SqlStr): SqlStr =
        sliced(
          engine,
          filter
            .fold(relation)(isIn => relation.filter(_ => isIn))
            .map(
              _ => (position.id, position.day, sortValueOf(position), sort.fold(nullSortValue)(secondSortValueOf(_, position)))),
          sort.fold(sql"${position.id} ASC")(_ => orderWithinGroup(engine, query, position)),
          limit
        )

      candidatesCte(
        slice(
          query.cursor.map(_ => afterBySort(engine, query, position, idOnly = sort.isEmpty, seeks = true)),
          SqlStr.raw(s" LIMIT ${query.rpp + 1}")),
        Option.when(needsNullSlice)(
          slice(Some(Expr[Boolean](implicit ctx => sql"${position.sortValue} IS NULL")), guardedLimit(LEADING_SLICE, query.rpp)))
      )

    val candidates = byCaptureDay match
      case Some(byDay) => dayCandidates(engine, query, base, byDay.field, byDay.direction)
      case None if isRelevance =>
        val scored = ScoredRow.select
        candidatesOf(scored, scoredPosition(WithSqlExpr.get(scored)))
      case None =>
        val sortValue = sort.fold[Expr[?]](nullSortValue)(_ => sortColumn(engine, query, asset))
        candidatesOf(base, Position(asset.id, nullDay, sortValue, asset.originalCreatedAt))

    val total = totalFragments(engine, base, query.cursor.isEmpty, query.totalCap)

    def order(prefix: String): SqlStr =
      SqlStr.raw(
        byCaptureDay.fold("")(byDay => s"${prefix}day ${byDay.direction}, ") +
          sort.fold(s"${prefix}id ASC")(candidateOrderWithinGroup(prefix, _)))

    val ctes = Option.when(isRelevance)(scoredCte(engine, query, base, None)).toSeq ++ Seq(
      candidates,
      sql"""page $narrowColumns AS MATERIALIZED (
        SELECT $narrowColumnList FROM candidates
         ORDER BY ${order("")}
         LIMIT ${SqlStr.raw(query.rpp.toString)}
      )"""
    ) ++ total.cte

    statement(
      engine,
      query,
      ctes,
      sql"""
      SELECT $assetColumns, p.day AS day, p.sort_value AS sort_value, p.second_sort_value AS second_sort_value,
             (SELECT count(*) FROM candidates) AS candidate_count${total.column}
        FROM page AS p
             JOIN asset ON asset.id = p.id
             ${total.join}
       ORDER BY ${order("p.")}
      """
    )

  /**
   * The candidates of a page ordered by day, as its CTEs: the narrow rows (ID, day, sort key) of the matches after the cursor's
   * anchor, in day order and then in the sort's, one row past the page. Under the Relevance sort they read `scored`. Where the
   * days' direction puts the rows without a day last, a continuation reads them in a second slice ([[candidatesCte]]).
   */
  private def dayCandidates(
      engine: SearchDialect,
      query: SearchQuery,
      base: Select[AssetRow[Expr], AssetRow[Sc]],
      dateField: String,
      direction: SortDirection)(using TypeMapper[SortValue]): SqlStr =
    import engine.dialect.*

    val sort = query.searchSort.head
    val asset = WithSqlExpr.get(base)
    val scored = ScoredRow.select
    val cursorDay = query.cursor.flatMap(_.key).map(LocalDate.parse)

    /** The narrow candidate relation, ordered and sliced in the context that names its own columns */
    def slice[Q, R](relation: Select[Q, R], position: Position, extra: Option[Position => Expr[Boolean]], limit: SqlStr): SqlStr =
      sliced(
        engine,
        extra
          .fold(relation)(isAfter => relation.filter(_ => isAfter(position)))
          .map(_ => (position.id, position.day, sortValueOf(position), secondSortValueOf(sort, position))),
        sql"${position.day} ${towards(direction)}, ${orderWithinGroup(engine, query, position)}",
        limit
      )

    // The slices read the matches themselves or, under the Relevance sort, the scored matches
    def candidates(extra: Option[Position => Expr[Boolean]], limit: SqlStr): SqlStr =
      if sort.isRelevance then slice(scored, scoredPosition(WithSqlExpr.get(scored)), extra, limit)
      else
        slice(
          base,
          Position(asset.id, engine.day(asset, dateField), sortColumn(engine, query, asset), asset.originalCreatedAt),
          extra,
          limit)

    val continuation =
      query.cursor.map(_ => (position: Position) => afterDay(engine, query, cursorDay, dateField, direction, position))
    // Only a transition from dated rows to a trailing null block needs the second slice
    val needsUndatedSlice = engine.isNullableTimestamp(dateField) && !engine.nullsFirst(direction) && cursorDay.isDefined

    candidatesCte(
      candidates(continuation, SqlStr.raw(s" LIMIT ${query.rpp + 1}")),
      Option.when(needsUndatedSlice)(
        candidates(
          Some(position => Expr[Boolean](implicit ctx => sql"${position.day} IS NULL")),
          guardedLimit(LEADING_SLICE, query.rpp)))
    )

  /** The slice of the rows that have a value for what orders them, which a slice of the rows that have none may follow */
  private val LEADING_SLICE = "dated"

  /**
   * The candidates of a page as its CTEs: the leading slice alone or, with a slice of the rows whose ordering value is NULL, the
   * two in turn. A range of values never reaches NULL, so where NULL sorts last a continuation needs the second slice, which
   * fills the page only once the leading one has run out. Its guard is in LIMIT ([[guardedLimit]]): SQLite short-circuits a zero
   * limit, but would scan the null block for a WHERE guard.
   */
  private def candidatesCte(leading: SqlStr, trailingNulls: Option[SqlStr]): SqlStr =
    trailingNulls.fold(sql"candidates $narrowColumns AS MATERIALIZED ($leading)") {
      trailing =>
        sql"""${SqlStr.raw(LEADING_SLICE)} $narrowColumns AS MATERIALIZED ($leading), undated $narrowColumns AS MATERIALIZED ($trailing),
          candidates $narrowColumns AS MATERIALIZED (
            SELECT $narrowColumnList FROM ${SqlStr.raw(LEADING_SLICE)} UNION ALL SELECT $narrowColumnList FROM undated)"""
    }

  /**
   * One statement for a page grouped by day: the ordered page slice joined back to its asset rows, the full-day count of each day
   * on the page and, on a first page, the count of every match. Every branch renders from the same [[matching]] relation, so
   * counts can never drift from the rows and every bind value travels with the fragment that needs it. The candidate slices are
   * narrow (ID, day, sort key) and fetch one row past the page to detect continuation; only the page's rows are joined to the
   * asset table. Every branch is materialized: the candidates because they are read twice, the counts so a day count runs once
   * per distinct day, not once per page row.
   *
   * Under the Relevance sort the statement first materializes `scored` ([[scoredCte]]): every match with its day and its
   * Relevance, computed once, which the slices, the cursor comparison and the order then read. No index orders a computed value,
   * so nothing is lost by reading every match first.
   *
   * A page reached by cursor skips the overall count: the footer total was set by the first page. Ordering is day, then the sort,
   * then the ID as a deterministic tiebreaker. Nulls fall where the engine puts them natively; an explicit NULLS clause would
   * forfeit index-ordered reads on both engines. The Relevance sort is the exception ([[orderWithinGroup]]): its capture-time
   * tiebreaker places them itself.
   *
   * The shell is hand-written on purpose: `MATERIALIZED`, the guarded `LIMIT CASE`, SQLite's planner hint and native null
   * placement are all tuned against the two engines' plans, and none of them can be expressed through a typed query.
   */
  def grouped(engine: SearchDialect, query: SearchQuery, repositoryId: String): SqlStr =
    given TypeMapper[SortValue] = engine.sortValueMapper

    val grouping = query.grouping.getOrElse(throw IllegalArgumentException("A grouped search needs a grouping"))
    val dateField = grouping.by.dateField.getOrElse(throw IllegalArgumentException("A day grouping needs a date field"))
    val sort = query.searchSort.head
    val base = matching(engine, query, repositoryId)

    // A grouping timestamp the schema lets be null gives its rows their own group, at the engine's native null position
    val ownGroup = engine.isNullableTimestamp(dateField)
    val isFirstPage = query.cursor.isEmpty

    def day(row: AssetRow[Expr]): Expr[Option[LocalDate]] = engine.day(row, dateField)
    def isUndated(row: AssetRow[Expr]): Expr[Boolean] = Expr[Boolean](implicit ctx => sql"${day(row)} IS NULL")

    val candidatesCte = dayCandidates(engine, query, base, dateField, grouping.direction)

    // Separate equality and IS NULL probes keep both counts on the day index. The null driver is empty on dated-only pages.
    val isCursorDay = (row: AssetRow[Expr]) => Expr[Boolean](implicit ctx => sql"${day(row)} = p.day")
    val datedDriver = if ownGroup then SqlStr.raw(" WHERE day IS NOT NULL") else SqlStr.empty
    val nullCount =
      if ownGroup then sql""" UNION ALL SELECT p.day AS day,
          (SELECT count(*) FROM (${matchingIds(engine, base, Some(isUndated))}) AS m) AS n
          FROM (SELECT DISTINCT day FROM page WHERE day IS NULL) AS p"""
      else SqlStr.empty
    val nullJoin = if ownGroup then SqlStr.raw(" OR (d.day IS NULL AND p.day IS NULL)") else SqlStr.empty

    val total = totalFragments(engine, base, isFirstPage, query.totalCap)

    def order(prefix: String): SqlStr =
      SqlStr.raw(s"${prefix}day ${grouping.direction}, ${candidateOrderWithinGroup(prefix, sort)}")

    val ctes = Option.when(sort.isRelevance)(scoredCte(engine, query, base, Some(day))).toSeq ++ Seq(
      candidatesCte,
      sql"""page $narrowColumns AS MATERIALIZED (
        SELECT $narrowColumnList FROM candidates
         ORDER BY ${order("")}
         LIMIT ${SqlStr.raw(query.rpp.toString)}
      )""",
      sql"""day_counts AS MATERIALIZED (
        SELECT p.day AS day,
               (SELECT count(*) FROM (${matchingIds(engine, base, Some(isCursorDay))}) AS m) AS n
          FROM (SELECT DISTINCT day FROM page$datedDriver) AS p$nullCount
      )"""
    ) ++ total.cte

    statement(
      engine,
      query,
      ctes,
      sql"""
      SELECT $assetColumns, p.day AS day, p.sort_value AS sort_value, p.second_sort_value AS second_sort_value, d.n AS day_total,
             (SELECT count(*) FROM candidates) AS candidate_count${total.column}
        FROM page AS p
             JOIN asset ON asset.id = p.id
             LEFT JOIN day_counts AS d ON d.day = p.day$nullJoin
             ${total.join}
       ORDER BY ${order("p.")}
      """
    )

  /**
   * One statement for a page grouped by Location: the shell of [[grouped]] over two relations instead of one. `located` is the
   * matches joined to their Locations - an asset once per Location it is in, so an asset in two Locations appears under both -
   * and `unlocated` is the matches in no Location, which always come last as the "No location" group. Under the Relevance sort
   * both read `scored`, the matches with their Relevance computed once, as the day statement does.
   *
   * Locations are in path order: the key is the category's lower-cased name and the Location's own joined by [[PATH_SEPARATOR]],
   * or the Location's name alone at the top level - the order the sidebar lists them in - then the Location's ID as a
   * deterministic tiebreaker, then the sort within the group, then the asset ID. A cursor whose anchor was in a Location slices
   * `located` after it and lets `unlocated` fill the page only once `located` has run out, through the same guarded `LIMIT CASE`
   * as the day statement's null slice; a cursor without a group key is already in the trailing group and slices `unlocated`
   * alone. A group's count is the matching assets in that Location (or in none); the overall total on a first page is the number
   * of matching assets, so the group counts may sum to more than it.
   */
  def groupedByLocation(engine: SearchDialect, query: SearchQuery, repositoryId: String): SqlStr =
    import engine.dialect.*
    given TypeMapper[SortValue] = engine.sortValueMapper

    val sort = query.searchSort.head
    val base = matching(engine, query, repositoryId)
    val scored = ScoredRow.select
    val isFirstPage = query.cursor.isEmpty
    // A cursor whose anchor was in a Location continues the located part; one without a key is in the trailing group already
    val inLocatedPart = query.cursor.forall(_.key.isDefined)

    def assetPosition(row: AssetRow[Expr]): Position =
      Position(row.id, nullDay, sortColumn(engine, query, row), row.originalCreatedAt)
    def inNoLocation(id: Expr[String]): Expr[Boolean] =
      LocationAssetRow.select.filter(link => link.assetId `=` id).map(_.assetId).isEmpty
    def nullText: Expr[Option[String]] = Expr[Option[String]](implicit ctx => sql"NULL")

    /** The located slice: each match under each of its Locations, in path order, after the cursor's anchor when it has one */
    def locatedSlice[Q, R](
        relation: Select[Q, R],
        position: Position,
        location: LocationRow[Expr],
        category: JoinNullable[LocationRow[Expr]]): SqlStr =
      val categoryName: Expr[Option[String]] = category.map(_.name)
      val pathKey =
        Expr[String](implicit ctx => sql"COALESCE(${category.get.nameLc} || $PATH_SEPARATOR, '') || ${location.nameLc}")

      /** Rows strictly after the anchor: a later path key, or the anchor's own Location's rows after it by sort */
      def afterLocation(cursor: SearchCursor): Expr[Boolean] =
        val key = cursor.key.get
        val groupId = cursor.groupId.getOrElse(throw IllegalArgumentException("A Location cursor needs its group ID"))
        val after = afterBySort(engine, query, position, idOnly = false)
        Expr[Boolean] {
          implicit ctx =>
            sql"($pathKey > $key OR ($pathKey = $key AND (${location.id} > $groupId OR (${location.id} = $groupId AND $after))))"
        }

      sliced(
        engine,
        relation
          .filterIf(query.cursor.isDefined && inLocatedPart)(_ => afterLocation(query.cursor.get))
          .map(
            _ =>
              (
                position.id,
                location.id,
                pathKey,
                location.name,
                categoryName,
                sortValueOf(position),
                secondSortValueOf(sort, position))),
        sql"$pathKey ASC, ${location.id} ASC, ${orderWithinGroup(engine, query, position)}",
        SqlStr.raw(s" LIMIT ${query.rpp + 1}")
      )

    /** The trailing slice: the matches in no Location, after the cursor's anchor when the anchor was among them */
    def unlocatedSlice[Q, R](relation: Select[Q, R], position: Position): SqlStr =
      sliced(
        engine,
        relation
          .filterIf(!inLocatedPart)(_ => afterBySort(engine, query, position, idOnly = false))
          .map(
            _ => (position.id, nullText, nullText, nullText, nullText, sortValueOf(position), secondSortValueOf(sort, position))),
        sql"${orderWithinGroup(engine, query, position)}",
        if inLocatedPart then guardedLimit("located", query.rpp) else SqlStr.raw(s" LIMIT ${query.rpp + 1}")
      )

    // The slices read the matches themselves or, under the Relevance sort, the scored matches
    val located =
      if sort.isRelevance then
        val relation = scored
          .join(LocationAssetRow)((row, link) => row.id `=` link.assetId)
          .join(LocationRow)((row, location) => row._2.locationId `=` location.id)
          .leftJoin(LocationRow)((row, category) => Expr[Boolean](implicit ctx => sql"${row._3.categoryId} = ${category.id}"))
        val ((row, _, location), category) = WithSqlExpr.get(relation)
        locatedSlice(relation, scoredPosition(row), location, category)
      else
        val relation = base
          .join(LocationAssetRow)((asset, link) => asset.id `=` link.assetId)
          .join(LocationRow)((row, location) => row._2.locationId `=` location.id)
          .leftJoin(LocationRow)((row, category) => Expr[Boolean](implicit ctx => sql"${row._3.categoryId} = ${category.id}"))
        val ((asset, _, location), category) = WithSqlExpr.get(relation)
        locatedSlice(relation, assetPosition(asset), location, category)

    val unlocated =
      if sort.isRelevance then
        val relation = scored.filter(row => inNoLocation(row.id))
        unlocatedSlice(relation, scoredPosition(WithSqlExpr.get(relation)))
      else
        val relation = base.filter(row => inNoLocation(row.id))
        unlocatedSlice(relation, assetPosition(WithSqlExpr.get(relation)))

    val candidatesCte =
      if inLocatedPart then
        sql"""located $locationColumns AS MATERIALIZED ($located), unlocated $locationColumns AS MATERIALIZED ($unlocated),
          candidates $locationColumns AS MATERIALIZED (
            SELECT $locationColumnList FROM located UNION ALL SELECT $locationColumnList FROM unlocated)"""
      else sql"candidates $locationColumns AS MATERIALIZED ($unlocated)"

    // One count per distinct Location on the page, read from that Location's own memberships under the search's predicates, so
    // its cost is the size of the page's Locations and not of the library. The no-location count, a count of the matches in no
    // Location, runs only when the page reaches that group.
    val pageLocationMembers = Db
      .render(
        base
          .join(LocationAssetRow)((asset, link) => asset.id `=` link.assetId)
          .filter((_, link) => Expr[Boolean](implicit ctx => sql"${link.locationId} = p.location_id"))
          .map((asset, _) => asset.id),
        engine.dialect
      )
      .withCompleteQuery(false)

    val total = totalFragments(engine, base, isFirstPage, query.totalCap)

    // Located rows first whatever the engine's null placement, then path order
    def order(prefix: String): SqlStr = SqlStr.raw(
      s"CASE WHEN ${prefix}location_id IS NULL THEN 1 ELSE 0 END, ${prefix}path_key ASC, ${prefix}location_id ASC, " +
        candidateOrderWithinGroup(prefix, sort))

    val ctes = Option.when(sort.isRelevance)(scoredCte(engine, query, base, None)).toSeq ++ Seq(
      candidatesCte,
      sql"""page $locationColumns AS MATERIALIZED (
        SELECT $locationColumnList FROM candidates AS c
         ORDER BY ${order("c.")}
         LIMIT ${SqlStr.raw(query.rpp.toString)}
      )""",
      sql"""group_counts AS MATERIALIZED (
        SELECT p.location_id AS location_id,
               (SELECT count(*) FROM ($pageLocationMembers) AS m) AS n
          FROM (SELECT DISTINCT location_id FROM page WHERE location_id IS NOT NULL) AS p
        UNION ALL
        SELECT p.location_id AS location_id,
               (SELECT count(*) FROM (${matchingIds(engine, base, Some(row => inNoLocation(row.id)))}) AS m) AS n
          FROM (SELECT DISTINCT location_id FROM page WHERE location_id IS NULL) AS p
      )"""
    ) ++ total.cte

    statement(
      engine,
      query,
      ctes,
      sql"""
      SELECT $assetColumns, p.location_id AS location_id, p.path_key AS path_key, p.location_name AS location_name,
             p.category_name AS category_name, p.sort_value AS sort_value, p.second_sort_value AS second_sort_value,
             g.n AS group_total,
             (SELECT count(*) FROM candidates) AS candidate_count${total.column}
        FROM page AS p
             JOIN asset ON asset.id = p.id
             LEFT JOIN group_counts AS g ON g.location_id = p.location_id OR (g.location_id IS NULL AND p.location_id IS NULL)
             ${total.join}
       ORDER BY ${order("p.")}
      """
    )

  /**
   * Every point the map plots for a search, one row per plotted point, with the asset's ID and capture time: a matching asset
   * with a point of its own is plotted there, and only there; one without is plotted at the pin of each Location it is in, so an
   * asset in two Locations is two points, as it is two cells in the Location grid. With a box, only the points inside it.
   *
   * Rendered as a `UNION ALL` of the two typed relations; each carries every filter of the search, so the points can never
   * disagree with the grid.
   */
  private def plottedPoints(engine: SearchDialect, query: SearchQuery, repositoryId: String, bbox: Option[BoundingBox]): SqlStr =
    import engine.dialect.*

    val base = matching(engine, query, repositoryId)

    val own = base
      .filter(asset => asset.latitude.isDefined)
      .filterIf(bbox.isDefined)(asset => inBox(engine, bbox.get, asset.latitude, asset.longitude))
      .map(asset => (asset.id, asset.latitude, asset.longitude, asset.originalCreatedAt))

    val pinned = base
      .filter(asset => asset.latitude.isEmpty)
      .join(LocationAssetRow)((asset, link) => asset.id `=` link.assetId)
      .join(LocationRow)((row, location) => row._2.locationId `=` location.id)
      .filterIf(bbox.isDefined)((_, _, location) => inBox(engine, bbox.get, location.latitude, location.longitude))
      .map((asset, _, location) => (asset.id, location.latitude, location.longitude, asset.originalCreatedAt))

    sql"${Db.render(own, engine.dialect).withCompleteQuery(false)} UNION ALL ${Db.render(pinned, engine.dialect).withCompleteQuery(false)}"

  /** The plotted points as a named CTE, for the statements that aggregate them */
  private def pointsCte(engine: SearchDialect, query: SearchQuery, repositoryId: String, bbox: Option[BoundingBox]): SqlStr =
    sql"points (asset_id, latitude, longitude, taken) AS (${plottedPoints(engine, query, repositoryId, bbox)})"

  /**
   * One statement for the map's cells in a viewport: the plotted points in the box, each assigned to a square cell of
   * `cellDegrees` a side by flooring its coordinates, then one window pass per cell for the count, the centroid and the rank of
   * each point by newest capture time (nulls last, whatever the engine's native placement) and then ID; the rank-one row of each
   * cell is the cell, and its asset represents it. `floor` is built into both engines.
   */
  def mapCells(engine: SearchDialect, query: SearchQuery, repositoryId: String, bbox: BoundingBox, cellDegrees: Double): SqlStr =
    import engine.dialect.*

    statement(
      engine,
      query,
      Seq(
        pointsCte(engine, query, repositoryId, Some(bbox)),
        sql"""gridded AS (
        SELECT asset_id, latitude, longitude, taken, floor(longitude / $cellDegrees) AS cell_x, floor(latitude / $cellDegrees) AS cell_y
          FROM points
      )""",
        sql"""cells AS (
        SELECT asset_id, cell_x, cell_y,
               COUNT(*) OVER w AS n, AVG(latitude) OVER w AS latitude, AVG(longitude) OVER w AS longitude,
               ROW_NUMBER() OVER (PARTITION BY cell_x, cell_y ORDER BY CASE WHEN taken IS NULL THEN 1 ELSE 0 END, taken DESC, asset_id ASC) AS rn
          FROM gridded
        WINDOW w AS (PARTITION BY cell_x, cell_y)
      )"""
      ),
      sql"SELECT n, latitude, longitude, asset_id FROM cells WHERE rn = 1 ORDER BY cell_y, cell_x"
    )

  /**
   * The Locations pinned inside the box that hold at least one matching asset, with that count and their category's name, in one
   * statement: the matches among the members of the Locations in the box are read once into `matched`, so it is as large as those
   * memberships and not as the library, then the repository's Locations in the box are joined to their memberships among them and
   * grouped. The count agrees with the grid scoped to the Location.
   */
  def mapLocations(engine: SearchDialect, query: SearchQuery, repositoryId: String, bbox: BoundingBox): SqlStr =
    import engine.dialect.*

    def column(name: String): Expr[Option[Double]] = Expr[Option[Double]](_ => SqlStr.raw(name))
    val inTheBox = inBox(engine, bbox, column("location.latitude"), column("location.longitude"))

    statement(
      engine,
      query,
      Seq(
        sql"matched (id) AS MATERIALIZED (${matchingIds(engine, matching(engine, query, repositoryId), Some(asset => membersOfLocationsIn(engine, bbox).contains(asset.id)))})"),
      sql"""
      SELECT location.id, location.name, category.name, location.latitude, location.longitude, count(*)
        FROM location
             JOIN location_asset ON location_asset.location_id = location.id
             JOIN matched ON matched.id = location_asset.asset_id
             LEFT JOIN location AS category ON category.id = location.category_id
       WHERE location.repository_id = $repositoryId AND location.kind = ${LocationKind.Location.dbValue} AND ${Db.render(inTheBox, engine.dialect)}
       GROUP BY location.id, location.name, category.name, location.latitude, location.longitude
      """
    )

  /**
   * One aggregate over every plotted point of the search: the box around them and their count, for fitting the map to a result.
   * With nothing plotted the extremes are NULL and the count is zero.
   */
  def mapBounds(engine: SearchDialect, query: SearchQuery, repositoryId: String): SqlStr =
    statement(
      engine,
      query,
      Seq(pointsCte(engine, query, repositoryId, None)),
      sql"SELECT min(latitude), max(latitude), min(longitude), max(longitude), count(*) FROM points"
    )

  /**
   * One statement reading the hits of the Search text's positive groups, those none of whose alternatives is excluded: for each,
   * the union of its sources' asset IDs, each source within the repository ([[sourceRelation]]), limited to one row past the
   * limit, so that a group with more hits than the limit costs no more than one with as many. Rows are (group index, asset ID),
   * an asset once per source it is a hit in. Nothing when no group is positive.
   */
  def textProbe(engine: SearchDialect, text: ResolvedSearchText, repositoryId: String, limit: Int): Option[SqlStr] =
    val branches = text.groups.zipWithIndex.filter(_._1.isPositive).map {
      (group, groupIndex) =>
        val hits = textSources(group, groupIndex).flatten.map(sourceRelation(engine, _, Some(repositoryId)))
        sql"""SELECT ${SqlStr.raw(groupIndex.toString)} AS grp, hits.* FROM (
          ${SqlStr.join(hits, sql" UNION ALL ")} LIMIT ${SqlStr.raw((limit + 1).toString)}) AS hits"""
    }
    Option.when(branches.nonEmpty)(SqlStr.join(branches, sql" UNION ALL "))

  /**
   * The one way a statement over a search is built: the Search text's CTEs, then the shell's own, then the shell's body. The
   * [[matching]] relation tests membership in the text's CTEs by name, so a statement built any other way would not run.
   */
  private def statement(engine: SearchDialect, query: SearchQuery, ctes: Seq[SqlStr], body: SqlStr): SqlStr =
    textCtes(engine, query) ++ ctes match
      case Seq() => body
      case all => sql"WITH " + SqlStr.join(all, SqlStr.raw(", ")) + sql" " + body

  /**
   * Where a row stands in a page's order, as the relation it is sliced from has it: its ID, its day under a day grouping, what it
   * is sorted by, and its capture time, which orders it next under the Relevance sort. A column sort reads them from the asset; a
   * grouped statement under the Relevance sort reads them from `scored`.
   */
  private case class Position(id: Expr[String], day: Expr[Option[LocalDate]], sortValue: Expr[?], taken: Expr[?])

  private def scoredPosition(row: ScoredRow[Expr]): Position = Position(row.id, row.day, row.sortValue, row.secondSortValue)

  /**
   * The matches with their Relevance computed once, for a grouped statement under the Relevance sort: what its slices, its cursor
   * comparison and its order read instead of computing the Relevance again ([[ScoredRow]]). A day grouping's day rides along.
   */
  private def scoredCte(
      engine: SearchDialect,
      query: SearchQuery,
      base: Select[AssetRow[Expr], AssetRow[Sc]],
      day: Option[AssetRow[Expr] => Expr[Option[LocalDate]]]): SqlStr =
    import engine.dialect.*

    val text = query.requireResolvedText
    val scores = base.map(row => (row.id, day.fold(nullDay)(_(row)), relevance(engine, row, text), row.originalCreatedAt))
    sql"scored (id, day, sort_value, second_sort_value) AS MATERIALIZED (${Db.render(scores, engine.dialect).withCompleteQuery(false)})"

  /**
   * Rows strictly after the cursor's anchor in day order, as lexicographic comparisons with independent directions: an earlier
   * day, or the same day and a later sort value, or the same sort value and a greater ID. A redundant inclusive bound on the day
   * lets the day index seek straight to the boundary.
   */
  private def afterDay(
      engine: SearchDialect,
      query: SearchQuery,
      cursorDay: Option[LocalDate],
      dateField: String,
      direction: SortDirection,
      position: Position): Expr[Boolean] =
    import engine.dialect.*

    val dayOp = SqlStr.raw(if direction == SortDirection.DESC then "<" else ">")
    val day = position.day
    // Sorting by capture time inside its null group reduces to ID order; every capture sort value there is NULL.
    val idOnly = cursorDay.isEmpty && query.searchSort.head.field == dateField
    val after = afterBySort(engine, query, position, idOnly)

    cursorDay match
      case Some(anchor) =>
        // The redundant inclusive bound seeks to the cursor day; a trailing null block is supplied by the guarded slice.
        Expr[Boolean](implicit ctx => sql"$day $dayOp= $anchor AND ($day $dayOp $anchor OR $after)")
      case None if engine.nullsFirst(direction) =>
        // All dated days follow the leading null group, regardless of the secondary sort.
        Expr[Boolean](implicit ctx => sql"($day IS NOT NULL OR $after)")
      case None =>
        Expr[Boolean](implicit ctx => sql"$day IS NULL AND $after")

  /**
   * Rows of the anchor's own group strictly after it: a later sort value, or the same sort value and a greater ID. Where the sort
   * column can be null, nulls sit where the engine natively orders them and the comparison honors that placement. With `idOnly`
   * the comparison is the ID alone: every sort value in the group is known to be null, or the search has no sort.
   *
   * Under the Relevance sort the position is a triple: between the Relevance and the ID comes the capture time
   * ([[afterByCaptureTime]]). The Relevance is read from `scored`, where it was computed once, and it is never null.
   *
   * With `seeks` the sort is the leading order of the slice: a redundant inclusive bound on the sort value lets an index on it
   * seek straight to the anchor, which the alternatives alone would not, and the rows without a value that follow an anchor with
   * one are left to the caller's second slice, since no range reaches them.
   */
  private def afterBySort(
      engine: SearchDialect,
      query: SearchQuery,
      position: Position,
      idOnly: Boolean,
      seeks: Boolean = false): Expr[Boolean] =
    import engine.dialect.*
    given TypeMapper[SortValue] = engine.sortValueMapper

    val cursor = query.cursor.get
    val afterById = Expr[Boolean](implicit ctx => sql"${position.id} > ${cursor.id}")

    if idOnly then afterById
    else
      val sort = query.searchSort.head
      val sortOp = SqlStr.raw(if sort.direction == SortDirection.DESC then "<" else ">")
      val column = position.sortValue

      cursor.sortValue match
        case SortValue.Null if engine.nullsFirst(sort.direction) =>
          Expr[Boolean](implicit ctx => sql"($column IS NOT NULL OR $afterById)")
        case SortValue.Null =>
          Expr[Boolean](implicit ctx => sql"($column IS NULL AND $afterById)")
        case value =>
          // What decides among the rows of the anchor's own sort value
          val afterTie =
            if sort.isRelevance then afterByCaptureTime(engine, cursor.requireSecondSortValue, position.taken, afterById)
            else afterById
          Expr[Boolean] {
            implicit ctx =>
              val after = sql"($column $sortOp $value OR ($column = $value AND $afterTie))"
              if seeks then sql"$column $sortOp= $value AND $after"
              else if engine.isNullableTimestamp(sort.field) && !engine.nullsFirst(sort.direction) then
                sql"($after OR $column IS NULL)"
              else after
          }

  /**
   * Rows of the anchor's own Relevance strictly after it, newest capture first: an older capture time, or the anchor's own and a
   * greater ID. Rows without a capture time are last on both engines, as [[orderWithinGroup]] places them: they all follow an
   * anchor that has one, and an anchor without one is followed only by those of them with a greater ID.
   */
  private def afterByCaptureTime(
      engine: SearchDialect,
      anchor: SortValue,
      taken: Expr[?],
      afterById: Expr[Boolean]): Expr[Boolean] =
    given TypeMapper[SortValue] = engine.sortValueMapper

    anchor match
      case SortValue.Null => Expr[Boolean](implicit ctx => sql"($taken IS NULL AND $afterById)")
      case value =>
        Expr[Boolean](implicit ctx => sql"($taken < $value OR $taken IS NULL OR ($taken = $value AND $afterById))")

  /** A relation with a hand-written ORDER BY, rendered in the context that names the relation's own columns */
  private def orderedBy[Q, R](select: Select[Q, R], order: Context ?=> SqlStr): Select[Q, R] =
    Select.withExprSuffix(
      Select.toSimpleFrom(select),
      false,
      ctx =>
        given Context = ctx
        sql" ORDER BY " + order
    )

  /** A narrow candidate relation with a hand-written ORDER BY and LIMIT */
  private def sliced[Q, R](engine: SearchDialect, projected: Select[Q, R], order: Context ?=> SqlStr, limit: SqlStr): SqlStr =
    Db.render(orderedBy(projected, order + limit), engine.dialect).withCompleteQuery(false)

  /** A trailing slice that fills the page only once the leading one has run out, which is when it fetched no more than a page */
  private def guardedLimit(leading: String, rpp: Int): SqlStr =
    SqlStr.raw(s" LIMIT CASE WHEN (SELECT count(*) FROM $leading) > $rpp THEN 0 ELSE ${rpp + 1} END")

  /** The matching IDs alone, for a count that has to agree with the rows */
  private def matchingIds(
      engine: SearchDialect,
      base: Select[AssetRow[Expr], AssetRow[Sc]],
      extra: Option[AssetRow[Expr] => Expr[Boolean]]): SqlStr =
    import engine.dialect.*
    Db.render(extra.fold(base)(base.filter).map(_.id), engine.dialect).withCompleteQuery(false)

  /** The matching IDs up to one past the cap: whether there are more than the cap is all a capped count needs to know */
  private def cappedIds(engine: SearchDialect, base: Select[AssetRow[Expr], AssetRow[Sc]], cap: Int): SqlStr =
    matchingIds(engine, base, None) + SqlStr.raw(s" LIMIT ${cap + 1}")

  /** A first page's overall count: its CTE, the column that reads it and the join that brings it in; nothing on a continuation */
  private case class TotalFragments(cte: Option[SqlStr], column: SqlStr, join: SqlStr)

  /** The overall count on a first page, counted up to one past the cap */
  private def totalFragments(
      engine: SearchDialect,
      base: Select[AssetRow[Expr], AssetRow[Sc]],
      isFirstPage: Boolean,
      cap: Int): TotalFragments =
    if isFirstPage then
      TotalFragments(
        Some(sql"total AS MATERIALIZED (SELECT count(*) AS n FROM (${cappedIds(engine, base, cap)}) AS m)"),
        SqlStr.raw(", t.n AS total"),
        SqlStr.raw("CROSS JOIN total AS t")
      )
    else TotalFragments(None, SqlStr.empty, SqlStr.empty)

  /** What a search sorts by: the sort's column or, under the Relevance sort, the Relevance computed from the Search text */
  private def sortColumn(engine: SearchDialect, query: SearchQuery, row: AssetRow[Expr]): Expr[?] =
    val sort = query.searchSort.head
    if sort.isRelevance then relevance(engine, row, query.requireResolvedText)
    else Columns.required(AssetRow, row, sort.field, engine.dialect)

  /** What a row is sorted by, read as the engine stores it, so a cursor can bind it back unchanged */
  private def sortValueOf(position: Position)(using TypeMapper[SortValue]): Expr[SortValue] =
    Expr[SortValue](implicit ctx => sql"${position.sortValue}")

  /**
   * What a candidate carries for the term after the sort: under the Relevance sort the capture time as stored, for the page's
   * order and the cursor; NULL under a column sort, whose next term is the ID, so every candidate relation has the same columns.
   */
  private def secondSortValueOf(sort: SearchSort, position: Position)(using TypeMapper[SortValue]): Expr[SortValue] =
    if sort.isRelevance then Expr[SortValue](implicit ctx => sql"${position.taken}") else nullSortValue

  /** The sort value of a page with no sort, whose only order is the ID, and the second sort value under a column sort */
  private def nullSortValue(using TypeMapper[SortValue]): Expr[SortValue] = Expr[SortValue](implicit ctx => sql"NULL")

  /** The day of a row that is in no day group: under a Location grouping, and on a flat page */
  private def nullDay: Expr[Option[LocalDate]] = Expr[Option[LocalDate]](implicit ctx => sql"NULL")

  /**
   * The ORDER BY terms within a group, over the position the slice reads: the sort, then the ID as a deterministic tiebreaker.
   * Under the Relevance sort the newest capture time comes between them, and the assets with none read last on both engines: no
   * index orders a computed value, so there is no index-ordered read for an explicit null placement to forfeit. The engine may
   * decorate a column sort's term to steer its planner.
   */
  private def orderWithinGroup(engine: SearchDialect, query: SearchQuery, position: Position)(using Context): SqlStr =
    val sort = query.searchSort.head
    val direction = towards(sort.direction)
    if sort.isRelevance then sql"${position.sortValue} $direction, ${position.taken} DESC NULLS LAST, ${position.id} ASC"
    else
      sql"${query.grouping.fold(position.sortValue)(engine.secondarySort(position.sortValue, sort, _))} $direction, ${position.id} ASC"

  /** [[orderWithinGroup]] over the columns of a candidate relation, which carries what the slice computed */
  private def candidateOrderWithinGroup(prefix: String, sort: SearchSort): String =
    val byCaptureTime = if sort.isRelevance then s"${prefix}second_sort_value DESC NULLS LAST, " else ""
    s"${prefix}sort_value ${sort.direction}, $byCaptureTime${prefix}id ASC"

  private def towards(direction: SortDirection): SqlStr = SqlStr.raw(direction.toString)

  /** The flat page's columns, named on its CTE like the candidates' */
  private val flatColumns: SqlStr = SqlStr.raw("(id, sort_value, second_sort_value)")

  /** The day candidates' columns. Naming them on the CTE keeps the projection free to alias its own columns however. */
  private val narrowColumnList: SqlStr = SqlStr.raw("id, day, sort_value, second_sort_value")
  private val narrowColumns: SqlStr = sql"($narrowColumnList)"

  /** The Location candidates' columns: the asset, its Location and what heads the group, and the sort keys */
  private val locationColumnList: SqlStr =
    SqlStr.raw("id, location_id, path_key, location_name, category_name, sort_value, second_sort_value")
  private val locationColumns: SqlStr = sql"($locationColumnList)"

  /** The asset columns the grouped statements select, in the order [[AssetRow]] declares them, so a row reads back positionally */
  private val assetColumns: SqlStr =
    SqlStr.raw(Table.labels(AssetRow).map(Db.config.columnNameMapper).map(name => s"asset.$name").mkString(", "))

  /**
   * One source of one alternative of one group of the Search text: where the term can match, with the IDs it resolved to there
   * (none for the Search document, which the engine matches by the term's words). Its name is its position in the text.
   */
  private case class TextSource(group: Int, alternative: Int, term: ResolvedSearchTerm, source: SearchSource, ids: Set[String]):
    val name: String = s"text_${group}_${alternative}_${source.toString.toLowerCase}"

    /** A folder is the asset's own column, which a statement tests directly; every other source is a relation of asset IDs */
    val isAssetColumn: Boolean = source == SearchSource.Folder

  /**
   * The sources of each alternative of a group, in the order they are declared: the name sources the term resolved to any IDs in,
   * then always its Search document
   */
  private def textSources(group: ResolvedSearchGroup, groupIndex: Int): Seq[Seq[TextSource]] =
    group.alternatives.zipWithIndex.map {
      (term, alternative) =>
        term.ids.toSeq
          .sortBy(_._1.ordinal)
          .map((source, ids) => TextSource(groupIndex, alternative, term, source, ids)) :+
          TextSource(groupIndex, alternative, term, SearchSource.Document, Set())
    }

  /** Every source of the Search text */
  private def allTextSources(text: ResolvedSearchText): Seq[TextSource] =
    text.groups.zipWithIndex.flatMap((group, groupIndex) => textSources(group, groupIndex).flatten)

  /**
   * The asset IDs a source matches, as a relation. The person, Location, album and folder IDs were resolved from the repository's
   * names, so those sources are within it by what they are asked for, and each reads the index that leads with its ID and carries
   * the asset, alone. The document is scoped by the asset that tests membership, so only a caller reading the relation on its own
   * (`repositoryId`) scopes it to the repository.
   */
  private def sourceRelation(engine: SearchDialect, source: TextSource, repositoryId: Option[String]): SqlStr =
    import engine.dialect.*

    def inSet(column: Expr[String]): Expr[Boolean] = Columns.isInSet(column, source.ids, engine.dialect)

    val relation = source.source match
      case SearchSource.Person => FaceRow.select.filter(face => inSet(face.personId)).map(_.assetId)
      case SearchSource.Location | SearchSource.Category =>
        LocationAssetRow.select.filter(link => inSet(link.locationId)).map(_.assetId)
      case SearchSource.Folder => AssetRow.select.filter(asset => inSet(asset.folderId)).map(_.id)
      case SearchSource.Album => AlbumAssetRow.select.filter(link => inSet(link.albumId)).map(_.assetId)
      case SearchSource.Document =>
        SearchDocumentRow.select
          .filter(document => engine.textMatch(document, source.term.term))
          .filterIf(repositoryId.isDefined)(document => document.repositoryId `=` repositoryId.get)
          .map(_.assetId)

    Db.render(relation, engine.dialect).withCompleteQuery(false)

  /**
   * The Search text's CTEs on the broad path: each source of each term built once, materialized, at the head of the statement, so
   * that every predicate, the Relevance and the counts beside the rows test membership in the same set. A folder is the asset's
   * own column and needs none, and the selective path tests the asset's own row instead.
   */
  private def textCtes(engine: SearchDialect, query: SearchQuery): Seq[SqlStr] =
    if !query.isText || query.requireResolvedText.candidates.isDefined then Nil
    else
      allTextSources(query.requireResolvedText)
        .filterNot(_.isAssetColumn)
        .map(source => sql"${SqlStr.raw(source.name)} (asset_id) AS MATERIALIZED (${sourceRelation(engine, source, None)})")

  /**
   * Whether the asset is among a source's matches: in one of its folders, or on the broad path in the source's CTE. On the
   * selective path it is a probe on the asset's own row, through an index that leads with the asset, for no more than the
   * candidates.
   */
  private def isIn(engine: SearchDialect, asset: AssetRow[Expr], text: ResolvedSearchText, source: TextSource): Expr[Boolean] =
    import engine.dialect.*

    def inSet(column: Expr[String]): Expr[Boolean] = Columns.isInSet(column, source.ids, engine.dialect)

    if source.isAssetColumn then folderFilter(engine, asset, source.ids)
    else if text.candidates.isEmpty then
      Expr[Boolean](implicit ctx => sql"(${asset.id} IN (SELECT asset_id FROM ${SqlStr.raw(source.name)}))")
    else
      source.source match
        case SearchSource.Person =>
          FaceRow.select.filter(face => (face.assetId `=` asset.id) && inSet(face.personId)).map(_.assetId).nonEmpty
        case SearchSource.Location | SearchSource.Category =>
          LocationAssetRow.select.filter(link => (link.assetId `=` asset.id) && inSet(link.locationId)).map(_.assetId).nonEmpty
        case SearchSource.Album =>
          AlbumAssetRow.select.filter(link => (link.assetId `=` asset.id) && inSet(link.albumId)).map(_.assetId).nonEmpty
        case SearchSource.Document =>
          SearchDocumentRow.select
            .filter(document => (document.assetId `=` asset.id) && engine.textMatch(document, source.term.term))
            .map(_.assetId)
            .nonEmpty
        case SearchSource.Folder => throw IllegalStateException("A folder is the asset's own column")

  /**
   * Assets that satisfy the Search text: every group, by any of its alternatives. A term is satisfied by any of its sources and
   * an excluded term by none of them, so an asset without the term's words anywhere (or without a document) satisfies an
   * exclusion. On the broad path a group of one excluded term is an anti-join on each of its sources, which the engine plans as
   * such; an excluded alternative among others negates its sources' memberships.
   *
   * On the selective path the asset is one of the candidates, which are already within every complete group's matches; the other
   * groups are tested on the asset's own row ([[isIn]]).
   */
  private def textFilter(engine: SearchDialect, asset: AssetRow[Expr], text: ResolvedSearchText): Expr[Boolean] =
    import engine.dialect.*

    def notIn(source: TextSource): Expr[Boolean] =
      if source.isAssetColumn || text.candidates.isDefined then !isIn(engine, asset, text, source)
      else engine.excludes(asset.id, source.name)

    def groupFilter(group: ResolvedSearchGroup, groupIndex: Int): Expr[Boolean] =
      val sources = textSources(group, groupIndex)
      group.alternatives match
        case Seq(only) if only.term.isExcluded => sources.head.map(notIn).reduce(_ && _)
        case alternatives =>
          alternatives
            .zip(sources)
            .map {
              (term, termSources) =>
                val inAnySource = termSources.map(isIn(engine, asset, text, _)).reduce(_ || _)
                if term.term.isExcluded then !inAnySource else inAnySource
            }
            .reduce(_ || _)

    val candidates = text.candidates.map(ids => Columns.isInSet(asset.id, ids, engine.dialect))
    val untested = text.groups.zipWithIndex.filterNot((group, _) => candidates.isDefined && group.isComplete)
    (candidates.toSeq ++ untested.map(groupFilter)).reduce(_ && _)

  /**
   * The Relevance of an asset to the Search text: the sum of what each group of the text scores, a group scoring as the best
   * source any of its alternatives matched the asset in. The alternatives' sources are the branches of one `CASE`, the highest
   * score first, so alternatives joined by `OR` count once, as the best of them. An excluded term scores nothing, and neither
   * does text made of exclusions alone: every asset it matches is as relevant as the next.
   */
  private def relevance(engine: SearchDialect, asset: AssetRow[Expr], text: ResolvedSearchText): Expr[Int] =
    import engine.dialect.*

    def score(group: ResolvedSearchGroup, groupIndex: Int): Option[Expr[Int]] =
      val matches = textSources(group, groupIndex).flatten.filterNot(_.term.term.isExcluded).sortBy(-_.source.relevance)
      Option.when(matches.nonEmpty) {
        Expr[Int] {
          implicit ctx =>
            val branches =
              matches.map(
                source => sql"WHEN ${isIn(engine, asset, text, source)} THEN ${SqlStr.raw(source.source.relevance.toString)}")
            sql"CASE ${SqlStr.join(branches, sql" ")} ELSE 0 END"
        }
      }

    text.groups.zipWithIndex.flatMap(score).reduceOption(_ + _).getOrElse(Expr(0))

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

  /** Assets in any of the given folders themselves; a subtree is the caller's to expand */
  private def folderFilter(engine: SearchDialect, asset: AssetRow[Expr], folderIds: Set[String]): Expr[Boolean] =
    Columns.isInSet(asset.folderId, folderIds, engine.dialect)

  /** Assets any of the given people appear in: the face's own person ID is all it reads */
  private def personFilter(engine: SearchDialect, asset: AssetRow[Expr], personIds: Set[String]): Expr[Boolean] =
    import engine.dialect.*

    FaceRow.select
      .filter(face => Columns.isInSet(face.personId, personIds, engine.dialect))
      .map(_.assetId)
      .contains(asset.id)

  /** Assets any of the given albums point at */
  private def albumFilter(engine: SearchDialect, asset: AssetRow[Expr], albumIds: Set[String]): Expr[Boolean] =
    import engine.dialect.*

    AlbumAssetRow.select
      .filter(link => Columns.isInSet(link.albumId, albumIds, engine.dialect))
      .map(_.assetId)
      .contains(asset.id)

  /** Assets in any of the given Locations */
  private def locationFilter(engine: SearchDialect, asset: AssetRow[Expr], locationIds: Set[String]): Expr[Boolean] =
    import engine.dialect.*

    LocationAssetRow.select
      .filter(link => Columns.isInSet(link.locationId, locationIds, engine.dialect))
      .map(_.assetId)
      .contains(asset.id)

  /**
   * Assets plotted inside the box: by their own point or, without one of their own, by the pin of a Location they are in - the
   * rule the map plots by.
   *
   * The two are one set of IDs, the union of the view's assets with a point in the box and of the members of the Locations pinned
   * in it that have no point of their own. Each branch is bounded by an index (the located assets' and the Locations'), where the
   * two rules as alternatives on the asset's own row would leave every matching asset to be tested.
   */
  private def bboxFilter(
      engine: SearchDialect,
      asset: AssetRow[Expr],
      query: SearchQuery,
      repositoryId: String,
      bbox: BoundingBox): Expr[Boolean] =
    import engine.dialect.*

    // The repository and the view's flags lead the index of the located assets
    val located = AssetRow.select
      .filter(own => (own.repositoryId `=` repositoryId) && (own.isPipelineProcessed `=` true))
      .filter(own => DynamicFilter(AssetRow, Columns.of(AssetRow, own, engine.dialect), query, engine.dialect))
      .filter(own => inBox(engine, bbox, own.latitude, own.longitude))
      .map(_.id)

    val pinned = membersOfLocationsIn(engine, bbox)
      .join(AssetRow)((memberId, member) => memberId `=` member.id)
      .filter((_, member) => member.latitude.isEmpty)
      .map((memberId, _) => memberId)

    Expr[Boolean] {
      implicit ctx =>
        sql"(${asset.id} IN (${Db.render(located, engine.dialect).withCompleteQuery(false)} UNION ALL ${Db.render(pinned, engine.dialect).withCompleteQuery(false)}))"
    }

  /** The IDs of the assets in the Locations pinned inside the box, an asset once per such Location */
  private def membersOfLocationsIn(engine: SearchDialect, bbox: BoundingBox): Select[Expr[String], String] =
    import engine.dialect.*

    LocationAssetRow.select
      .join(LocationRow)((link, location) => link.locationId `=` location.id)
      .filter((_, location) => inBox(engine, bbox, location.latitude, location.longitude))
      .map((link, _) => link.assetId)

  /** A point inside the box; a box across the antimeridian covers both sides of it. Plain arithmetic, no dialect hook. */
  private def inBox(
      engine: SearchDialect,
      bbox: BoundingBox,
      latitude: Expr[Option[Double]],
      longitude: Expr[Option[Double]]): Expr[Boolean] =
    import engine.dialect.*

    val longitudes =
      if bbox.crossesAntimeridian then
        Expr[Boolean](implicit ctx => sql"($longitude >= ${bbox.west} OR $longitude <= ${bbox.east})")
      else Expr[Boolean](implicit ctx => sql"$longitude BETWEEN ${bbox.west} AND ${bbox.east}")

    Expr[Boolean](implicit ctx => sql"($latitude BETWEEN ${bbox.south} AND ${bbox.north} AND $longitudes)")
