package altitude.core.dao.sql.search

import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import scalasql.Sc
import scalasql.core.Context
import scalasql.core.Expr
import scalasql.core.JoinNullable
import scalasql.core.SqlStr
import scalasql.core.SqlStr.SqlStringSyntax
import scalasql.core.TypeMapper
import scalasql.core.WithSqlExpr
import scalasql.query.Select
import scalasql.query.TableRef

import altitude.core.FieldConst
import altitude.core.dao.sql.Columns
import altitude.core.dao.sql.Db
import altitude.core.dao.sql.DynamicFilter
import altitude.core.dao.sql.tables.AlbumAssetRow
import altitude.core.dao.sql.tables.AssetRow
import altitude.core.dao.sql.tables.BulkyMetadata
import altitude.core.dao.sql.tables.FaceRow
import altitude.core.dao.sql.tables.LocationAssetRow
import altitude.core.dao.sql.tables.LocationRow
import altitude.core.dao.sql.tables.MetadataParameterRow
import altitude.core.dao.sql.tables.PersonRow
import altitude.core.dao.sql.tables.SearchDocumentRow
import altitude.core.models.LocationKind
import altitude.core.models.SceneModel
import altitude.core.util.BoundingBox
import altitude.core.util.DateRange
import altitude.core.util.DescribedPart
import altitude.core.util.DescribeRanking
import altitude.core.util.NameKey
import altitude.core.util.PeopleMatch
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
import altitude.core.util.SortKey
import altitude.core.util.SortValue
import altitude.core.util.TriageSubset

/**
 * A search as typed relations, rendered into hand-written statements.
 *
 * Every shape of search (the flat page, each slice and count of a grouped page, the map's aggregates) starts from the same
 * [[matching]] relation, so a count can never disagree with its rows. Every shell is built through [[statement]], which puts the
 * Search text's CTEs at its head, so a shell cannot leave them out. Joined-in filters are semi-joins on `asset.id`, so a search
 * reads `FROM asset` alone, with no `GROUP BY` on the outer query and no duplicate rows.
 *
 * These are pure functions of their arguments; the one thing read from the JVM is its time zone, in which the days of a Date
 * Imported range begin.
 */
object SearchQueries:

  /**
   * Every asset a search matches, unordered and unpaged. It may refer to the Search text's CTEs and to the `described` CTE, which
   * only [[statement]] supplies.
   */
  def matching(engine: SearchDialect, query: SearchQuery, repositoryId: String): Select[AssetRow[Expr], AssetRow[Sc]] =
    import engine.dialect.*

    AssetRow.select
      .filter(asset => (asset.repositoryId `=` repositoryId) && (asset.isPipelineProcessed `=` true))
      .filter(asset => DynamicFilter(AssetRow, Columns.of(AssetRow, asset, engine.dialect), query, engine.dialect))
      .filterIf(query.takenRange.isDefined)(asset => takenFilter(engine, asset, query.takenRange.get))
      .filterIf(query.importedRange.isDefined)(
        asset => importedFilter(engine, asset, query.importedRange.get, ZoneId.systemDefault))
      .filterIf(query.folderIds.nonEmpty)(asset => folderFilter(engine, asset, query.folderIds))
      .filterIf(query.isText)(asset => textFilter(engine, asset, query.requireResolvedText))
      .filterIf(query.hasMetadataFilters)(asset => metadataFilter(engine, asset, query, repositoryId))
      .filterIf(query.personIds.nonEmpty)(asset => peopleFilter(engine, asset, query.personIds, query.peopleMatch))
      .filterIf(query.albumIds.nonEmpty)(asset => albumFilter(engine, asset, query.albumIds))
      .filterIf(query.locationIds.nonEmpty)(asset => locationFilter(engine, asset, query.locationIds))
      .filterIf(query.subset.contains(TriageSubset.UnknownPeople))(asset => unknownPeopleFilter(engine, asset))
      .filterIf(query.subset.contains(TriageSubset.NoLocation))(asset => noLocationFilter(engine, asset.id))
      .filterIf(query.bbox.isDefined)(asset => bboxFilter(engine, asset, query, repositoryId, query.bbox.get))
      .filterIf(query.described.isDefined)(
        asset => Expr[Boolean](implicit ctx => sql"${asset.id} IN (SELECT id FROM ${SqlStr.raw(DescribedRow.tableName)})"))

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
   * How many assets each folder holds itself: the default view's matches grouped by folder, so a count is what clicking the
   * folder shows. It reads the capture-day index alone, which carries the folder.
   */
  def folderCounts(
      engine: SearchDialect,
      repositoryId: String): Select[(Expr[Option[String]], Expr[Int]), (Option[String], Int)] =
    import engine.dialect.*

    val defaultView = new SearchQuery(params = Map(FieldConst.Asset.IS_RECYCLED -> false))
    matching(engine, defaultView, repositoryId).groupBy(_.folderId)(_.size)

  /** The exact count of every match, uncapped */
  def count(engine: SearchDialect, query: SearchQuery, repositoryId: String): SqlStr =
    statement(
      engine,
      query,
      repositoryId,
      Nil,
      sql"SELECT count(*) FROM (${matchingIds(engine, matching(engine, query, repositoryId), None)}) AS m")

  /** The count of the matches up to one past the query's cap, which means more than the cap; no more rows are read */
  def cappedCount(engine: SearchDialect, query: SearchQuery, repositoryId: String): SqlStr =
    statement(
      engine,
      query,
      repositoryId,
      Nil,
      sql"SELECT count(*) FROM (${cappedIds(engine, matching(engine, query, repositoryId), query.totalCap)}) AS m")

  /**
   * How many matches carry a SigLIP 2 embedding, capped as [[cappedCount]] is: whether a Describe search's candidates are few
   * enough to rank one by one
   */
  def embeddedCount(engine: SearchDialect, query: SearchQuery, repositoryId: String, cap: Int): SqlStr =
    val matches = embedded(matching(engine, query, repositoryId))
    statement(engine, query, repositoryId, Nil, sql"SELECT count(*) FROM (${cappedIds(engine, matches, cap)}) AS m")

  /**
   * One page of a flat search: the page's assets are picked over narrow rows, then only those are read in full.
   *
   * The candidates are ordered by the sort, then the ID: a missing capture time makes a large tie group, and without the ID a
   * page is not deterministic. They fetch one row past the page to tell whether another follows, and start after the cursor's
   * anchor ([[afterBySort]]), so no page reads the rows of the pages before it.
   *
   * Sorted by capture time, the candidates are a day-grouped page's ([[dayCandidates]]): day then time is the order of the time
   * alone, and the engines read the capture-day index in order where they would not for the time alone. Under a scored sort they
   * read `scored` ([[scoredCte]]), so the cursor compares a score computed once. Rows without a sort value, sorted last, come
   * from a second slice ([[candidatesCte]]).
   */
  def flat(engine: SearchDialect, query: SearchQuery, repositoryId: String): SqlStr =
    import engine.dialect.*
    given TypeMapper[SortValue] = engine.sortValueMapper

    if query.rpp < 1 then throw IllegalArgumentException("A flat page needs a page size")

    val base = matching(engine, query, repositoryId)
    val asset = WithSqlExpr.get(base)
    val sort = query.searchSort.headOption
    val byCaptureDay = sort.filter(_.field == FieldConst.Asset.ORIGINAL_CREATED_AT)
    val isScored = sort.exists(_.isScored)

    /**
     * The candidates of a sort that is not by capture time: after the cursor's anchor, in the sort's order, then the ID's. Every
     * such sort has a value on every row, so one slice reads them.
     */
    def candidatesOf[Q, R](relation: Select[Q, R], position: Position): SqlStr =
      val after = query.cursor.map(_ => afterBySort(engine, query, position, idOnly = sort.isEmpty, seeks = true))
      candidatesCte(
        sliced(
          engine,
          after
            .fold(relation)(isAfter => relation.filter(_ => isAfter))
            .map(
              _ => (position.id, position.day, sortValueOf(position), sort.fold(nullSortValue)(secondSortValueOf(_, position)))),
          sort.fold(sql"${position.id} ASC")(_ => orderWithinGroup(engine, query, position)),
          SqlStr.raw(s" LIMIT ${query.rpp + 1}")
        ),
        None
      )

    val candidates = byCaptureDay match
      case Some(byDay) => dayCandidates(engine, query, base, byDay.field, byDay.direction)
      case None if isScored =>
        val scored = ScoredRow.select
        candidatesOf(scored, scoredPosition(WithSqlExpr.get(scored)))
      case None =>
        val sortValue = sort.fold[Expr[?]](nullSortValue)(_ => sortColumn(engine, query, asset))
        candidatesOf(base, Position(asset.id, nullDay, sortValue, asset.originalCreatedAt))

    val total = totalFragments(engine, base, query.isTotalCounted, query.totalCap)

    def order(prefix: String): SqlStr =
      SqlStr.raw(
        byCaptureDay.fold("")(byDay => s"${prefix}day ${byDay.direction}, ") +
          sort.fold(s"${prefix}id ASC")(candidateOrderWithinGroup(prefix, _)))

    val ctes = Option.when(isScored)(scoredCte(engine, query, base, None)).toSeq ++ Seq(
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
      repositoryId,
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
   * The candidates of a page ordered by day, as its CTEs: narrow rows after the cursor's anchor, in day order then the sort's,
   * one row past the page
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
    val cursorDay = cursorKey(query)(LocalDate.parse)

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
      if sort.isScored then slice(scored, scoredPosition(WithSqlExpr.get(scored)), extra, limit)
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

  /** The name of the slice of rows that have an ordering value, which a slice of those that have none may follow */
  private val LEADING_SLICE = "dated"

  /**
   * The candidates as CTEs: the leading slice, then optionally a slice of the rows whose ordering value is NULL. A range never
   * reaches NULL, so where NULL sorts last a continuation needs that second slice, which fills the page only once the leading one
   * runs out. Its guard is in LIMIT ([[guardedLimit]]): SQLite short-circuits a zero limit, but would scan the null block for a
   * WHERE guard.
   */
  private def candidatesCte(leading: SqlStr, trailingNulls: Option[SqlStr]): SqlStr =
    trailingNulls.fold(sql"candidates $narrowColumns AS MATERIALIZED ($leading)") {
      trailing =>
        sql"""${SqlStr.raw(LEADING_SLICE)} $narrowColumns AS MATERIALIZED ($leading), undated $narrowColumns AS MATERIALIZED ($trailing),
          candidates $narrowColumns AS MATERIALIZED (
            SELECT $narrowColumnList FROM ${SqlStr.raw(LEADING_SLICE)} UNION ALL SELECT $narrowColumnList FROM undated)"""
    }

  /**
   * One statement for a page grouped by day: the page slice joined back to its asset rows, the full-day count of each day on the
   * page and, on a first page, the count of every match. Every CTE is materialized: the candidates because they are read twice,
   * the counts so a day count runs once per distinct day, not per row. Under a scored sort `scored` ([[scoredCte]]) comes first;
   * no index orders a computed value, so reading every match first loses nothing.
   *
   * Ordering is day, then the sort, then the ID. Nulls fall where the engine puts them natively, since an explicit NULLS clause
   * would forfeit index-ordered reads on both engines; a scored sort places them itself ([[orderWithinGroup]]).
   *
   * The shell is hand-written because `MATERIALIZED`, the guarded `LIMIT CASE`, SQLite's planner hint and native null placement
   * are tuned against both engines' plans and cannot be expressed through a typed query.
   */
  def grouped(engine: SearchDialect, query: SearchQuery, repositoryId: String): SqlStr =
    given TypeMapper[SortValue] = engine.sortValueMapper

    val grouping = query.grouping.getOrElse(throw IllegalArgumentException("A grouped search needs a grouping"))
    val dateField = grouping.by.dateField.getOrElse(throw IllegalArgumentException("A day grouping needs a date field"))
    val sort = query.searchSort.head
    val base = matching(engine, query, repositoryId)

    // A grouping timestamp the schema lets be null gives its rows their own group, at the engine's native null position
    val ownGroup = engine.isNullableTimestamp(dateField)

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

    val total = totalFragments(engine, base, query.isTotalCounted, query.totalCap)

    def order(prefix: String): SqlStr =
      SqlStr.raw(s"${prefix}day ${grouping.direction}, ${candidateOrderWithinGroup(prefix, sort)}")

    val ctes = Option.when(sort.isScored)(scoredCte(engine, query, base, Some(day))).toSeq ++ Seq(
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
      repositoryId,
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
   * One statement for a page grouped by Location: [[grouped]]'s shell over two relations. `located` is the matches joined to
   * their Locations (an asset in two Locations appears under both); `unlocated` is the matches in no Location, always the last
   * group.
   *
   * Locations are in the sidebar's path order (the path key, `SearchDialect.pathKey`), then by ID, then the sort, then the asset
   * ID. A cursor in a Location slices `located` after it and lets `unlocated` fill the page only once `located` runs out, through
   * the same guarded `LIMIT CASE`. A group's count is its matching assets, so the group counts may sum to more than the total.
   */
  def groupedByLocation(engine: SearchDialect, query: SearchQuery, repositoryId: String): SqlStr =
    import engine.dialect.*
    given TypeMapper[SortValue] = engine.sortValueMapper

    val sort = query.searchSort.head
    val base = matching(engine, query, repositoryId)
    val scored = ScoredRow.select
    // A cursor whose anchor was in a Location continues the located part; one without a key is in the trailing group already
    val inLocatedPart = query.cursor.forall(_.key.isDefined)

    def assetPosition(row: AssetRow[Expr]): Position = assetPositionIn(engine, query, row)
    def nullText: Expr[Option[String]] = Expr[Option[String]](implicit ctx => sql"NULL")

    /** The located slice: each match under each of its Locations, in path order, after the cursor's anchor when it has one */
    def locatedSlice[Q, R](
        relation: Select[Q, R],
        position: Position,
        location: LocationRow[Expr],
        category: JoinNullable[LocationRow[Expr]]): SqlStr =
      val categoryName: Expr[Option[String]] = category.map(_.name)
      val pathKey = engine.pathKey(category.get.nameSort, location.nameSort)

      /** Rows strictly after the anchor: a later path key, or the anchor's own Location's rows after it by sort */
      def afterLocation(cursor: SearchCursor): Expr[Boolean] =
        val key = cursor.key.get
        val groupId = cursor.groupId.getOrElse(throw SearchCursor.malformed)
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
      if sort.isScored then
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
      if sort.isScored then
        val relation = scored.filter(row => noLocationFilter(engine, row.id))
        unlocatedSlice(relation, scoredPosition(WithSqlExpr.get(relation)))
      else
        val relation = base.filter(row => noLocationFilter(engine, row.id))
        unlocatedSlice(relation, assetPosition(WithSqlExpr.get(relation)))

    val candidatesCte =
      if inLocatedPart then
        sql"""located $locationColumns AS MATERIALIZED ($located), unlocated $locationColumns AS MATERIALIZED ($unlocated),
          candidates $locationColumns AS MATERIALIZED (
            SELECT $locationColumnList FROM located UNION ALL SELECT $locationColumnList FROM unlocated)"""
      else sql"candidates $locationColumns AS MATERIALIZED ($unlocated)"

    // One count per distinct Location on the page, over that Location's own memberships, so it costs the page's Locations and
    // not the library. The no-location count runs only when the page reaches that group.
    val pageLocationMembers = Db
      .render(
        base
          .join(LocationAssetRow)((asset, link) => asset.id `=` link.assetId)
          .filter((_, link) => Expr[Boolean](implicit ctx => sql"${link.locationId} = p.location_id"))
          .map((asset, _) => asset.id),
        engine.dialect
      )
      .withCompleteQuery(false)

    val total = totalFragments(engine, base, query.isTotalCounted, query.totalCap)

    // Located rows first whatever the engine's null placement, then path order
    def order(prefix: String): SqlStr = SqlStr.raw(
      s"CASE WHEN ${prefix}location_id IS NULL THEN 1 ELSE 0 END, ${prefix}path_key ASC, ${prefix}location_id ASC, " +
        candidateOrderWithinGroup(prefix, sort))

    val ctes = Option.when(sort.isScored)(scoredCte(engine, query, base, None)).toSeq ++ Seq(
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
               (SELECT count(*) FROM (${matchingIds(engine, base, Some(row => noLocationFilter(engine, row.id)))}) AS m) AS n
          FROM (SELECT DISTINCT location_id FROM page WHERE location_id IS NULL) AS p
      )"""
    ) ++ total.cte

    statement(
      engine,
      query,
      repositoryId,
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
   * One statement for a page grouped by Sequence: [[groupedByLocation]]'s shell, over `sequenced` (the matches in a Sequence) and
   * `unsequenced` (the rest, always the last group).
   *
   * Groups are in the queue's order: the first capture of the group's matching frames, oldest first, then the Sequence's ID, then
   * the sort, then the asset ID. That first capture is a window over the matching frames, which `sequence_frames`
   * ([[SequenceFrameRow]]) materializes for the cursor to compare; it costs the frames in a Sequence (through `asset_sequence`),
   * not the library. A Sequence with no capture time follows those with one on both engines. Continuation and the per-group
   * counts work as [[groupedByLocation]]'s.
   */
  def groupedBySequence(engine: SearchDialect, query: SearchQuery, repositoryId: String): SqlStr =
    import engine.dialect.*
    given TypeMapper[SortValue] = engine.sortValueMapper

    val sort = query.searchSort.head
    val base = matching(engine, query, repositoryId)
    val scored = ScoredRow.select
    // A cursor whose anchor was in a Sequence continues the sequenced part; a Sequence with no capture time has an ID and no key
    val inSequencedPart = query.cursor.forall(_.groupId.isDefined)

    val frames = SequenceFrameRow.select
    val frame = WithSqlExpr.get(frames)
    val framePosition = Position(frame.id, nullDay, frame.sortValue, frame.secondSortValue)

    /** The matches that carry a Sequence, as [[SequenceFrameRow]] has them */
    def framesOf[Q, R](relation: Select[Q, R], asset: AssetRow[Expr], position: Position): SqlStr =
      val firstCapture =
        Expr[Option[LocalDateTime]](implicit ctx => sql"MIN(${asset.originalCreatedAt}) OVER (PARTITION BY ${asset.sequenceId})")
      val taken = Expr[SortValue](implicit ctx => sql"${position.taken}")
      val projected = relation.map(_ => (position.id, asset.sequenceId, firstCapture, sortValueOf(position), taken))
      Db.render(projected, engine.dialect).withCompleteQuery(false)

    /**
     * Rows strictly after the anchor: its own Sequence's rows after it by sort, or a later group. A Sequence is told by its ID
     * before its first capture, which moves when the frame that set it leaves: the anchor's Sequence's frames already shown must
     * not count as a later group, and those still to come must not be lost.
     */
    def afterSequence(cursor: SearchCursor): Expr[Boolean] =
      val sequenceId = cursor.groupId.get
      val after = afterBySort(engine, query, framePosition, idOnly = false)
      val first = frame.firstCapturedAt
      val anchorCapture = cursorKey(query)(LocalDateTime.parse)
      Expr[Boolean] {
        implicit ctx =>
          val laterGroup = anchorCapture.fold(sql"($first IS NULL AND ${frame.sequenceId} > $sequenceId)") {
            capture => sql"($first > $capture OR $first IS NULL OR ($first = $capture AND ${frame.sequenceId} > $sequenceId))"
          }
          sql"((${frame.sequenceId} = $sequenceId AND $after) OR (${frame.sequenceId} <> $sequenceId AND $laterGroup))"
      }

    /** The trailing slice: the matches with no Sequence, after the cursor's anchor when the anchor was among them */
    def unsequencedSlice[Q, R](relation: Select[Q, R], position: Position): SqlStr =
      // Typed, for the union with the sequenced slice: PostgreSQL would take a bare NULL in a CTE as text
      val noSequence = Expr[Option[String]](implicit ctx => sql"CAST(NULL AS CHAR(36))")
      val noCapture = Expr[Option[LocalDateTime]](implicit ctx => sql"CAST(NULL AS TIMESTAMP)")
      sliced(
        engine,
        relation
          .filterIf(!inSequencedPart)(_ => afterBySort(engine, query, position, idOnly = false))
          .map(_ => (position.id, noSequence, noCapture, sortValueOf(position), secondSortValueOf(sort, position))),
        sql"${orderWithinGroup(engine, query, position)}",
        if inSequencedPart then guardedLimit("sequenced", query.rpp) else SqlStr.raw(s" LIMIT ${query.rpp + 1}")
      )

    // The scored matches, with the asset row that says which part of the page each belongs to
    def scoredWhere(isIn: AssetRow[Expr] => Expr[Boolean]) =
      scored.join(AssetRow)((row, asset) => row.id `=` asset.id).filter((_, asset) => isIn(asset))

    val unsequenced =
      if sort.isScored then
        val relation = scoredWhere(_.sequenceId.isEmpty)
        unsequencedSlice(relation, scoredPosition(WithSqlExpr.get(relation)._1))
      else
        val relation = base.filter(_.sequenceId.isEmpty)
        unsequencedSlice(relation, assetPositionIn(engine, query, WithSqlExpr.get(relation)))

    val candidatesCte =
      if inSequencedPart then
        val sequenceFrames =
          if sort.isScored then
            val relation = scoredWhere(_.sequenceId.isDefined)
            val (row, asset) = WithSqlExpr.get(relation)
            framesOf(relation, asset, scoredPosition(row))
          else
            val relation = base.filter(_.sequenceId.isDefined)
            val asset = WithSqlExpr.get(relation)
            framesOf(relation, asset, assetPositionIn(engine, query, asset))
        val sequenced = sliced(
          engine,
          frames
            .filterIf(query.cursor.isDefined)(_ => afterSequence(query.cursor.get))
            .map(
              _ =>
                (
                  frame.id,
                  frame.sequenceId,
                  frame.firstCapturedAt,
                  sortValueOf(framePosition),
                  secondSortValueOf(sort, framePosition))),
          sql"${frame.firstCapturedAt} ASC NULLS LAST, ${frame.sequenceId} ASC, ${orderWithinGroup(engine, query, framePosition)}",
          SqlStr.raw(s" LIMIT ${query.rpp + 1}")
        )
        sql"""sequence_frames $sequenceColumns AS MATERIALIZED ($sequenceFrames),
          sequenced $sequenceColumns AS MATERIALIZED ($sequenced), unsequenced $sequenceColumns AS MATERIALIZED ($unsequenced),
          candidates $sequenceColumns AS MATERIALIZED (
            SELECT $sequenceColumnList FROM sequenced UNION ALL SELECT $sequenceColumnList FROM unsequenced)"""
      else sql"candidates $sequenceColumns AS MATERIALIZED ($unsequenced)"

    val inPageSequence = (row: AssetRow[Expr]) => Expr[Boolean](implicit ctx => sql"${row.sequenceId} = p.sequence_id")

    val total = totalFragments(engine, base, query.isTotalCounted, query.totalCap)

    // The Sequences first whatever the engine's null placement, by the first capture with those that have none last
    def order(prefix: String): SqlStr = SqlStr.raw(
      s"CASE WHEN ${prefix}sequence_id IS NULL THEN 1 ELSE 0 END, ${prefix}first_captured_at ASC NULLS LAST, " +
        s"${prefix}sequence_id ASC, " + candidateOrderWithinGroup(prefix, sort))

    val ctes = Option.when(sort.isScored)(scoredCte(engine, query, base, None)).toSeq ++ Seq(
      candidatesCte,
      sql"""page $sequenceColumns AS MATERIALIZED (
        SELECT $sequenceColumnList FROM candidates AS c
         ORDER BY ${order("c.")}
         LIMIT ${SqlStr.raw(query.rpp.toString)}
      )""",
      sql"""group_counts AS MATERIALIZED (
        SELECT p.sequence_id AS sequence_id,
               (SELECT count(*) FROM (${matchingIds(engine, base, Some(inPageSequence))}) AS m) AS n
          FROM (SELECT DISTINCT sequence_id FROM page WHERE sequence_id IS NOT NULL) AS p
        UNION ALL
        SELECT p.sequence_id AS sequence_id,
               (SELECT count(*) FROM (${matchingIds(engine, base, Some(_.sequenceId.isEmpty))}) AS m) AS n
          FROM (SELECT DISTINCT sequence_id FROM page WHERE sequence_id IS NULL) AS p
      )"""
    ) ++ total.cte

    statement(
      engine,
      query,
      repositoryId,
      ctes,
      sql"""
      SELECT $assetColumns, p.sequence_id AS sequence_id, p.first_captured_at AS first_captured_at,
             p.sort_value AS sort_value, p.second_sort_value AS second_sort_value,
             g.n AS group_total,
             (SELECT count(*) FROM candidates) AS candidate_count${total.column}
        FROM page AS p
             JOIN asset ON asset.id = p.id
             LEFT JOIN group_counts AS g ON g.sequence_id = p.sequence_id OR (g.sequence_id IS NULL AND p.sequence_id IS NULL)
             ${total.join}
       ORDER BY ${order("p.")}
      """
    )

  /**
   * One page of a Describe search: the matches ranked by how much they look like a description, most alike first.
   *
   * `ranked (id, distance)` ([[rankedCtes]]) is the engine's ranking of the [[matching]] relation: the matches whose SigLIP 2
   * embedding is within the cutoff, nearest first up to the limit. Since it ranks the search's own matches, a Describe search
   * cannot disagree with the search of its filters about which assets they leave. The page is the rows after the cursor by
   * (distance, ID), one more than the page holds; every page ranks again, so a cursor continues among the rows as they are then.
   *
   * Every row also carries where the weaker matches begin, the same on each: `weaker_under`, the cosine line read off the whole
   * ranking, and `weaker_count`, how many ranked rows are under it ([[IS_WEAKER]]).
   */
  def described(engine: SearchDialect, query: SearchQuery, repositoryId: String, ranking: DescribeRanking): SqlStr =
    import engine.dialect.*

    if query.rpp < 1 then throw IllegalArgumentException("A page of a Describe search needs a page size")
    if query.described.isDefined then
      throw IllegalArgumentException("A search among a part of a description's matches is no ranking of them")

    val after = query.cursor.fold(SqlStr.empty) {
      cursor =>
        cursor.sortValue match
          case SortValue.Real(distance) => sql"WHERE (distance, id) > ($distance, ${cursor.id})"
          case _ => throw SearchCursor.malformed
    }
    val total = if query.isTotalCounted then SqlStr.raw(", (SELECT count(*) FROM ranked) AS total") else SqlStr.empty

    statement(
      engine,
      query,
      repositoryId,
      rankedCtes(engine, query, repositoryId, ranking),
      sql"""
      SELECT $assetColumnsWithoutBulkyMetadata, page.distance AS distance,
             (SELECT cosine FROM weaker_line) AS weaker_under,
             (SELECT count(*) FROM ranked WHERE $IS_WEAKER) AS weaker_count$total
        FROM (SELECT id, distance
                FROM ranked
               $after
               ORDER BY distance, id
               LIMIT ${SqlStr.raw((query.rpp + 1).toString)}) AS page
             JOIN asset ON asset.id = page.id
       ORDER BY page.distance, page.id
      """
    )

  /**
   * Every point the map plots for a search: a matching asset with a point of its own is plotted there only; one without is
   * plotted at the pin of each Location it is in, so an asset in two Locations is two points. Both branches carry every filter of
   * the search, so the points can never disagree with the grid.
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

  private def pointsCte(engine: SearchDialect, query: SearchQuery, repositoryId: String, bbox: Option[BoundingBox]): SqlStr =
    sql"points (asset_id, latitude, longitude, taken) AS (${plottedPoints(engine, query, repositoryId, bbox)})"

  /**
   * The map's cells in a viewport: each plotted point in the box floored into a square cell `cellDegrees` a side, then one window
   * pass per cell for the count, the centroid and the representative asset (newest capture time, nulls last on either engine,
   * then ID)
   */
  def mapCells(engine: SearchDialect, query: SearchQuery, repositoryId: String, bbox: BoundingBox, cellDegrees: Double): SqlStr =
    import engine.dialect.*

    statement(
      engine,
      query,
      repositoryId,
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
      sql"""SELECT cells.n, cells.latitude, cells.longitude, cells.asset_id, asset.edit_rev
              FROM cells JOIN asset ON asset.id = cells.asset_id AND asset.repository_id = $repositoryId
             WHERE cells.rn = 1
          ORDER BY cells.cell_y, cells.cell_x"""
    )

  /**
   * The Locations pinned inside the box that hold at least one matching asset, with that count. The matches among the members of
   * the box's Locations are read once into `matched`, so it is as large as those memberships and not the library. The count
   * agrees with the grid scoped to the Location.
   */
  def mapLocations(engine: SearchDialect, query: SearchQuery, repositoryId: String, bbox: BoundingBox): SqlStr =
    import engine.dialect.*

    def column(name: String): Expr[Option[Double]] = Expr[Option[Double]](_ => SqlStr.raw(name))
    val inTheBox = inBox(engine, bbox, column("location.latitude"), column("location.longitude"))

    statement(
      engine,
      query,
      repositoryId,
      Seq(
        sql"matched (id) AS MATERIALIZED (${matchingIds(engine, matching(engine, query, repositoryId), Some(asset => membersOfLocationsIn(engine, bbox, repositoryId).contains(asset.id)))})"),
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

  /** The box around every plotted point of the search and their count; with nothing plotted the extremes are NULL */
  def mapBounds(engine: SearchDialect, query: SearchQuery, repositoryId: String): SqlStr =
    statement(
      engine,
      query,
      repositoryId,
      Seq(pointsCte(engine, query, repositoryId, None)),
      sql"SELECT min(latitude), max(latitude), min(longitude), max(longitude), count(*) FROM points"
    )

  /**
   * The hits of the Search text's positive groups (none of whose alternatives is excluded) as (group index, asset ID), an asset
   * once per source it is a hit in. Each group stops one row past `limit`, so a group with more hits costs no more than one with
   * as many.
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
   * The one way a statement over a search is built: the Search text's CTEs, the description's, the shell's own, then the body.
   * [[matching]] names the text's CTEs and `described`, so a statement built any other way would not run. So no statement binds a
   * cursor's values unchecked ([[requireCursorFits]]).
   */
  private def statement(
      engine: SearchDialect,
      query: SearchQuery,
      repositoryId: String,
      ctes: Seq[SqlStr],
      body: SqlStr): SqlStr =
    requireCursorFits(engine, query)
    textCtes(engine, query) ++ describedCtes(engine, query, repositoryId) ++ ctes match
      case Seq() => body
      case all => sql"WITH " + SqlStr.join(all, SqlStr.raw(", ")) + sql" " + body

  /**
   * Refuses a cursor holding values its search could not have written, before a statement binds them. The fingerprint says which
   * search a cursor is of, but the values travel in the token, and one edited by hand would otherwise fail its statement or be
   * compared across types: a malformed cursor, a 400. The sort value must be of the kind the engine reads the sort's values as,
   * and under a scored sort the capture time beside it of the capture time's. The group key is read where a statement orders by
   * it ([[cursorKey]]); a Describe ranking's cursor, which no sort names, in [[described]].
   */
  private def requireCursorFits(engine: SearchDialect, query: SearchQuery): Unit =
    for cursor <- query.cursor; sort <- query.searchSort.headOption do
      if !engine.readsAs(sort.field, cursor.sortValue) then throw SearchCursor.malformed
      if sort.isScored && !engine.readsAs(FieldConst.Asset.ORIGINAL_CREATED_AT, cursor.requireSecondSortValue) then
        throw SearchCursor.malformed

  /**
   * The cursor's group key read as its statement orders the groups (`SearchGroupKey.cursorKey` writes it): a day, or a Sequence's
   * first capture. A key that does not read is a malformed cursor.
   */
  private def cursorKey[K](query: SearchQuery)(read: String => K): Option[K] =
    query.cursor
      .flatMap(_.key)
      .map(
        key =>
          try read(key)
          catch case _: DateTimeException => throw SearchCursor.malformed)

  /**
   * The CTEs of a description's ranking: the engine's sources, `ranked (id, distance)` (the matches of `filters` as the engine
   * ranks them, materialized once), and `weaker_line (cosine)`, the ranking's `weakerRatio` of the nearest row's cosine
   */
  private def rankedCtes(
      engine: SearchDialect,
      filters: SearchQuery,
      repositoryId: String,
      ranking: DescribeRanking): Seq[SqlStr] =
    import engine.dialect.*

    val rankedMatches = engine.ranked(matching(engine, filters, repositoryId), ranking)
    rankedMatches.sources ++ Seq(
      sql"ranked (id, distance) AS MATERIALIZED (${rankedMatches.rows})",
      sql"weaker_line (cosine) AS (SELECT ${ranking.weakerRatio} * (1 - min(distance)) FROM ranked)"
    )

  /**
   * Whether a row of `ranked` is a weaker match: its cosine (1 - distance) is under the line. A line that is not positive is none
   * (a ratio of 0, or a nearest row with no positive cosine), so the nearest row is never weaker. Every comparison with the line
   * goes through this, so a row is on the same side of it everywhere.
   */
  private val IS_WEAKER: SqlStr =
    SqlStr.raw("((SELECT cosine FROM weaker_line) > 0 AND 1 - distance < (SELECT cosine FROM weaker_line))")

  /**
   * For a search among a part of a description's matches (`SearchQuery.described`): the ranking of its filters, then
   * `described (id, similarity)`, the ranked rows of that part (above the weaker line or under it) with their cosine
   */
  private def describedCtes(engine: SearchDialect, query: SearchQuery, repositoryId: String): Seq[SqlStr] =
    query.described.fold(Seq.empty[SqlStr]) {
      described =>
        val part = described.part match
          case DescribedPart.Nearer => sql"NOT $IS_WEAKER"
          case DescribedPart.Weaker => IS_WEAKER
        rankedCtes(engine, query.undescribed, repositoryId, described.ranking) :+
          sql"""${SqlStr.raw(DescribedRow.tableName)} (id, similarity) AS MATERIALIZED (
            SELECT id, 1 - distance FROM ranked WHERE $part)"""
    }

  /**
   * Where a row stands in a page's order, as the relation it is sliced from has it: the asset under a column sort, `scored` under
   * a scored one. `taken` is the capture time, which orders it next under a scored sort.
   */
  private case class Position(id: Expr[String], day: Expr[Option[LocalDate]], sortValue: Expr[?], taken: Expr[?])

  private def scoredPosition(row: ScoredRow[Expr]): Position = Position(row.id, row.day, row.sortValue, row.secondSortValue)

  /** The position of an asset in a statement with no days and no `scored` */
  private def assetPositionIn(engine: SearchDialect, query: SearchQuery, row: AssetRow[Expr]): Position =
    Position(row.id, nullDay, sortColumn(engine, query, row), row.originalCreatedAt)

  /**
   * The matches with what a scored sort orders them by, computed once for the slices, the cursor and the order to read: the
   * Relevance of the Search text, or the similarity from `described`. The capture time (the next order term) and a day grouping's
   * day ride along.
   */
  private def scoredCte(
      engine: SearchDialect,
      query: SearchQuery,
      base: Select[AssetRow[Expr], AssetRow[Sc]],
      day: Option[AssetRow[Expr] => Expr[Option[LocalDate]]]): SqlStr =
    import engine.dialect.*

    val scores =
      if query.searchSort.head.isSimilarity then
        if query.described.isEmpty then throw IllegalArgumentException("The similarity sort requires a description")
        val described = base.join(DescribedRow)((row, described) => row.id `=` described.id)
        val (row, similar) = WithSqlExpr.get(described)
        Db.render(
          described.map(_ => (row.id, day.fold(nullDay)(_(row)), similar.similarity, row.originalCreatedAt)),
          engine.dialect)
      else
        val text = query.requireResolvedText
        Db.render(
          base.map(row => (row.id, day.fold(nullDay)(_(row)), relevance(engine, row, text), row.originalCreatedAt)),
          engine.dialect)
    sql"scored (id, day, sort_value, second_sort_value) AS MATERIALIZED (${scores.withCompleteQuery(false)})"

  /**
   * Rows strictly after the cursor's anchor in day order, compared lexicographically with independent directions (day, sort
   * value, ID). A redundant inclusive bound on the day lets the day index seek straight to the boundary.
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
        // A trailing null block comes from the guarded slice
        Expr[Boolean](implicit ctx => sql"$day $dayOp= $anchor AND ($day $dayOp $anchor OR $after)")
      case None if engine.nullsFirst(direction) =>
        // All dated days follow the leading null group, regardless of the secondary sort.
        Expr[Boolean](implicit ctx => sql"($day IS NOT NULL OR $after)")
      case None =>
        Expr[Boolean](implicit ctx => sql"$day IS NULL AND $after")

  /**
   * Rows of the anchor's own group strictly after it: a later sort value, or the same one and a greater ID. Nulls sit where the
   * engine natively orders them, and the comparison honors that. With `idOnly` it compares the ID alone: every sort value in the
   * group is null, or there is no sort. Under a scored sort the capture time comes between the score and the ID
   * ([[afterByCaptureTime]]).
   *
   * With `seeks` the sort leads the slice's order: a redundant inclusive bound lets an index seek straight to the anchor, and the
   * rows without a value that follow an anchor with one are left to the caller's second slice, since no range reaches them.
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

      cursorSortValue(sort, cursor.sortValue) match
        case SortValue.Null if engine.nullsFirst(sort.direction) =>
          Expr[Boolean](implicit ctx => sql"($column IS NOT NULL OR $afterById)")
        case SortValue.Null =>
          Expr[Boolean](implicit ctx => sql"($column IS NULL AND $afterById)")
        case value =>
          // What decides among the rows of the anchor's own sort value
          val afterTie =
            if sort.isScored then afterByCaptureTime(engine, cursor.requireSecondSortValue, position.taken, afterById)
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
   * Rows of the anchor's own score strictly after it, newest capture first, then by ID. Rows without a capture time are last on
   * both engines, as [[orderWithinGroup]] places them.
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
  private[search] def sliced[Q, R](
      engine: SearchDialect,
      projected: Select[Q, R],
      order: Context ?=> SqlStr,
      limit: SqlStr): SqlStr =
    Db.render(orderedBy(projected, order + limit), engine.dialect).withCompleteQuery(false)

  /** A trailing slice's limit: it fills the page only once the leading slice ran out, i.e. fetched no more than a page */
  private def guardedLimit(leading: String, rpp: Int): SqlStr =
    SqlStr.raw(s" LIMIT CASE WHEN (SELECT count(*) FROM $leading) > $rpp THEN 0 ELSE ${rpp + 1} END")

  /**
   * An asset column [[AssetRow]] does not map (a Scene embedding, SQLite's rowid), under the name `relation` gives its asset, so
   * a hand-written fragment can sit among the relation's typed predicates. The relation must read `FROM asset` alone.
   */
  private[search] def assetColumn(relation: Select[AssetRow[Expr], AssetRow[Sc]], name: String): Expr[?] =
    Select.toSimpleFrom(relation).from match
      case Seq(asset: TableRef) =>
        Expr[Any] {
          implicit ctx =>
            // A context that names no table for the asset renders its columns bare, as `scalasql.query.Column` does
            SqlStr.raw(ctx.fromNaming.get(asset).filter(_.nonEmpty).fold(name)(table => s"$table.$name"))
        }
      case other => throw IllegalStateException(s"A search reads the asset table alone, not $other")

  /** The matches that carry a SigLIP 2 embedding: the ones a Describe search can rank */
  private[search] def embedded(relation: Select[AssetRow[Expr], AssetRow[Sc]]): Select[AssetRow[Expr], AssetRow[Sc]] =
    val embedding = assetColumn(relation, SceneModel.Siglip2.column)
    relation.filter(_ => Expr[Boolean](implicit ctx => sql"$embedding IS NOT NULL"))

  /** The matching IDs alone, for a count that has to agree with the rows */
  private def matchingIds(
      engine: SearchDialect,
      base: Select[AssetRow[Expr], AssetRow[Sc]],
      extra: Option[AssetRow[Expr] => Expr[Boolean]]): SqlStr =
    import engine.dialect.*
    Db.render(extra.fold(base)(base.filter).map(_.id), engine.dialect).withCompleteQuery(false)

  /** The matching IDs up to one past the cap: all a capped count needs */
  private def cappedIds(engine: SearchDialect, base: Select[AssetRow[Expr], AssetRow[Sc]], cap: Int): SqlStr =
    matchingIds(engine, base, None) + SqlStr.raw(s" LIMIT ${cap + 1}")

  /** A page's overall count as its CTE, column and join; all empty for a page that counts no total */
  private case class TotalFragments(cte: Option[SqlStr], column: SqlStr, join: SqlStr)

  private def totalFragments(
      engine: SearchDialect,
      base: Select[AssetRow[Expr], AssetRow[Sc]],
      isTotalCounted: Boolean,
      cap: Int): TotalFragments =
    if isTotalCounted then
      TotalFragments(
        Some(sql"total AS MATERIALIZED (SELECT count(*) AS n FROM (${cappedIds(engine, base, cap)}) AS m)"),
        SqlStr.raw(", t.n AS total"),
        SqlStr.raw("CROSS JOIN total AS t")
      )
    else TotalFragments(None, SqlStr.empty, SqlStr.empty)

  /**
   * What a search sorts by: the sort's column or the Relevance. The file name sorts by its stored `SortKey` (`filename_sort`), so
   * both engines order it in the natural order (`SortKey`); the key is in no row class, so no asset read carries it
   * ([[unmappedAssetColumn]]), and a cursor carries the file name, whose key the continuation binds ([[cursorSortValue]]). The
   * similarity is not an expression of the asset's row and is read from `scored` ([[scoredCte]]).
   */
  private def sortColumn(engine: SearchDialect, query: SearchQuery, row: AssetRow[Expr]): Expr[?] =
    val sort = query.searchSort.head
    if sort.isSimilarity then throw IllegalStateException("The similarity is read from the scored matches")
    if sort.isRelevance then relevance(engine, row, query.requireResolvedText)
    else if sort.field == FieldConst.Asset.FILENAME then unmappedAssetColumn(FieldConst.Asset.FILENAME_SORT)
    else Columns.required(AssetRow, row, sort.field, engine.dialect)

  /**
   * An asset column no row class maps, under the name the rendering context gives the one asset table it reads: the table
   * reference the row's own columns render with, so it sits among them wherever they are rendered
   */
  private def unmappedAssetColumn(name: String): Expr[Any] =
    Expr[Any] {
      implicit ctx =>
        ctx.fromNaming.toList.collect { case (asset: TableRef, alias) if asset.value == AssetRow => alias } match
          case List(alias) => SqlStr.raw(if alias.isEmpty then name else s"$alias.$name")
          case aliases =>
            throw IllegalStateException(s"An asset column needs one asset table in its context, not ${aliases.size}")
    }

  /**
   * The cursor's sort value as the statement compares it with the sort's column. A file name sort's cursor carries the anchor's
   * file name, a short text, and is compared by the name's `SortKey`, the bytes `filename_sort` holds.
   */
  private def cursorSortValue(sort: SearchSort, value: SortValue): SortValue = value match
    case SortValue.Text(name) if sort.field == FieldConst.Asset.FILENAME => SortValue.Bytes(SortKey.of(name).bytes)
    case other => other

  /** What a row is sorted by, read as the engine stores it, so a cursor can bind it back unchanged */
  private def sortValueOf(position: Position)(using TypeMapper[SortValue]): Expr[SortValue] =
    Expr[SortValue](implicit ctx => sql"${position.sortValue}")

  /**
   * The order term after the sort: the capture time as stored under a scored sort; NULL under a column sort, whose next term is
   * the ID, so every candidate relation has the same columns
   */
  private def secondSortValueOf(sort: SearchSort, position: Position)(using TypeMapper[SortValue]): Expr[SortValue] =
    if sort.isScored then Expr[SortValue](implicit ctx => sql"${position.taken}") else nullSortValue

  /** The sort value of a page with no sort, whose only order is the ID, and the second sort value under a column sort */
  private def nullSortValue(using TypeMapper[SortValue]): Expr[SortValue] = Expr[SortValue](implicit ctx => sql"NULL")

  /** The day of a row that is in no day group: under a Location or a Sequence grouping, and on a flat page */
  private def nullDay: Expr[Option[LocalDate]] = Expr[Option[LocalDate]](implicit ctx => sql"NULL")

  /**
   * The ORDER BY terms within a group: the sort, then the ID. Under a scored sort the newest capture time comes between them,
   * nulls last on both engines: no index orders a computed value, so an explicit null placement forfeits nothing. The engine may
   * decorate a column sort's term to steer its planner.
   */
  private def orderWithinGroup(engine: SearchDialect, query: SearchQuery, position: Position)(using Context): SqlStr =
    val sort = query.searchSort.head
    val direction = towards(sort.direction)
    if sort.isScored then sql"${position.sortValue} $direction, ${position.taken} DESC NULLS LAST, ${position.id} ASC"
    else
      sql"${query.grouping.fold(position.sortValue)(engine.secondarySort(position.sortValue, sort, _))} $direction, ${position.id} ASC"

  /** [[orderWithinGroup]] over the columns of a candidate relation, which carries what the slice computed */
  private def candidateOrderWithinGroup(prefix: String, sort: SearchSort): String =
    val byCaptureTime = if sort.isScored then s"${prefix}second_sort_value DESC NULLS LAST, " else ""
    s"${prefix}sort_value ${sort.direction}, $byCaptureTime${prefix}id ASC"

  private def towards(direction: SortDirection): SqlStr = SqlStr.raw(direction.toString)

  /** The flat and day candidates' columns. Naming them on the CTE leaves the projection free to alias its own columns. */
  private val narrowColumnList: SqlStr = SqlStr.raw("id, day, sort_value, second_sort_value")
  private val narrowColumns: SqlStr = sql"($narrowColumnList)"

  /** The Location candidates' columns: the asset, its Location and what heads the group, and the sort keys */
  private val locationColumnList: SqlStr =
    SqlStr.raw("id, location_id, path_key, location_name, category_name, sort_value, second_sort_value")
  private val locationColumns: SqlStr = sql"($locationColumnList)"

  /** The Sequence candidates' columns: the asset, its Sequence and that Sequence's first capture, and the sort keys */
  private val sequenceColumnList: SqlStr = SqlStr.raw("id, sequence_id, first_captured_at, sort_value, second_sort_value")
  private val sequenceColumns: SqlStr = sql"($sequenceColumnList)"

  /** The asset columns a page selects, in [[AssetRow]]'s order, so a row reads back positionally */
  private val assetColumns: SqlStr = SqlStr.raw(AssetRow.columnList())

  /** [[assetColumns]] with the bulky metadata JSON read as NULL, for a page that shows neither (a Describe search's) */
  private val assetColumnsWithoutBulkyMetadata: SqlStr = SqlStr.raw(AssetRow.columnList(BulkyMetadata.AsNull))

  /**
   * One source of one alternative of one group of the Search text, with the IDs the term resolved to there (none for the Search
   * document, matched by the term's words). Its name is its position in the text.
   */
  private case class TextSource(group: Int, alternative: Int, term: ResolvedSearchTerm, source: SearchSource, ids: Set[String]):
    val name: String = s"text_${group}_${alternative}_${source.toString.toLowerCase}"

    /** A folder is the asset's own column, which a statement tests directly; every other source is a relation of asset IDs */
    val isAssetColumn: Boolean = source == SearchSource.Folder

  /** The sources of each alternative of a group: the name sources the term resolved any IDs in, then always the Search document */
  private def textSources(group: ResolvedSearchGroup, groupIndex: Int): Seq[Seq[TextSource]] =
    group.alternatives.zipWithIndex.map {
      (term, alternative) =>
        term.ids.toSeq
          .sortBy(_._1.ordinal)
          .map((source, ids) => TextSource(groupIndex, alternative, term, source, ids)) :+
          TextSource(groupIndex, alternative, term, SearchSource.Document, Set())
    }

  private def allTextSources(text: ResolvedSearchText): Seq[TextSource] =
    text.groups.zipWithIndex.flatMap((group, groupIndex) => textSources(group, groupIndex).flatten)

  /**
   * The asset IDs a source matches. The ID sources are within the repository by construction (their IDs were resolved from its
   * names), each read from an index alone. The document is scoped by the asset testing membership, so only a caller reading the
   * relation on its own passes `repositoryId`.
   */
  private def sourceRelation(engine: SearchDialect, source: TextSource, repositoryId: Option[String]): SqlStr =
    import engine.dialect.*

    def inSet(column: Expr[String]): Expr[Boolean] = Columns.isInSet(column, source.ids, engine.dialect)

    val relation = source.source match
      case SearchSource.Person => FaceRow.select.filter(face => inSet(face.personId)).map(_.assetId)
      case SearchSource.Location | SearchSource.Category =>
        LocationAssetRow.select.filter(link => inSet(link.locationId)).map(_.assetId)
      case SearchSource.Folder =>
        AssetRow.select.filter(asset => Columns.isInSet(asset.folderId, source.ids, engine.dialect)).map(_.id)
      case SearchSource.Album => AlbumAssetRow.select.filter(link => inSet(link.albumId)).map(_.assetId)
      case SearchSource.Document =>
        SearchDocumentRow.select
          .filter(document => engine.textMatch(document, source.term.term))
          .filterIf(repositoryId.isDefined)(document => document.repositoryId `=` repositoryId.get)
          .map(_.assetId)

    Db.render(relation, engine.dialect).withCompleteQuery(false)

  /**
   * The Search text's CTEs on the broad path: each source built once and materialized, so every predicate, the Relevance and the
   * counts test membership in the same set. A folder needs none; the selective path tests the asset's own row instead.
   */
  private def textCtes(engine: SearchDialect, query: SearchQuery): Seq[SqlStr] =
    if !query.isText || query.requireResolvedText.candidates.isDefined then Nil
    else
      allTextSources(query.requireResolvedText)
        .filterNot(_.isAssetColumn)
        .map(source => sql"${SqlStr.raw(source.name)} (asset_id) AS MATERIALIZED (${sourceRelation(engine, source, None)})")

  /**
   * Whether the asset is among a source's matches: by its own column for a folder, through the source's CTE on the broad path, or
   * on the selective path by a probe through an index that leads with the asset, run for no more than the candidates
   */
  private def isIn(engine: SearchDialect, asset: AssetRow[Expr], text: ResolvedSearchText, source: TextSource): Expr[Boolean] =
    import engine.dialect.*

    def inSet(column: Expr[String]): Expr[Boolean] = Columns.isInSet(column, source.ids, engine.dialect)

    if source.isAssetColumn then inFolders(engine, asset, source.ids)
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
   * Assets that satisfy every group of the Search text by any of its alternatives. An excluded term is satisfied by none of its
   * sources, so an asset without a document, or in no folder, satisfies an exclusion. On the broad path a group of one excluded
   * term is an anti-join, which the engine plans as such. On the selective path the candidates already satisfy every complete
   * group, and only the other groups are tested.
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
   * The Relevance of an asset to the Search text: the sum over groups of the best source any alternative matched in. The sources
   * are one `CASE`, highest score first, so alternatives joined by `OR` count once. Excluded terms score nothing.
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
   * Assets carrying every requested metadata value. A `metadata_parameter` row holds one value, so the filters are OR-ed and an
   * asset's surviving rows must number as many as the filters.
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

  /**
   * One metadata filter, against the column of the value's type (a keyword field searched by number matches nothing). A keyword
   * is compared under its name key, as `field_value_kw` holds it, so it matches in any case or Unicode form.
   */
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

    val (column, compared) = plain match
      case keyword: String => (parameter.fieldValueKw, NameKey.of(keyword))
      case _: Boolean => (parameter.fieldValueBool, plain)
      case _: Number => (parameter.fieldValueNum, plain)
      case other => throw IllegalArgumentException(s"This type of parameter is not supported: $other")

    (parameter.fieldId `=` fieldId) && Columns.equalTo(column, compared, engine.dialect)

  /** Assets in any of the given folders themselves; a subtree is the caller's to expand */
  private def folderFilter(engine: SearchDialect, asset: AssetRow[Expr], folderIds: Set[String]): Expr[Boolean] =
    Columns.isInSet(asset.folderId, folderIds, engine.dialect)

  /**
   * [[folderFilter]] as a source of the Search text: false, never NULL, for an asset in no folder (a triaged one), since the text
   * negates its sources and a NULL negated would drop the asset from an exclusion of a folder's name
   */
  private def inFolders(engine: SearchDialect, asset: AssetRow[Expr], folderIds: Set[String]): Expr[Boolean] =
    val inAny = folderFilter(engine, asset, folderIds)
    Expr[Boolean](implicit ctx => sql"(${asset.folderId} IS NOT NULL AND $inAny)")

  /**
   * Assets captured on a day of the range, by the camera's wall clock. The day is the expression `asset_search_date_taken` is
   * built on, so a range is a seek in that index, and `asset_search_created` carries the capture time, so a Date Imported page
   * tests the range on its index. An asset with no capture time is in no range, not even one open at an end.
   */
  private def takenFilter(engine: SearchDialect, asset: AssetRow[Expr], range: DateRange): Expr[Boolean] =
    import engine.dialect.*

    val day = engine.day(asset, FieldConst.Asset.ORIGINAL_CREATED_AT)
    val fromFirst = range.from.map(first => Expr[Boolean](implicit ctx => sql"$day >= $first"))
    val toLast = range.to.map(last => Expr[Boolean](implicit ctx => sql"$day <= $last"))

    withinBoth(engine, fromFirst, toLast)

  /**
   * Assets imported on a day of the range, the days being those of `zone`, so a day the clocks change is as long as the zone
   * makes it. The import time is compared bare, so a range is a seek in `asset_search_created`, and `asset_search_date_taken`
   * carries it, so a Date Taken page tests the range on its index.
   *
   * A range ending on the last day of year 9999 is open at that end: the next day begins in a five-digit year, which SQLite's
   * text writes with a sign and so orders before every other, and no import is that late.
   */
  private def importedFilter(engine: SearchDialect, asset: AssetRow[Expr], range: DateRange, zone: ZoneId): Expr[Boolean] =
    import engine.dialect.*

    def startOf(day: LocalDate): OffsetDateTime = day.atStartOfDay(zone).toOffsetDateTime
    val fromFirst = range.from.map(first => Expr[Boolean](implicit ctx => sql"${asset.createdAt} >= ${startOf(first)}"))
    val beforeNext = range.to
      .map(last => startOf(last.plusDays(1)))
      .filter(_.withOffsetSameInstant(ZoneOffset.UTC).getYear <= MAX_STORED_YEAR)
      .map(next => Expr[Boolean](implicit ctx => sql"${asset.createdAt} < $next"))

    if fromFirst.isEmpty && beforeNext.isEmpty then Expr[Boolean](implicit ctx => sql"${asset.createdAt} IS NOT NULL")
    else withinBoth(engine, fromFirst, beforeNext)

  // The last year an instant is stored in on both engines in one form: four digits
  private val MAX_STORED_YEAR = 9999

  /** The bounds of a date range AND-ed; a range has at least one end */
  private def withinBoth(engine: SearchDialect, lower: Option[Expr[Boolean]], upper: Option[Expr[Boolean]]): Expr[Boolean] =
    import engine.dialect.*

    (lower ++ upper).reduce(_ && _)

  /**
   * Assets the given people appear in: any of them in one semi-join, or all of them in one semi-join per person. A person's faces
   * are read from an index alone (`face_02`, or `face_01` where PostgreSQL probes by the asset), so the cost follows the chosen
   * people's Faces and not the library.
   */
  private def peopleFilter(
      engine: SearchDialect,
      asset: AssetRow[Expr],
      personIds: Set[String],
      peopleMatch: PeopleMatch): Expr[Boolean] =
    import engine.dialect.*

    peopleMatch match
      case PeopleMatch.Any => personFilter(engine, asset, personIds)
      // In ID order, so one search always renders one statement
      case PeopleMatch.All => personIds.toList.sorted.map(id => personFilter(engine, asset, Set(id))).reduce(_ && _)

  /** Assets any of the given people appear in; it reads the face's own person ID alone */
  private def personFilter(engine: SearchDialect, asset: AssetRow[Expr], personIds: Set[String]): Expr[Boolean] =
    import engine.dialect.*

    FaceRow.select
      .filter(face => Columns.isInSet(face.personId, personIds, engine.dialect))
      .map(_.assetId)
      .contains(asset.id)

  private def albumFilter(engine: SearchDialect, asset: AssetRow[Expr], albumIds: Set[String]): Expr[Boolean] =
    import engine.dialect.*

    AlbumAssetRow.select
      .filter(link => Columns.isInSet(link.albumId, albumIds, engine.dialect))
      .map(_.assetId)
      .contains(asset.id)

  private def locationFilter(engine: SearchDialect, asset: AssetRow[Expr], locationIds: Set[String]): Expr[Boolean] =
    import engine.dialect.*

    LocationAssetRow.select
      .filter(link => Columns.isInSet(link.locationId, locationIds, engine.dialect))
      .map(_.assetId)
      .contains(asset.id)

  /** Assets with a Face of an Unknown person: unnamed, and not hidden, merged away (`is_deleted`) or a bad match */
  private def unknownPeopleFilter(engine: SearchDialect, asset: AssetRow[Expr]): Expr[Boolean] =
    import engine.dialect.*

    FaceRow.select
      .join(PersonRow)((face, person) => face.personId `=` person.id)
      .filter {
        (_, person) =>
          (person.isNamed `=` false) && (person.isHidden `=` false) && (person.isDeleted `=` false) &&
          (person.isBadMatch `=` false)
      }
      .map((face, _) => face.assetId)
      .contains(asset.id)

  /** Assets no Location points at; a GPS position of their own does not count */
  private def noLocationFilter(engine: SearchDialect, id: Expr[String]): Expr[Boolean] =
    import engine.dialect.*

    LocationAssetRow.select.filter(link => link.assetId `=` id).map(_.assetId).isEmpty

  /**
   * Assets plotted inside the box, by the map's rule: by their own point or, without one, by the pin of a Location they are in.
   * The two are a union of IDs so each branch is bounded by an index, where alternatives on the asset's row would test every
   * match.
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

    val pinned = membersOfLocationsIn(engine, bbox, repositoryId)
      .join(AssetRow)((memberId, member) => memberId `=` member.id)
      .filter((_, member) => member.latitude.isEmpty)
      .map((memberId, _) => memberId)

    Expr[Boolean] {
      implicit ctx =>
        sql"(${asset.id} IN (${Db.render(located, engine.dialect).withCompleteQuery(false)} UNION ALL ${Db.render(pinned, engine.dialect).withCompleteQuery(false)}))"
    }

  /**
   * The IDs of the repository's assets in its Locations pinned inside the box, an asset once per such Location. The caller's
   * asset is scoped already; the repository here keeps the read to the repository's Locations (`location_01`), not every
   * instance's.
   */
  private def membersOfLocationsIn(engine: SearchDialect, bbox: BoundingBox, repositoryId: String): Select[Expr[String], String] =
    import engine.dialect.*

    LocationAssetRow.select
      .join(LocationRow)((link, location) => link.locationId `=` location.id)
      .filter(
        (_, location) => (location.repositoryId `=` repositoryId) && inBox(engine, bbox, location.latitude, location.longitude))
      .map((link, _) => link.assetId)

  /** A point inside the box; a box across the antimeridian covers both sides of it */
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
