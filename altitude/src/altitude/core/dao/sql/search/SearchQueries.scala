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
import altitude.core.util.BoundingBox
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

  /**
   * Separates a parent's name from its Location's in a path key: the control character U+0001. It sorts below every printable
   * character, so the composite key orders exactly as the pair (parent name, Location name) would, and a Location's key cannot
   * collide with a top-level one.
   */
  private val PATH_SEPARATOR: String = 1.toChar.toString

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
      .filterIf(query.locationIds.nonEmpty)(asset => locationFilter(engine, asset, query.locationIds))
      .filterIf(query.bbox.isDefined)(asset => bboxFilter(engine, asset, query.bbox.get))

  /** The count of every match alone, for a result that renders no rows of its own (the map layout's total) */
  def count(engine: SearchDialect, query: SearchQuery, repositoryId: String): Aggregate[Expr[Int], Int] =
    import engine.dialect.*

    matching(engine, query, repositoryId).aggregate(_.size)

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
   * One statement for a page grouped by day: the ordered page slice joined back to its asset rows, the full-day count of each day
   * on the page and, on a first page, the count of every match. Every branch renders from the same [[matching]] relation, so
   * counts can never drift from the rows and every bind value travels with the fragment that needs it. The candidate slices are
   * narrow (ID, day, sort key) and fetch one row past the page to detect continuation; only the page's rows are joined to the
   * asset table. Every branch is materialized: the candidates because they are read twice, the counts so a day count runs once
   * per distinct day, not once per page row.
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
      val projected = extra.fold(base)(base.filter).map(row => (row.id, day(row), sortValue(engine, row, sort)))
      sliced(
        engine,
        projected,
        sql"${day(asset)} ${towards(grouping.direction)}, ${engine.secondarySort(asset, sort, grouping)} ${towards(sort.direction)}, ${asset.id} ASC",
        limit
      )

    val continuation =
      query.cursor.map(cursor => (row: AssetRow[Expr]) => afterDay(engine, cursor, cursorDay, dateField, grouping, sort, row))
    val dated = candidates(continuation, SqlStr.raw(s" LIMIT ${query.rpp + 1}"))

    // A day range never reaches NULL. Only a transition from dated rows to the trailing null group needs a second slice.
    // Put its guard in LIMIT: SQLite short-circuits a zero limit, but would scan the null block for a WHERE guard.
    val needsUndatedSlice = ownGroup && !engine.nullsFirst(grouping.direction) && cursorDay.isDefined
    val candidatesCte =
      if needsUndatedSlice then
        val undated = candidates(Some(isUndated), guardedLimit("dated", query.rpp))
        sql"""dated $narrowColumns AS MATERIALIZED ($dated), undated $narrowColumns AS MATERIALIZED ($undated),
          candidates $narrowColumns AS MATERIALIZED (
            SELECT id, day, sort_value FROM dated UNION ALL SELECT id, day, sort_value FROM undated)"""
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
      SqlStr.raw(s"$prefix.day ${grouping.direction}, $prefix.sort_value ${sort.direction}, $prefix.id ASC")

    sql"""
      WITH $candidatesCte, page $narrowColumns AS MATERIALIZED (
        SELECT id, day, sort_value FROM candidates
         ORDER BY ${SqlStr.raw(s"day ${grouping.direction}, sort_value ${sort.direction}, id ASC")}
         LIMIT ${SqlStr.raw(query.rpp.toString)}
      ), day_counts AS MATERIALIZED (
        SELECT p.day AS day,
               (SELECT count(*) FROM (${matchingIds(engine, base, Some(isCursorDay))}) AS m) AS n
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
   * One statement for a page grouped by Location: the shell of [[grouped]] over two relations instead of one. `located` is the
   * matching assets joined to their Locations - an asset once per Location it is in, so an asset in two Locations appears under
   * both - and `unlocated` is the matching assets in no Location, which always come last as the "No location" group.
   *
   * Locations are in path order: the key is the parent's lower-cased name and the Location's own joined by [[PATH_SEPARATOR]], or
   * the Location's name alone at the top level - the order the sidebar lists them in - then the Location's ID as a deterministic
   * tiebreaker, then the sort within the group, then the asset ID. A cursor whose anchor was in a Location slices `located` after
   * it and lets `unlocated` fill the page only once `located` has run out, through the same guarded `LIMIT CASE` as the day
   * statement's null slice; a cursor without a group key is already in the trailing group and slices `unlocated` alone. A group's
   * count is the matching assets in that Location (or in none); the overall total on a first page is the number of matching
   * assets, so the group counts may sum to more than it.
   */
  def groupedByLocation(engine: SearchDialect, query: SearchQuery, repositoryId: String): SqlStr =
    import engine.dialect.*
    given TypeMapper[SortValue] = engine.sortValueMapper

    val grouping = query.grouping.getOrElse(throw IllegalArgumentException("A grouped search needs a grouping"))
    val sort = query.searchSort.head
    val base = matching(engine, query, repositoryId)
    val isFirstPage = query.cursor.isEmpty
    // A cursor whose anchor was in a Location continues the located part; one without a key is in the trailing group already
    val inLocatedPart = query.cursor.forall(_.key.isDefined)

    val located = base
      .join(LocationAssetRow)((asset, link) => asset.id `=` link.assetId)
      .join(LocationRow)((row, location) => row._2.locationId `=` location.id)
      .leftJoin(LocationRow)((row, parent) => Expr[Boolean](implicit ctx => sql"${row._3.parentId} = ${parent.id}"))
    val ((asset, _, location), parent) = WithSqlExpr.get(located)
    val parentName: Expr[Option[String]] = parent.map(_.name)
    val pathKey =
      Expr[String](implicit ctx => sql"COALESCE(${parent.get.nameLc} || $PATH_SEPARATOR, '') || ${location.nameLc}")

    def inNoLocation(row: AssetRow[Expr]): Expr[Boolean] =
      LocationAssetRow.select.filter(link => link.assetId `=` row.id).map(_.assetId).isEmpty
    def nullText: Expr[Option[String]] = Expr[Option[String]](implicit ctx => sql"NULL")

    /** Rows strictly after the anchor: a later path key, or the anchor's own Location's rows after it by sort */
    def afterLocation(cursor: SearchCursor): Expr[Boolean] =
      val key = cursor.key.get
      val groupId = cursor.groupId.getOrElse(throw IllegalArgumentException("A Location cursor needs its group ID"))
      val after = afterBySort(engine, cursor, sort, asset, idOnly = false)
      Expr[Boolean] {
        implicit ctx =>
          sql"($pathKey > $key OR ($pathKey = $key AND (${location.id} > $groupId OR (${location.id} = $groupId AND $after))))"
      }

    val locatedSlice = sliced(
      engine,
      located
        .filterIf(query.cursor.isDefined && inLocatedPart)(_ => afterLocation(query.cursor.get))
        .map(_ => (asset.id, location.id, pathKey, location.name, parentName, sortValue(engine, asset, sort))),
      sql"$pathKey ASC, ${location.id} ASC, ${engine.secondarySort(asset, sort, grouping)} ${towards(sort.direction)}, ${asset.id} ASC",
      SqlStr.raw(s" LIMIT ${query.rpp + 1}")
    )

    val unlocatedBase = base.filter(inNoLocation)
    val unlocatedAsset = WithSqlExpr.get(unlocatedBase)
    val unlocatedSlice = sliced(
      engine,
      unlocatedBase
        .filterIf(!inLocatedPart)(row => afterBySort(engine, query.cursor.get, sort, row, idOnly = false))
        .map(row => (row.id, nullText, nullText, nullText, nullText, sortValue(engine, row, sort))),
      sql"${engine.secondarySort(unlocatedAsset, sort, grouping)} ${towards(sort.direction)}, ${unlocatedAsset.id} ASC",
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
      s"CASE WHEN $prefix.location_id IS NULL THEN 1 ELSE 0 END, $prefix.path_key ASC, $prefix.location_id ASC, " +
        s"$prefix.sort_value ${sort.direction}, $prefix.id ASC")

    sql"""
      WITH $candidatesCte, page $locationColumns AS MATERIALIZED (
        SELECT $locationColumnList FROM candidates AS c
         ORDER BY ${order("c")}
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
             p.parent_name AS parent_name, p.sort_value AS sort_value, g.n AS group_total,
             (SELECT count(*) FROM candidates) AS candidate_count$totalColumn
        FROM page AS p
             JOIN asset ON asset.id = p.id
             LEFT JOIN group_counts AS g ON g.location_id = p.location_id OR (g.location_id IS NULL AND p.location_id IS NULL)
             $totalJoin
       ORDER BY ${order("p")}
    """

  /**
   * Rows strictly after the cursor's anchor in day order, as lexicographic comparisons with independent directions: an earlier
   * day, or the same day and a later sort value, or the same sort value and a greater ID. A redundant inclusive bound on the day
   * lets the day index seek straight to the boundary.
   */
  private def afterDay(
      engine: SearchDialect,
      cursor: SearchCursor,
      cursorDay: Option[LocalDate],
      dateField: String,
      grouping: SearchGrouping,
      sort: SearchSort,
      row: AssetRow[Expr]): Expr[Boolean] =
    import engine.dialect.*

    val dayOp = SqlStr.raw(if grouping.direction == SortDirection.DESC then "<" else ">")
    val day = engine.day(row, dateField)
    // Sorting by capture time inside its null group reduces to ID order; every capture sort value there is NULL.
    val after = afterBySort(engine, cursor, sort, row, idOnly = cursorDay.isEmpty && sort.field == dateField)

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
   */
  private def afterBySort(
      engine: SearchDialect,
      cursor: SearchCursor,
      sort: SearchSort,
      row: AssetRow[Expr],
      idOnly: Boolean): Expr[Boolean] =
    import engine.dialect.*
    given TypeMapper[SortValue] = engine.sortValueMapper

    val sortOp = SqlStr.raw(if sort.direction == SortDirection.DESC then "<" else ">")
    val column = sortColumn(engine, row, sort)
    val id = row.id

    if idOnly then Expr[Boolean](implicit ctx => sql"$id > ${cursor.id}")
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

  /** A narrow candidate relation with a hand-written ORDER BY and LIMIT, rendered in the context that names its own columns */
  private def sliced[Q, R](engine: SearchDialect, projected: Select[Q, R], order: Context ?=> SqlStr, limit: SqlStr): SqlStr =
    val ordered = Select.withExprSuffix(
      Select.toSimpleFrom(projected),
      false,
      ctx =>
        given Context = ctx
        sql" ORDER BY " + order + limit
    )
    Db.render(ordered, engine.dialect).withCompleteQuery(false)

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

  /** The column a search sorts within a group by */
  private def sortColumn(engine: SearchDialect, row: AssetRow[Expr], sort: SearchSort): Expr[?] =
    Columns.required(AssetRow, row, sort.field, engine.dialect)

  /** The sort column read as the engine stores it, so a cursor can bind it back unchanged */
  private def sortValue(engine: SearchDialect, row: AssetRow[Expr], sort: SearchSort): Expr[SortValue] =
    given TypeMapper[SortValue] = engine.sortValueMapper
    Expr[SortValue](implicit ctx => sql"${sortColumn(engine, row, sort)}")

  private def towards(direction: SortDirection): SqlStr = SqlStr.raw(direction.toString)

  /** The day candidates' columns. Naming them on the CTE keeps the projection free to alias its own columns however. */
  private val narrowColumns: SqlStr = SqlStr.raw("(id, day, sort_value)")

  /** The Location candidates' columns: the asset, its Location and what heads the group, and the sort key */
  private val locationColumnList: SqlStr = SqlStr.raw("id, location_id, path_key, location_name, parent_name, sort_value")
  private val locationColumns: SqlStr = sql"($locationColumnList)"

  /** The asset columns the grouped statements select, in the order [[AssetRow]] declares them, so a row reads back positionally */
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

  /** Assets in any of the given Locations */
  private def locationFilter(engine: SearchDialect, asset: AssetRow[Expr], locationIds: Set[String]): Expr[Boolean] =
    import engine.dialect.*

    LocationAssetRow.select
      .filter(link => Columns.isIn(link.locationId, locationIds, engine.dialect))
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
