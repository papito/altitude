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
import scalasql.query.Aggregate
import scalasql.query.Select
import scalasql.query.SqlWindow

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

  /**
   * Separates a category's name from its Location's in a path key: the control character U+0001. It sorts below every printable
   * character, so the composite key orders exactly as the pair (category name, Location name) would, and a Location's key cannot
   * collide with a top-level one.
   */
  private val PATH_SEPARATOR: String = 1.toChar.toString

  /** A row of [[mapLocations]]: ID, name, category name, latitude, longitude, matching-asset count */
  type MapLocationRow = (String, String, Option[String], Option[Double], Option[Double], Int)
  type MapLocationExprs =
    (Expr[String], Expr[String], Expr[Option[String]], Expr[Option[Double]], Expr[Option[Double]], Expr[Int])

  /** Every asset a search matches, unordered and unpaged */
  def matching(engine: SearchDialect, query: SearchQuery, repositoryId: String): Select[AssetRow[Expr], AssetRow[Sc]] =
    import engine.dialect.*

    AssetRow.select
      .filter(asset => (asset.repositoryId `=` repositoryId) && (asset.isPipelineProcessed `=` true))
      .filter(asset => DynamicFilter(AssetRow, Columns.of(AssetRow, asset, engine.dialect), query, engine.dialect))
      .filterIf(query.folderIds.nonEmpty)(asset => folderFilter(engine, asset, query.folderIds))
      .filterIf(query.isText)(asset => textFilter(engine, asset, query.requireResolvedText, repositoryId))
      .filterIf(query.hasMetadataFilters)(asset => metadataFilter(engine, asset, query, repositoryId))
      .filterIf(query.personIds.nonEmpty)(asset => personFilter(engine, asset, query.personIds))
      .filterIf(query.albumIds.nonEmpty)(asset => albumFilter(engine, asset, query.albumIds))
      .filterIf(query.locationIds.nonEmpty)(asset => locationFilter(engine, asset, query.locationIds))
      .filterIf(query.bbox.isDefined)(asset => bboxFilter(engine, asset, query.bbox.get))

  /** The count of every match alone, for a result that renders no rows of its own (the map layout's total) */
  def count(engine: SearchDialect, query: SearchQuery, repositoryId: String): Aggregate[Expr[Int], Int] =
    import engine.dialect.*

    matching(engine, query, repositoryId).aggregate(_.size)

  /**
   * One page of a flat search, with the count of every match carried on each row.
   *
   * The window count is added before the ordering and the page, so it counts the whole match rather than the page. The order is
   * the one a grouped page has within a group ([[orderWithinGroup]]): the sort, then the ID. A missing capture time creates a
   * large tie group, and without the ID an offset page is not deterministic.
   *
   * The `ORDER BY` is rendered over the asset's own columns ([[orderedBy]]) rather than as typed `sortBy` terms, which name the
   * select list's aliases: PostgreSQL accepts an alias as a whole ordering term but not inside an expression, and the Relevance
   * is an expression over the asset's columns.
   */
  def flat(
      engine: SearchDialect,
      query: SearchQuery,
      repositoryId: String): Select[(AssetRow[Expr], SqlWindow[Int]), (AssetRow[Sc], Int)] =
    import engine.dialect.*

    val counted = matching(engine, query, repositoryId).mapAggregate((asset, aggregate) => (asset, aggregate.size.over))

    val asset = WithSqlExpr.get(counted)._1
    val sorted = if query.isSorted then orderedBy(counted, orderWithinGroup(engine, query, repositoryId, asset)) else counted

    if query.rpp > 0 then sorted.drop((query.page - 1) * query.rpp).take(query.rpp) else sorted

  /**
   * One statement for a page grouped by day: the ordered page slice joined back to its asset rows, the full-day count of each day
   * on the page and, on a first page, the count of every match. Every branch renders from the same [[matching]] relation, so
   * counts can never drift from the rows and every bind value travels with the fragment that needs it. The candidate slices are
   * narrow (ID, day, sort key) and fetch one row past the page to detect continuation; only the page's rows are joined to the
   * asset table. Every branch is materialized: the candidates because they are read twice, the counts so a day count runs once
   * per distinct day, not once per page row.
   *
   * A page reached by cursor skips the overall count: it is the dominant cost of the statement on a large library, and the footer
   * total was set by the first page. Ordering is day, then the sort, then the ID as a deterministic tiebreaker. Nulls fall where
   * the engine puts them natively; an explicit NULLS clause would forfeit index-ordered reads on both engines. The Relevance sort
   * is the exception ([[orderWithinGroup]]): no index orders a computed value, so its capture-time tiebreaker places them itself.
   *
   * The shell is hand-written on purpose: `MATERIALIZED`, the guarded `LIMIT CASE`, SQLite's planner hint and native null
   * placement are all tuned against the two engines' plans, and none of them can be expressed through a typed query.
   */
  def grouped(engine: SearchDialect, query: SearchQuery, repositoryId: String): SqlStr =
    import engine.dialect.*
    given TypeMapper[SortValue] = engine.sortValueMapper

    val grouping = query.grouping.getOrElse(throw IllegalArgumentException("A grouped search needs a grouping"))
    val dateField = grouping.by.dateField.getOrElse(throw IllegalArgumentException("A day grouping needs a date field"))
    val sort = query.searchSort.head
    val base = matching(engine, query, repositoryId)
    val asset = WithSqlExpr.get(base)

    // A grouping timestamp the schema lets be null gives its rows their own group, at the engine's native null position
    val ownGroup = engine.isNullableTimestamp(dateField)
    val isFirstPage = query.cursor.isEmpty
    val cursorDay = query.cursor.flatMap(_.key).map(LocalDate.parse)

    def day(row: AssetRow[Expr]): Expr[Option[LocalDate]] = engine.day(row, dateField)
    def isUndated(row: AssetRow[Expr]): Expr[Boolean] = Expr[Boolean](implicit ctx => sql"${day(row)} IS NULL")

    /** The narrow candidate relation, ordered and sliced in the context that names its own columns */
    def candidates(extra: Option[AssetRow[Expr] => Expr[Boolean]], limit: SqlStr): SqlStr =
      val projected = extra
        .fold(base)(base.filter)
        .map(row => (row.id, day(row), sortValue(engine, query, repositoryId, row), secondSortValue(engine, sort, row)))
      sliced(
        engine,
        projected,
        sql"${day(asset)} ${towards(grouping.direction)}, ${orderWithinGroup(engine, query, repositoryId, asset)}",
        limit
      )

    val continuation =
      query.cursor.map(_ => (row: AssetRow[Expr]) => afterDay(engine, query, repositoryId, cursorDay, dateField, row))
    val dated = candidates(continuation, SqlStr.raw(s" LIMIT ${query.rpp + 1}"))

    // A day range never reaches NULL. Only a transition from dated rows to the trailing null group needs a second slice.
    // Put its guard in LIMIT: SQLite short-circuits a zero limit, but would scan the null block for a WHERE guard.
    val needsUndatedSlice = ownGroup && !engine.nullsFirst(grouping.direction) && cursorDay.isDefined
    val candidatesCte =
      if needsUndatedSlice then
        val undated = candidates(Some(isUndated), guardedLimit("dated", query.rpp))
        sql"""dated $narrowColumns AS MATERIALIZED ($dated), undated $narrowColumns AS MATERIALIZED ($undated),
          candidates $narrowColumns AS MATERIALIZED (
            SELECT $narrowColumnList FROM dated UNION ALL SELECT $narrowColumnList FROM undated)"""
      else sql"candidates $narrowColumns AS MATERIALIZED ($dated)"

    // Separate equality and IS NULL probes keep both counts on the day index. The null driver is empty on dated-only pages.
    val isCursorDay = (row: AssetRow[Expr]) => Expr[Boolean](implicit ctx => sql"${day(row)} = p.day")
    val datedDriver = if ownGroup then SqlStr.raw(" WHERE day IS NOT NULL") else SqlStr.empty
    val nullCount =
      if ownGroup then sql""" UNION ALL SELECT p.day AS day,
          (SELECT count(*) FROM (${matchingIds(engine, base, Some(isUndated))}) AS m) AS n
          FROM (SELECT DISTINCT day FROM page WHERE day IS NULL) AS p"""
      else SqlStr.empty
    val nullJoin = if ownGroup then SqlStr.raw(" OR (d.day IS NULL AND p.day IS NULL)") else SqlStr.empty

    val (totalCte, totalColumn, totalJoin) = totalFragments(engine, base, isFirstPage)

    def order(prefix: String): SqlStr =
      SqlStr.raw(s"${prefix}day ${grouping.direction}, ${candidateOrderWithinGroup(prefix, sort)}")

    sql"""
      WITH $candidatesCte, page $narrowColumns AS MATERIALIZED (
        SELECT $narrowColumnList FROM candidates
         ORDER BY ${order("")}
         LIMIT ${SqlStr.raw(query.rpp.toString)}
      ), day_counts AS MATERIALIZED (
        SELECT p.day AS day,
               (SELECT count(*) FROM (${matchingIds(engine, base, Some(isCursorDay))}) AS m) AS n
          FROM (SELECT DISTINCT day FROM page$datedDriver) AS p$nullCount
      )$totalCte
      SELECT $assetColumns, p.day AS day, p.sort_value AS sort_value, p.second_sort_value AS second_sort_value, d.n AS day_total,
             (SELECT count(*) FROM candidates) AS candidate_count$totalColumn
        FROM page AS p
             JOIN asset ON asset.id = p.id
             LEFT JOIN day_counts AS d ON d.day = p.day$nullJoin
             $totalJoin
       ORDER BY ${order("p.")}
    """

  /**
   * One statement for a page grouped by Location: the shell of [[grouped]] over two relations instead of one. `located` is the
   * matching assets joined to their Locations - an asset once per Location it is in, so an asset in two Locations appears under
   * both - and `unlocated` is the matching assets in no Location, which always come last as the "No location" group.
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
    val isFirstPage = query.cursor.isEmpty
    // A cursor whose anchor was in a Location continues the located part; one without a key is in the trailing group already
    val inLocatedPart = query.cursor.forall(_.key.isDefined)

    val located = base
      .join(LocationAssetRow)((asset, link) => asset.id `=` link.assetId)
      .join(LocationRow)((row, location) => row._2.locationId `=` location.id)
      .leftJoin(LocationRow)((row, category) => Expr[Boolean](implicit ctx => sql"${row._3.categoryId} = ${category.id}"))
    val ((asset, _, location), category) = WithSqlExpr.get(located)
    val categoryName: Expr[Option[String]] = category.map(_.name)
    val pathKey =
      Expr[String](implicit ctx => sql"COALESCE(${category.get.nameLc} || $PATH_SEPARATOR, '') || ${location.nameLc}")

    def inNoLocation(row: AssetRow[Expr]): Expr[Boolean] =
      LocationAssetRow.select.filter(link => link.assetId `=` row.id).map(_.assetId).isEmpty
    def nullText: Expr[Option[String]] = Expr[Option[String]](implicit ctx => sql"NULL")

    /** Rows strictly after the anchor: a later path key, or the anchor's own Location's rows after it by sort */
    def afterLocation(cursor: SearchCursor): Expr[Boolean] =
      val key = cursor.key.get
      val groupId = cursor.groupId.getOrElse(throw IllegalArgumentException("A Location cursor needs its group ID"))
      val after = afterBySort(engine, query, repositoryId, asset, idOnly = false)
      Expr[Boolean] {
        implicit ctx =>
          sql"($pathKey > $key OR ($pathKey = $key AND (${location.id} > $groupId OR (${location.id} = $groupId AND $after))))"
      }

    val locatedSlice = sliced(
      engine,
      located
        .filterIf(query.cursor.isDefined && inLocatedPart)(_ => afterLocation(query.cursor.get))
        .map {
          _ =>
            (
              asset.id,
              location.id,
              pathKey,
              location.name,
              categoryName,
              sortValue(engine, query, repositoryId, asset),
              secondSortValue(engine, sort, asset))
        },
      sql"$pathKey ASC, ${location.id} ASC, ${orderWithinGroup(engine, query, repositoryId, asset)}",
      SqlStr.raw(s" LIMIT ${query.rpp + 1}")
    )

    val unlocatedBase = base.filter(inNoLocation)
    val unlocatedAsset = WithSqlExpr.get(unlocatedBase)
    val unlocatedSlice = sliced(
      engine,
      unlocatedBase
        .filterIf(!inLocatedPart)(row => afterBySort(engine, query, repositoryId, row, idOnly = false))
        .map {
          row =>
            (
              row.id,
              nullText,
              nullText,
              nullText,
              nullText,
              sortValue(engine, query, repositoryId, row),
              secondSortValue(engine, sort, row))
        },
      sql"${orderWithinGroup(engine, query, repositoryId, unlocatedAsset)}",
      if inLocatedPart then guardedLimit("located", query.rpp) else SqlStr.raw(s" LIMIT ${query.rpp + 1}")
    )

    val candidatesCte =
      if inLocatedPart then
        sql"""located $locationColumns AS MATERIALIZED ($locatedSlice), unlocated $locationColumns AS MATERIALIZED ($unlocatedSlice),
          candidates $locationColumns AS MATERIALIZED (
            SELECT $locationColumnList FROM located UNION ALL SELECT $locationColumnList FROM unlocated)"""
      else sql"candidates $locationColumns AS MATERIALIZED ($unlocatedSlice)"

    // One count per distinct Location on the page, and the no-location count only when the page reaches that group
    val inPageLocation = (row: AssetRow[Expr]) =>
      LocationAssetRow.select
        .filter(link => Expr[Boolean](implicit ctx => sql"${link.locationId} = p.location_id"))
        .map(_.assetId)
        .contains(row.id)

    val (totalCte, totalColumn, totalJoin) = totalFragments(engine, base, isFirstPage)

    // Located rows first whatever the engine's null placement, then path order
    def order(prefix: String): SqlStr = SqlStr.raw(
      s"CASE WHEN ${prefix}location_id IS NULL THEN 1 ELSE 0 END, ${prefix}path_key ASC, ${prefix}location_id ASC, " +
        candidateOrderWithinGroup(prefix, sort))

    sql"""
      WITH $candidatesCte, page $locationColumns AS MATERIALIZED (
        SELECT $locationColumnList FROM candidates AS c
         ORDER BY ${order("c.")}
         LIMIT ${SqlStr.raw(query.rpp.toString)}
      ), group_counts AS MATERIALIZED (
        SELECT p.location_id AS location_id,
               (SELECT count(*) FROM (${matchingIds(engine, base, Some(inPageLocation))}) AS m) AS n
          FROM (SELECT DISTINCT location_id FROM page WHERE location_id IS NOT NULL) AS p
        UNION ALL
        SELECT p.location_id AS location_id,
               (SELECT count(*) FROM (${matchingIds(engine, base, Some(inNoLocation))}) AS m) AS n
          FROM (SELECT DISTINCT location_id FROM page WHERE location_id IS NULL) AS p
      )$totalCte
      SELECT $assetColumns, p.location_id AS location_id, p.path_key AS path_key, p.location_name AS location_name,
             p.category_name AS category_name, p.sort_value AS sort_value, p.second_sort_value AS second_sort_value,
             g.n AS group_total,
             (SELECT count(*) FROM candidates) AS candidate_count$totalColumn
        FROM page AS p
             JOIN asset ON asset.id = p.id
             LEFT JOIN group_counts AS g ON g.location_id = p.location_id OR (g.location_id IS NULL AND p.location_id IS NULL)
             $totalJoin
       ORDER BY ${order("p.")}
    """

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
    sql"WITH points (asset_id, latitude, longitude, taken) AS (${plottedPoints(engine, query, repositoryId, bbox)})"

  /**
   * One statement for the map's cells in a viewport: the plotted points in the box, each assigned to a square cell of
   * `cellDegrees` a side by flooring its coordinates, then one window pass per cell for the count, the centroid and the rank of
   * each point by newest capture time (nulls last, whatever the engine's native placement) and then ID; the rank-one row of each
   * cell is the cell, and its asset represents it. `floor` is built into both engines.
   */
  def mapCells(engine: SearchDialect, query: SearchQuery, repositoryId: String, bbox: BoundingBox, cellDegrees: Double): SqlStr =
    import engine.dialect.*

    sql"""
      ${pointsCte(engine, query, repositoryId, Some(bbox))}, gridded AS (
        SELECT asset_id, latitude, longitude, taken, floor(longitude / $cellDegrees) AS cell_x, floor(latitude / $cellDegrees) AS cell_y
          FROM points
      ), cells AS (
        SELECT asset_id, cell_x, cell_y,
               COUNT(*) OVER w AS n, AVG(latitude) OVER w AS latitude, AVG(longitude) OVER w AS longitude,
               ROW_NUMBER() OVER (PARTITION BY cell_x, cell_y ORDER BY CASE WHEN taken IS NULL THEN 1 ELSE 0 END, taken DESC, asset_id ASC) AS rn
          FROM gridded
        WINDOW w AS (PARTITION BY cell_x, cell_y)
      )
      SELECT n, latitude, longitude, asset_id FROM cells WHERE rn = 1 ORDER BY cell_y, cell_x
    """

  /**
   * The Locations pinned inside the box that hold at least one matching asset, with that count and their category's name. The
   * count is a correlated count over the search's own [[matching]] relation, so it agrees with the grid scoped to the Location.
   */
  def mapLocations(
      engine: SearchDialect,
      query: SearchQuery,
      repositoryId: String,
      bbox: BoundingBox): Select[MapLocationExprs, MapLocationRow] =
    import engine.dialect.*

    val matchingIds = matching(engine, query, repositoryId).map(_.id)

    def matches(location: LocationRow[Expr]): Expr[Int] =
      LocationAssetRow.select.filter(link => (link.locationId `=` location.id) && matchingIds.contains(link.assetId)).size

    LocationRow.select
      .leftJoin(LocationRow)((location, category) => Expr[Boolean](implicit ctx => sql"${location.categoryId} = ${category.id}"))
      .filter {
        (location, _) =>
          (location.repositoryId `=` repositoryId) && (location.kind `=` LocationKind.Location.dbValue) &&
          inBox(engine, bbox, location.latitude, location.longitude) && (matches(location) > 0)
      }
      .map(
        (location, category) =>
          (location.id, location.name, category.map(_.name), location.latitude, location.longitude, matches(location)))

  /**
   * One aggregate over every plotted point of the search: the box around them and their count, for fitting the map to a result.
   * With nothing plotted the extremes are NULL and the count is zero.
   */
  def mapBounds(engine: SearchDialect, query: SearchQuery, repositoryId: String): SqlStr =
    sql"""
      ${pointsCte(engine, query, repositoryId, None)}
      SELECT min(latitude), max(latitude), min(longitude), max(longitude), count(*) FROM points
    """

  /**
   * Rows strictly after the cursor's anchor in day order, as lexicographic comparisons with independent directions: an earlier
   * day, or the same day and a later sort value, or the same sort value and a greater ID. A redundant inclusive bound on the day
   * lets the day index seek straight to the boundary.
   */
  private def afterDay(
      engine: SearchDialect,
      query: SearchQuery,
      repositoryId: String,
      cursorDay: Option[LocalDate],
      dateField: String,
      row: AssetRow[Expr]): Expr[Boolean] =
    import engine.dialect.*

    val grouping = query.grouping.get
    val dayOp = SqlStr.raw(if grouping.direction == SortDirection.DESC then "<" else ">")
    val day = engine.day(row, dateField)
    // Sorting by capture time inside its null group reduces to ID order; every capture sort value there is NULL.
    val idOnly = cursorDay.isEmpty && query.searchSort.head.field == dateField
    val after = afterBySort(engine, query, repositoryId, row, idOnly)

    cursorDay match
      case Some(anchor) =>
        // The redundant inclusive bound seeks to the cursor day; a trailing null block is supplied by the guarded slice.
        Expr[Boolean](implicit ctx => sql"$day $dayOp= $anchor AND ($day $dayOp $anchor OR $after)")
      case None if engine.nullsFirst(grouping.direction) =>
        // All dated days follow the leading null group, regardless of the secondary sort.
        Expr[Boolean](implicit ctx => sql"($day IS NOT NULL OR $after)")
      case None =>
        Expr[Boolean](implicit ctx => sql"$day IS NULL AND $after")

  /**
   * Rows of the anchor's own group strictly after it: a later sort value, or the same sort value and a greater ID. Where the sort
   * column can be null, nulls sit where the engine natively orders them and the comparison honors that placement. With `idOnly`
   * every sort value in the group is known to be null, and the comparison is the ID alone.
   *
   * Under the Relevance sort the position is a triple: between the Relevance and the ID comes the capture time
   * ([[afterByCaptureTime]]). The Relevance is not a column, so it is computed again wherever it is compared, and it is never
   * null.
   */
  private def afterBySort(
      engine: SearchDialect,
      query: SearchQuery,
      repositoryId: String,
      row: AssetRow[Expr],
      idOnly: Boolean): Expr[Boolean] =
    import engine.dialect.*
    given TypeMapper[SortValue] = engine.sortValueMapper

    val cursor = query.cursor.get
    val sort = query.searchSort.head
    val sortOp = SqlStr.raw(if sort.direction == SortDirection.DESC then "<" else ">")
    val column = sortColumn(engine, query, repositoryId, row)
    val afterById = Expr[Boolean](implicit ctx => sql"${row.id} > ${cursor.id}")

    if idOnly then afterById
    else
      cursor.sortValue match
        case SortValue.Null if engine.nullsFirst(sort.direction) =>
          Expr[Boolean](implicit ctx => sql"($column IS NOT NULL OR $afterById)")
        case SortValue.Null =>
          Expr[Boolean](implicit ctx => sql"($column IS NULL AND $afterById)")
        case value =>
          // What decides among the rows of the anchor's own sort value
          val afterTie =
            if sort.isRelevance then afterByCaptureTime(engine, cursor.requireSecondSortValue, row, afterById) else afterById
          Expr[Boolean] {
            implicit ctx =>
              val nullsAfter =
                if engine.isNullableTimestamp(sort.field) && !engine.nullsFirst(sort.direction) then sql" OR $column IS NULL"
                else SqlStr.empty
              sql"($column $sortOp $value OR ($column = $value AND $afterTie)$nullsAfter)"
          }

  /**
   * Rows of the anchor's own Relevance strictly after it, newest capture first: an older capture time, or the anchor's own and a
   * greater ID. Rows without a capture time are last on both engines, as [[orderWithinGroup]] places them: they all follow an
   * anchor that has one, and an anchor without one is followed only by those of them with a greater ID.
   */
  private def afterByCaptureTime(
      engine: SearchDialect,
      anchor: SortValue,
      row: AssetRow[Expr],
      afterById: Expr[Boolean]): Expr[Boolean] =
    given TypeMapper[SortValue] = engine.sortValueMapper

    val taken = row.originalCreatedAt

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

  /** The overall count's CTE, column and join on a first page; nothing on a continuation */
  private def totalFragments(
      engine: SearchDialect,
      base: Select[AssetRow[Expr], AssetRow[Sc]],
      isFirstPage: Boolean): (SqlStr, SqlStr, SqlStr) =
    if isFirstPage then
      (
        sql", total AS MATERIALIZED (SELECT count(*) AS n FROM (${matchingIds(engine, base, None)}) AS m)",
        SqlStr.raw(", t.n AS total"),
        SqlStr.raw("CROSS JOIN total AS t"))
    else (SqlStr.empty, SqlStr.empty, SqlStr.empty)

  /** What a search sorts by: the sort's column or, under the Relevance sort, the Relevance computed from the Search text */
  private def sortColumn(engine: SearchDialect, query: SearchQuery, repositoryId: String, row: AssetRow[Expr]): Expr[?] =
    val sort = query.searchSort.head
    if sort.isRelevance then relevance(engine, row, query.requireResolvedText, repositoryId)
    else Columns.required(AssetRow, row, sort.field, engine.dialect)

  /** What a search sorts by, read as the engine stores it, so a cursor can bind it back unchanged */
  private def sortValue(engine: SearchDialect, query: SearchQuery, repositoryId: String, row: AssetRow[Expr]): Expr[SortValue] =
    given TypeMapper[SortValue] = engine.sortValueMapper
    Expr[SortValue](implicit ctx => sql"${sortColumn(engine, query, repositoryId, row)}")

  /**
   * What a candidate carries for the term after the sort: under the Relevance sort the capture time as stored, for the page's
   * order and the cursor; NULL under a column sort, whose next term is the ID, so every candidate relation has the same columns.
   */
  private def secondSortValue(engine: SearchDialect, sort: SearchSort, row: AssetRow[Expr]): Expr[SortValue] =
    given TypeMapper[SortValue] = engine.sortValueMapper
    if sort.isRelevance then Expr[SortValue](implicit ctx => sql"${row.originalCreatedAt}")
    else Expr[SortValue](implicit ctx => sql"NULL")

  /**
   * The ORDER BY terms within a group, or of a whole flat page, over the asset's own columns: the sort, then the ID as a
   * deterministic tiebreaker. Under the Relevance sort the newest capture time comes between them, and the assets with none read
   * last on both engines: no index orders a computed value, so there is no index-ordered read for an explicit null placement to
   * forfeit. Within a group the engine may decorate a column sort's term to steer its planner.
   */
  private def orderWithinGroup(engine: SearchDialect, query: SearchQuery, repositoryId: String, row: AssetRow[Expr])(using
      Context): SqlStr =
    val sort = query.searchSort.head
    val direction = towards(sort.direction)
    val column = sortColumn(engine, query, repositoryId, row)
    if sort.isRelevance then sql"$column $direction, ${row.originalCreatedAt} DESC NULLS LAST, ${row.id} ASC"
    else sql"${query.grouping.fold(column)(engine.secondarySort(row, sort, _))} $direction, ${row.id} ASC"

  /** [[orderWithinGroup]] over the columns of a candidate relation, which carries what the slice computed */
  private def candidateOrderWithinGroup(prefix: String, sort: SearchSort): String =
    val byCaptureTime = if sort.isRelevance then s"${prefix}second_sort_value DESC NULLS LAST, " else ""
    s"${prefix}sort_value ${sort.direction}, $byCaptureTime${prefix}id ASC"

  private def towards(direction: SortDirection): SqlStr = SqlStr.raw(direction.toString)

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
   * Assets that satisfy the Search text: every group, by any of its alternatives. A term is satisfied by any of its sources and
   * an excluded term by none of them, so an asset without the term's words anywhere (or without a document) satisfies an
   * exclusion.
   */
  private def textFilter(
      engine: SearchDialect,
      asset: AssetRow[Expr],
      text: ResolvedSearchText,
      repositoryId: String): Expr[Boolean] =
    import engine.dialect.*

    def isSatisfied(term: ResolvedSearchTerm): Expr[Boolean] =
      val inAnySource = termMatches(engine, asset, term, repositoryId).map(_._2).reduce(_ || _)
      if term.term.isExcluded then !inAnySource else inAnySource

    text.groups.map(_.alternatives.map(isSatisfied).reduce(_ || _)).reduce(_ && _)

  /**
   * The Relevance of an asset to the Search text: the sum of what each group of the text scores, a group scoring as the best
   * source any of its alternatives matched the asset in. The alternatives' sources are the branches of one `CASE`, the highest
   * score first, so alternatives joined by `OR` count once, as the best of them. An excluded term scores nothing, and neither
   * does text made of exclusions alone: every asset it matches is as relevant as the next.
   */
  private def relevance(engine: SearchDialect, asset: AssetRow[Expr], text: ResolvedSearchText, repositoryId: String): Expr[Int] =
    import engine.dialect.*

    def score(group: ResolvedSearchGroup): Option[Expr[Int]] =
      val matches = group.alternatives
        .filterNot(_.term.isExcluded)
        .flatMap(termMatches(engine, asset, _, repositoryId))
        .sortBy((source, _) => -source.relevance)
      Option.when(matches.nonEmpty) {
        Expr[Int] {
          implicit ctx =>
            val branches = matches.map((source, isMatch) => sql"WHEN $isMatch THEN ${SqlStr.raw(source.relevance.toString)}")
            sql"CASE ${SqlStr.join(branches, sql" ")} ELSE 0 END"
        }
      }

    text.groups.flatMap(score).reduceOption(_ + _).getOrElse(Expr(0))

  /**
   * Where a term can match an asset, each source with its own predicate, in the order the sources are declared: the name sources
   * the term resolved to any IDs in, as the semi-joins and the folder filter a search already scopes by, and always the asset's
   * Search document, as the engine's own match. Exclusion is the caller's to apply.
   */
  private def termMatches(
      engine: SearchDialect,
      asset: AssetRow[Expr],
      term: ResolvedSearchTerm,
      repositoryId: String): Seq[(SearchSource, Expr[Boolean])] =
    import engine.dialect.*

    val inNames = term.ids.toSeq.sortBy(_._1.ordinal).map {
      (source, ids) =>
        source -> (source match
          case SearchSource.Person => personFilter(engine, asset, ids)
          case SearchSource.Location | SearchSource.Category => locationFilter(engine, asset, ids)
          case SearchSource.Folder => folderFilter(engine, asset, ids)
          case SearchSource.Album => albumFilter(engine, asset, ids)
          case SearchSource.Document => throw IllegalArgumentException("A Search document is matched by words, not by ID"))
    }

    val inDocument = SearchDocumentRow.select
      .filter(document => (document.repositoryId `=` repositoryId) && engine.textMatch(document, term.term))
      .map(_.assetId)
      .contains(asset.id)

    inNames :+ (SearchSource.Document -> inDocument)

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

  /** Assets any of the given people appear in */
  private def personFilter(engine: SearchDialect, asset: AssetRow[Expr], personIds: Set[String]): Expr[Boolean] =
    import engine.dialect.*

    FaceRow.select
      .join(PersonRow)((face, person) => face.personId `=` person.id)
      .filter((_, person) => Columns.isInSet(person.id, personIds, engine.dialect))
      .map((face, _) => face.assetId)
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
   */
  private def bboxFilter(engine: SearchDialect, asset: AssetRow[Expr], bbox: BoundingBox): Expr[Boolean] =
    import engine.dialect.*

    val pinnedInBox = LocationAssetRow.select
      .join(LocationRow)((link, location) => link.locationId `=` location.id)
      .filter((_, location) => inBox(engine, bbox, location.latitude, location.longitude))
      .map((link, _) => link.assetId)

    inBox(engine, bbox, asset.latitude, asset.longitude) || (asset.latitude.isEmpty && pinnedInBox.contains(asset.id))

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
