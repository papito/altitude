package altitude.core.unit

import altitude.test.TestFocus
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers.*
import scalasql.Table
import scalasql.core.DbApi
import scalasql.core.Queryable
import scalasql.core.SqlStr

import altitude.core.SearchCursorException
import altitude.core.dao.sql.Db
import altitude.core.dao.sql.search.PostgresSearchDialect
import altitude.core.dao.sql.search.SearchDialect
import altitude.core.dao.sql.search.SearchQueries
import altitude.core.dao.sql.search.SqliteSearchDialect
import altitude.core.dao.sql.tables.AssetRow
import altitude.core.util.BoundingBox
import altitude.core.util.GroupBy
import altitude.core.util.Query
import altitude.core.util.ResolvedSearchGroup
import altitude.core.util.ResolvedSearchTerm
import altitude.core.util.ResolvedSearchText
import altitude.core.util.SearchCursor
import altitude.core.util.SearchGrouping
import altitude.core.util.SearchQuery
import altitude.core.util.SearchSort
import altitude.core.util.SearchSource
import altitude.core.util.SearchText
import altitude.core.util.SortDirection
import altitude.core.util.SortValue

/**
 * The SQL a search renders to.
 *
 * The assertions are structural: the joined-in filters are semi-joins on `asset.id` rather than comma joins, every value is bound
 * rather than inlined, and the ordering terms come out in page order. Behaviour itself is covered by the integration suites,
 * which run every one of these shapes against both engines.
 */
@DoNotDiscover class SearchSqlTests extends funsuite.AnyFunSuite with TestFocus {

  private val engines = List("postgres" -> PostgresSearchDialect, "sqlite" -> SqliteSearchDialect)

  test("Every search is scoped to its repository and to processed assets alone") {
    for ((engine, dialect) <- engines) withClue(engine) {
      val sql = flat(dialect, new SearchQuery())

      sql.contains("FROM asset asset0") shouldBe true
      sql.contains("asset0.repository_id = ?") shouldBe true
      sql.contains("asset0.is_pipeline_processed = ?") shouldBe true
      placeholders(sql) shouldBe 2
    }
  }

  test("A flat page is one SELECT with a window count, ordered by the sort and then the ID") {
    for ((engine, dialect) <- engines) withClue(engine) {
      val query = new SearchQuery(rpp = 25, page = 3, searchSort = List(SearchSort("filename", SortDirection.DESC)))
      val sql = flat(dialect, query)

      withClue(sql) {
        // One SELECT level: the total is a window over the match, not a wrapping subquery over a comma join
        "SELECT".r.findAllIn(sql).size shouldBe 1
        sql.contains("COUNT(1) OVER ()") shouldBe true
        "ORDER BY \\S*filename DESC, \\S*id ASC".r.findFirstIn(sql).isDefined shouldBe true
        sql.contains("LIMIT ?") shouldBe true
        sql.contains("OFFSET ?") shouldBe true
        sql.contains("NULLS FIRST") shouldBe false
        sql.contains("NULLS LAST") shouldBe false
      }
    }
  }

  test("Text search is a semi-join on the search document, in the engine's own dialect") {
    val postgres = flat(PostgresSearchDialect, textQuery("beach"))
    val sqlite = flat(SqliteSearchDialect, textQuery("beach"))

    withClue(postgres) {
      postgres.contains("asset0.id IN (SELECT") shouldBe true
      postgres.contains("FROM search_document search_document1") shouldBe true
      postgres.contains("tsv @@ to_tsquery('simple', ?)") shouldBe true
      // repository, pipeline flag, the document's repository and the text
      placeholders(postgres) shouldBe 4
    }

    withClue(sqlite) {
      sqlite.contains("asset0.id IN (SELECT") shouldBe true
      sqlite.contains("FROM search_document search_document1") shouldBe true
      sqlite.contains("rowid IN (SELECT rowid FROM search_document_fts WHERE search_document_fts MATCH ?)") shouldBe true
      placeholders(sqlite) shouldBe 4
    }
  }

  test("Each term of the Search text is its own semi-join: groups AND-ed, alternatives OR-ed, an exclusion negated") {
    for ((engine, dialect) <- engines) {
      val sql = flat(dialect, textQuery("""beach OR -lake "golden gate""""))

      withClue(s"$engine: $sql") {
        """\(\(asset0\.id IN \(SELECT .*?\)\) OR \(NOT \(asset0\.id IN \(SELECT .*?\)\)\)\) AND \(asset0\.id IN \(SELECT""".r
          .findFirstIn(sql)
          .isDefined shouldBe true
        "FROM search_document ".r.findAllIn(sql).size shouldBe 3
        // repository and pipeline flag, then the document's repository and the engine's query string per term
        placeholders(sql) shouldBe 8
      }
    }
  }

  test("A term is bound as one query string in the engine's syntax: a prefix on the last word unless it is a phrase") {
    val text = """beach IMG_12 "golden gate" -"lake""""

    textBinds(PostgresSearchDialect, text) shouldBe List("beach:*", "img <-> 12:*", "golden <-> gate", "lake")
    textBinds(SqliteSearchDialect, text) shouldBe List("\"beach\" *", "\"img 12\" *", "\"golden gate\"", "\"lake\"")
  }

  test("A term with a camelCase hump is bound as one query string, the OR of its readings") {
    val text = """McDonald "LaGuardia airport""""

    textBinds(PostgresSearchDialect, text) shouldBe
      List("mc <-> donald:* | mcdonald:*", "la <-> guardia <-> airport | laguardia <-> airport")
    textBinds(SqliteSearchDialect, text) shouldBe
      List("\"mc donald\" * OR \"mcdonald\" *", "\"la guardia airport\" OR \"laguardia airport\"")
  }

  test("Text without a usable term adds no filter") {
    for ((engine, dialect) <- engines) withClue(engine) {
      flat(dialect, new SearchQuery(text = Some("- !!! OR"))).contains("search_document") shouldBe false
    }
  }

  test("A term is the OR of the name sources it resolved to and the document, each ID set bound as one parameter") {
    val ids = Map(
      SearchSource.Album -> Set("a1"),
      SearchSource.Person -> Set("p1", "p2"),
      SearchSource.Folder -> Set("f1", "f2", "f3"),
      SearchSource.Category -> Set("l2"),
      SearchSource.Location -> Set("l1")
    )

    for ((engine, dialect) <- engines) {
      val sql = flat(dialect, textQuery("alice", ids))
      val inSet = if (dialect == PostgresSearchDialect) raw"= ANY\(\?\)" else raw"IN \(SELECT value FROM json_each\(\?\)\)"
      val semiJoin = raw"\(asset0\.id IN \(SELECT "

      withClue(s"$engine: $sql") {
        // Sources in declaration order: person, Location, Category (as its Locations), folder, album, document
        (raw"${semiJoin}face\d\.asset_id .*?person\d\.id $inSet\)+ OR " +
          raw"$semiJoin.*?location_asset\d\.location_id $inSet\)+ OR " +
          raw"$semiJoin.*?location_asset\d\.location_id $inSet\)+ OR " +
          raw"asset0\.folder_id $inSet\)+ OR " +
          raw"$semiJoin.*?album_asset\d\.album_id $inSet\)+ OR " +
          raw"${semiJoin}search_document\d\.asset_id").r.findFirstIn(sql).isDefined shouldBe true
        // repository and pipeline flag, one parameter per ID set, the document's repository and query string
        placeholders(sql) shouldBe 9
      }
    }
  }

  test("An excluded term must not match in any source") {
    for ((engine, dialect) <- engines) {
      val sql = flat(dialect, textQuery("-alice", Map(SearchSource.Person -> Set("p1"))))

      withClue(s"$engine: $sql") {
        """\(NOT \(\(asset0\.id IN \(SELECT face.*?\)\) OR \(asset0\.id IN \(SELECT search_document.*?\)\)\)\)""".r
          .findFirstIn(sql)
          .isDefined shouldBe true
      }
    }
  }

  test("An ID set is bound whole: an array on PostgreSQL, a JSON array on SQLite") {
    val query = new SearchQuery(folderIds = Set("f1", "f2"))

    bound(PostgresSearchDialect, query) should contain(Set("f1", "f2"))
    bound(SqliteSearchDialect, query) should contain("""["f1","f2"]""")
  }

  test("Text that was not resolved against the names is refused, not searched by the document alone") {
    for ((engine, dialect) <- engines) withClue(engine) {
      intercept[IllegalStateException](flat(dialect, new SearchQuery(text = Some("beach"))))
    }
  }

  test("Metadata filters are one semi-join that counts the values an asset carries") {
    val query =
      new SearchQuery(metadataFilters = Map("kw_field" -> "beach", "num_field" -> 12, "bool_field" -> Query.EQUALS(false)))
    val sql = flat(PostgresSearchDialect, query)

    withClue(sql) {
      sql.contains("asset0.id IN (SELECT") shouldBe true
      sql.contains("FROM metadata_parameter metadata_parameter1") shouldBe true
      sql.contains("metadata_parameter1.field_value_kw = ?") shouldBe true
      sql.contains("metadata_parameter1.field_value_num = ?") shouldBe true
      sql.contains("metadata_parameter1.field_value_bool = ?") shouldBe true
      sql.contains("GROUP BY metadata_parameter1.asset_id") shouldBe true
      sql.contains("HAVING (COUNT(1) >= ?)") shouldBe true

      // The outer query neither joins nor groups: the filter is entirely inside the subquery
      sql.contains("FROM asset asset0") shouldBe true
      "GROUP BY".r.findAllIn(sql).size shouldBe 1
      // repository, pipeline flag, the parameter repository, three field/value pairs and the count
      placeholders(sql) shouldBe 10
    }
  }

  test("Person and album filters are semi-joins too") {
    val people = flat(SqliteSearchDialect, new SearchQuery(personIds = Set("p1", "p2")))
    val albums = flat(PostgresSearchDialect, new SearchQuery(albumIds = Set("a1")))

    withClue(people) {
      people.contains("asset0.id IN (SELECT") shouldBe true
      people.contains("FROM face face1") shouldBe true
      people.contains("JOIN person person2") shouldBe true
      people.contains("person2.id IN (SELECT value FROM json_each(?))") shouldBe true
    }

    withClue(albums) {
      albums.contains("FROM album_asset album_asset1") shouldBe true
      albums.contains("album_asset1.album_id = ANY(?)") shouldBe true
    }
  }

  test("Location and bounding-box filters are semi-joins that bind their values") {
    val byLocation = flat(SqliteSearchDialect, new SearchQuery(locationIds = Set("l1", "l2")))
    withClue(byLocation) {
      byLocation.contains("asset0.id IN (SELECT") shouldBe true
      byLocation.contains("FROM location_asset location_asset1") shouldBe true
      byLocation.contains("location_asset1.location_id IN (SELECT value FROM json_each(?))") shouldBe true
      // repository, pipeline flag and the ID set
      placeholders(byLocation) shouldBe 3
    }

    // An asset's own point, or - with no point of its own - the pin of a Location it is in
    val box = flat(PostgresSearchDialect, new SearchQuery(bbox = Some(BoundingBox(48.0, 2.0, 49.0, 3.0))))
    withClue(box) {
      box.contains("asset0.latitude BETWEEN ? AND ? AND asset0.longitude BETWEEN ? AND ?") shouldBe true
      box.contains("(asset0.latitude IS NULL) AND (asset0.id IN (SELECT") shouldBe true
      box.contains("FROM location_asset location_asset1") shouldBe true
      box.contains("JOIN location location2 ON") shouldBe true
      box.contains("location2.latitude BETWEEN ? AND ? AND location2.longitude BETWEEN ? AND ?") shouldBe true
      // repository, pipeline flag, four edges for the asset and four for the Location pin
      placeholders(box) shouldBe 10
    }

    // Across the antimeridian the longitude range is two open-ended halves
    val across = flat(SqliteSearchDialect, new SearchQuery(bbox = Some(BoundingBox(-1.0, 179.0, 1.0, -179.0))))
    withClue(across) {
      across.contains("(asset0.longitude >= ? OR asset0.longitude <= ?)") shouldBe true
      across.contains("BETWEEN ? AND ? AND (") shouldBe true
    }
  }

  test("A count is one COUNT over the matching relation") {
    for ((engine, dialect) <- engines) withClue(engine) {
      val query = textQuery("beach", params = Map("is_recycled" -> false), locationIds = Set("l1"))
      val sql = DbApi.renderSql(SearchQueries.count(dialect, query, "repo-1"), Db.config, dialect.dialect)

      withClue(sql) {
        sql.startsWith("SELECT COUNT(1)") shouldBe true
        sql.contains("FROM asset asset0") shouldBe true
        sql.contains("asset0.is_recycled = ?") shouldBe true
        sql.contains("location_asset") shouldBe true
        sql.contains("ORDER BY") shouldBe false
        sql.contains("LIMIT") shouldBe false
      }
    }
  }

  test("Folder and column filters bind their values") {
    val query = new SearchQuery(params = Map("is_recycled" -> false), folderIds = Set("f1", "f2"))
    val sql = flat(SqliteSearchDialect, query)

    withClue(sql) {
      sql.contains("asset0.is_recycled = ?") shouldBe true
      sql.contains("asset0.folder_id IN (SELECT value FROM json_each(?))") shouldBe true
      // repository, pipeline flag, the recycled flag and the folder set
      placeholders(sql) shouldBe 4
    }
  }

  /**
   * The tuned shape of a grouped page, over every combination that changes it: both engines, both group directions, a first page
   * and a continuation from either a dated or an undated anchor, sorted by the grouping column itself or by another.
   */
  test("A grouped page keeps its guarded null slices and its separately indexable day counts") {
    for {
      (engine, dialect) <- engines
      direction <- SortDirection.values.toList
      position <- List("first", "dated", "undated")
      field <- List("original_created_at", "filename")
    } {
      val day = if (engine == "sqlite") "date(asset0.original_created_at)" else "asset0.original_created_at::date"
      val cursor = Option.when(position != "first")(
        SearchCursor(
          key = Option.when(position == "dated")("2026-09-06"),
          groupId = None,
          sortValue = if (field == "original_created_at") SortValue.Null else SortValue.Text("image.jpg"),
          id = "id",
          scope = "scope"
        ))
      val query = new SearchQuery(
        params = Map("is_recycled" -> false),
        rpp = 50,
        folderIds = Set("folder"),
        metadataFilters = Map("tag" -> "beach"),
        searchSort = List(SearchSort(field, SortDirection.ASC)),
        grouping = Some(SearchGrouping(GroupBy.DateTaken, direction)),
        cursor = cursor
      )
      val statement = SearchQueries.grouped(dialect, query, "repo-1")
      val text = statement.toString.replaceAll("\\s+", " ").trim
      // Where this engine puts nulls for the grouping direction: leading nulls need no second slice
      val leads = if (engine == "sqlite") direction == SortDirection.ASC else direction == SortDirection.DESC

      withClue(s"$engine/$direction/$position/$field: $text") {
        text.contains("undated (id, day, sort_value, second_sort_value) AS MATERIALIZED") shouldBe (position == "dated" && !leads)
        text.contains(
          "LIMIT CASE WHEN (SELECT count(*) FROM dated) > 50 THEN 0 ELSE 51 END") shouldBe (position == "dated" && !leads)
        text.contains("FROM page WHERE day IS NOT NULL") shouldBe true
        text.contains("FROM page WHERE day IS NULL") shouldBe true
        text.contains("OR (d.day IS NULL AND p.day IS NULL)") shouldBe true
        text.contains("NULLS FIRST") shouldBe false
        text.contains("NULLS LAST") shouldBe false
        // Only a first page pays for the overall count
        text.contains("total AS MATERIALIZED") shouldBe (position == "first")
        text.count(_ == '?') shouldBe binds(statement)

        if (position == "undated" && field == "original_created_at") {
          val after = if (leads) s"($day IS NOT NULL OR asset0.id > ?)" else s"$day IS NULL AND asset0.id > ?"
          text.contains(after) shouldBe true
        }
      }
    }
  }

  test("A grouped slice reads in day-index order, with SQLite's planner hint on a non-grouping sort") {
    def slice(dialect: SearchDialect, field: String): String =
      val query = new SearchQuery(
        rpp = 50,
        searchSort = List(SearchSort(field, SortDirection.ASC)),
        grouping = Some(SearchGrouping(GroupBy.DateTaken, SortDirection.DESC)))
      SearchQueries.grouped(dialect, query, "repo-1").toString.replaceAll("\\s+", " ")

    // The day term is the engine's own index expression, and the ID is always the last, deterministic term
    slice(PostgresSearchDialect, "filename").contains(
      "ORDER BY asset0.original_created_at::date DESC, asset0.filename ASC, asset0.id ASC LIMIT 51") shouldBe true

    // SQLite is steered away from any index on a non-grouping sort term, so the day index stays in charge
    slice(SqliteSearchDialect, "filename").contains(
      "ORDER BY date(asset0.original_created_at) DESC, +asset0.filename ASC, asset0.id ASC LIMIT 51") shouldBe true
    slice(SqliteSearchDialect, "original_created_at").contains(
      "ORDER BY date(asset0.original_created_at) DESC, asset0.original_created_at ASC, asset0.id ASC LIMIT 51") shouldBe true
  }

  test("A cursor day is bound with a redundant inclusive bound, so the day index can seek to it") {
    val query = new SearchQuery(
      rpp = 50,
      searchSort = List(SearchSort("filename", SortDirection.ASC)),
      grouping = Some(SearchGrouping(GroupBy.DateTaken, SortDirection.DESC)),
      cursor = Some(SearchCursor(Some("2026-09-06"), None, SortValue.Text("image.jpg"), "id-1", "scope"))
    )
    val text = SearchQueries.grouped(PostgresSearchDialect, query, "repo-1").toString.replaceAll("\\s+", " ")

    withClue(text) {
      text.contains("asset0.original_created_at::date <= ?") shouldBe true
      text.contains(
        "(asset0.original_created_at::date < ? OR (asset0.filename > ? OR (asset0.filename = ? AND asset0.id > ?)))") shouldBe true
    }
  }

  test("Every branch of a grouped page selects from the same relation, so a count cannot drift from its rows") {
    val query = new SearchQuery(
      params = Map("is_recycled" -> false),
      rpp = 50,
      text = Some("beach"),
      resolvedText = resolved("beach"),
      searchSort = List(SearchSort("filename", SortDirection.ASC)),
      grouping = Some(SearchGrouping(GroupBy.DateTaken, SortDirection.DESC))
    )
    val text = SearchQueries.grouped(SqliteSearchDialect, query, "repo-1").toString.replaceAll("\\s+", " ")

    // The candidate slice, both day-count probes and the overall count: four copies of the same predicate
    withClue(text) {
      "search_document_fts MATCH \\?".r.findAllIn(text).size shouldBe 4
      "asset0\\.is_recycled = \\?".r.findAllIn(text).size shouldBe 4
    }
  }

  test("The grouped statement selects the asset columns its row class reads, in that order") {
    val query = new SearchQuery(
      rpp = 50,
      searchSort = List(SearchSort("filename", SortDirection.ASC)),
      grouping = Some(SearchGrouping(GroupBy.DateTaken, SortDirection.DESC)))
    val text = SearchQueries.grouped(PostgresSearchDialect, query, "repo-1").toString.replaceAll("\\s+", " ")

    val declared = Table.labels(AssetRow).map(Db.config.columnNameMapper).map(name => s"asset.$name").mkString(", ")
    text.contains(
      s"SELECT $declared, p.day AS day, p.sort_value AS sort_value, p.second_sort_value AS second_sort_value, " +
        "d.n AS day_total") shouldBe true
  }

  /**
   * The shape of a page grouped by Location, over what changes it: both engines, a first page and a continuation from an anchor
   * in a Location or in the trailing "No location" group, sorted by a nullable timestamp or by a text column.
   */
  test("A Location page slices the located and the unlocated relation, guards the second, and binds every value") {
    for {
      (engine, dialect) <- engines
      position <- List("first", "located", "unlocated")
      field <- List("original_created_at", "filename")
    } {
      val cursor = Option.when(position != "first")(
        SearchCursor(
          key = Option.when(position == "located")("italy\u0001rome"),
          groupId = Option.when(position == "located")("location-1"),
          sortValue = if (field == "original_created_at") SortValue.Null else SortValue.Text("image.jpg"),
          id = "id",
          scope = "scope"
        ))
      val query = new SearchQuery(
        params = Map("is_recycled" -> false),
        rpp = 50,
        folderIds = Set("folder"),
        text = Some("beach"),
        resolvedText = resolved("beach"),
        searchSort = List(SearchSort(field, SortDirection.ASC)),
        grouping = Some(SearchGrouping(GroupBy.Location)),
        cursor = cursor
      )
      val statement = SearchQueries.groupedByLocation(dialect, query, "repo-1")
      val text = statement.toString.replaceAll("\\s+", " ").trim
      val columns = "(id, location_id, path_key, location_name, category_name, sort_value, second_sort_value)"

      withClue(s"$engine/$position/$field: $text") {
        // The located slice and its guard on the unlocated one exist until the anchor is in the trailing group
        text.contains(s"located $columns AS MATERIALIZED") shouldBe (position != "unlocated")
        text.contains(s"unlocated $columns AS MATERIALIZED") shouldBe (position != "unlocated")
        text.contains("LIMIT CASE WHEN (SELECT count(*) FROM located) > 50 THEN 0 ELSE 51 END") shouldBe (position != "unlocated")
        text.contains(s"candidates $columns AS MATERIALIZED") shouldBe true
        // The located relation is the asset joined to its memberships, the Location and its category
        text.contains("JOIN location_asset location_asset1 ON") shouldBe (position != "unlocated")
        text.contains("LEFT JOIN location location3 ON") shouldBe (position != "unlocated")
        // The unlocated relation is the matching set less every asset in a Location
        text.contains("(NOT EXISTS (SELECT location_asset1.asset_id") shouldBe true
        // Located rows first, whatever the engine puts nulls, then path order
        text.contains("CASE WHEN c.location_id IS NULL THEN 1 ELSE 0 END, c.path_key ASC, c.location_id ASC") shouldBe true
        text.contains("FROM page WHERE location_id IS NOT NULL") shouldBe true
        text.contains("FROM page WHERE location_id IS NULL") shouldBe true
        text.contains("OR (g.location_id IS NULL AND p.location_id IS NULL)") shouldBe true
        text.contains("NULLS FIRST") shouldBe false
        text.contains("NULLS LAST") shouldBe false
        // Only a first page pays for the overall count, which counts assets, not memberships
        text.contains("total AS MATERIALIZED") shouldBe (position == "first")
        text.count(_ == '?') shouldBe binds(statement)
        // Every branch applies the same filters: the located slice, the unlocated slice, both group counts and the total
        "search_document_fts MATCH \\?|tsv @@ to_tsquery\\('simple', \\?\\)".r.findAllIn(text).size shouldBe
          (if (position == "unlocated") 3 else if (position == "first") 5 else 4)

        if (position == "located") {
          val pathKey = "COALESCE\\(location3\\.name_lc \\|\\| \\?, ''\\) \\|\\| location2\\.name_lc"
          s"\\($pathKey > \\? OR \\($pathKey = \\? AND \\(location2\\.id > \\? OR \\(location2\\.id = \\? AND ".r
            .findFirstIn(text)
            .isDefined shouldBe true
        }
      }
    }
  }

  test("A Location slice reads in path order, then the Location, the sort and the ID") {
    def slice(dialect: SearchDialect): String =
      val query = new SearchQuery(
        rpp = 50,
        searchSort = List(SearchSort("filename", SortDirection.DESC)),
        grouping = Some(SearchGrouping(GroupBy.Location)))
      SearchQueries.groupedByLocation(dialect, query, "repo-1").toString.replaceAll("\\s+", " ")

    val pathKey = "COALESCE(location3.name_lc || ?, '') || location2.name_lc"
    slice(PostgresSearchDialect).contains(
      s"ORDER BY $pathKey ASC, location2.id ASC, asset0.filename DESC, asset0.id ASC LIMIT 51") shouldBe true
    // The trailing group is ordered by the sort alone
    slice(PostgresSearchDialect).contains("ORDER BY asset0.filename DESC, asset0.id ASC LIMIT CASE") shouldBe true
    // SQLite gets its planner hint on every sort term of a Location grouping: there is no grouping index to protect
    slice(SqliteSearchDialect).contains(
      s"ORDER BY $pathKey ASC, location2.id ASC, +asset0.filename DESC, asset0.id ASC LIMIT 51") shouldBe true
  }

  test("The Location statement selects the asset columns its row class reads, then the group columns") {
    val query = new SearchQuery(
      rpp = 50,
      searchSort = List(SearchSort("filename", SortDirection.ASC)),
      grouping = Some(SearchGrouping(GroupBy.Location)))
    val text = SearchQueries.groupedByLocation(PostgresSearchDialect, query, "repo-1").toString.replaceAll("\\s+", " ")

    val declared = Table.labels(AssetRow).map(Db.config.columnNameMapper).map(name => s"asset.$name").mkString(", ")
    text.contains(
      s"SELECT $declared, p.location_id AS location_id, p.path_key AS path_key, p.location_name AS location_name, " +
        "p.category_name AS category_name, p.sort_value AS sort_value, p.second_sort_value AS second_sort_value, " +
        "g.n AS group_total") shouldBe true
  }

  test("Relevance is rendered only under its sort: one CASE per scoring group, the best source first") {
    val ids = Map(SearchSource.Folder -> Set("f1"), SearchSource.Person -> Set("p1"))
    val text = "alice OR bob paris -lake"

    for ((engine, dialect) <- engines) {
      val sql = flat(dialect, textQuery(text, ids, searchSort = List(SearchSort.Relevance)))

      withClue(s"$engine: $sql") {
        // The alternatives share one CASE, so their group scores once, as its best match; the exclusion scores nothing
        "CASE WHEN".r.findAllIn(sql).size shouldBe 2
        "THEN (\\d)".r.findAllMatchIn(sql).map(_.group(1)).mkString shouldBe "552211521"
        ("ORDER BY \\(CASE WHEN .* ELSE 0 END \\+ CASE WHEN .* ELSE 0 END\\) DESC, " +
          "asset0\\.original_created_at DESC NULLS LAST, asset0\\.id ASC").r.findFirstIn(sql).isDefined shouldBe true
      }

      val byFilename = List(SearchSort("filename", SortDirection.ASC))
      flat(dialect, textQuery(text, ids, searchSort = byFilename)).contains("CASE WHEN") shouldBe false
    }
  }

  test("A Relevance page orders by Relevance, newest capture and ID, and its cursor compares all three") {
    val relevance = "CASE WHEN .*? THEN 1 ELSE 0 END"
    val taken = "asset0\\.original_created_at"

    for {
      (engine, dialect) <- engines
      by <- GroupBy.values.toList
      second <- List(SortValue.Text("2026-09-06 10:00:00"), SortValue.Null)
    } {
      // A day's anchor, or one in the trailing "No location" group
      val cursor = SearchCursor(
        key = Option.when(by == GroupBy.DateTaken)("2026-09-06"),
        groupId = None,
        sortValue = SortValue.Num(1),
        id = "id",
        scope = "scope",
        secondSortValue = Some(second))
      def page(cursor: SearchCursor): SqlStr =
        val query = new SearchQuery(
          rpp = 50,
          text = Some("beach"),
          resolvedText = resolved("beach"),
          searchSort = List(SearchSort.Relevance),
          grouping = Some(SearchGrouping(by)),
          cursor = Some(cursor)
        )
        if by == GroupBy.DateTaken then SearchQueries.grouped(dialect, query, "repo-1")
        else SearchQueries.groupedByLocation(dialect, query, "repo-1")
      val statement = page(cursor)
      val text = statement.toString.replaceAll("\\s+", " ").trim

      withClue(s"$engine/$by/$second: $text") {
        // Among the anchor's Relevance an older capture follows it, and so does every asset with none: they are last
        val afterTie =
          if (second == SortValue.Null) s"\\($taken IS NULL AND asset0\\.id > \\?\\)"
          else s"\\($taken < \\? OR $taken IS NULL OR \\($taken = \\? AND asset0\\.id > \\?\\)\\)"
        s"\\($relevance < \\? OR \\($relevance = \\? AND $afterTie\\)\\)".r.findFirstIn(text).isDefined shouldBe true
        // The slice computes what it orders by; the page orders its candidates by the columns they carry
        s"$relevance DESC, $taken DESC NULLS LAST, asset0\\.id ASC LIMIT".r.findFirstIn(text).isDefined shouldBe true
        "(c\\.)?sort_value DESC, (c\\.)?second_sort_value DESC NULLS LAST, (c\\.)?id ASC LIMIT 50".r
          .findFirstIn(text)
          .isDefined shouldBe true
        text.contains("p.sort_value DESC, p.second_sort_value DESC NULLS LAST, p.id ASC") shouldBe true
        text.count(_ == '?') shouldBe binds(statement)
      }

      // A cursor without the capture time is no position under this sort
      intercept[SearchCursorException](page(cursor.copy(secondSortValue = None)))
    }
  }

  test("The map's cells are one statement: the plotted points in the box, gridded, one window pass, the newest per cell") {
    for ((engine, dialect) <- engines) withClue(engine) {
      val query = new SearchQuery(params = Map("is_recycled" -> false), locationIds = Set("l1"))
      val statement = SearchQueries.mapCells(dialect, query, "repo-1", BoundingBox(48.0, 2.0, 49.0, 3.0), 0.25)
      val text = statement.toString.replaceAll("\\s+", " ")

      withClue(text) {
        // An asset's own point in the box, and - without one - the pin of each Location it is in that is in the box
        text.contains("WITH points (asset_id, latitude, longitude, taken) AS (") shouldBe true
        text.contains("UNION ALL") shouldBe true
        text.contains(
          "(asset0.latitude IS NOT NULL) AND (asset0.latitude BETWEEN ? AND ? AND asset0.longitude BETWEEN ? AND ?)") shouldBe true
        text.contains("JOIN location_asset location_asset1 ON") shouldBe true
        text.contains("JOIN location location2 ON") shouldBe true
        text.contains("(location2.latitude BETWEEN ? AND ? AND location2.longitude BETWEEN ? AND ?)") shouldBe true
        // Both branches carry the search's own filters
        raw"location_asset\S*\.location_id (= ANY\(\?\)|IN \(SELECT value FROM json_each\(\?\)\))".r
          .findAllIn(text)
          .size shouldBe 2
        text.contains("floor(longitude / ?) AS cell_x, floor(latitude / ?) AS cell_y") shouldBe true
        text.contains("COUNT(*) OVER w AS n, AVG(latitude) OVER w AS latitude, AVG(longitude) OVER w AS longitude") shouldBe true
        text.contains(
          "ROW_NUMBER() OVER (PARTITION BY cell_x, cell_y ORDER BY CASE WHEN taken IS NULL THEN 1 ELSE 0 END, taken DESC, asset_id ASC) AS rn") shouldBe true
        text.contains("WINDOW w AS (PARTITION BY cell_x, cell_y)") shouldBe true
        text.contains("WHERE rn = 1") shouldBe true
        text.contains("NULLS FIRST") shouldBe false
        text.contains("NULLS LAST") shouldBe false
        text.count(_ == '?') shouldBe binds(statement)
      }
    }
  }

  test("The map's bounds are one aggregate over the same plotted points, over the whole search") {
    for ((engine, dialect) <- engines) withClue(engine) {
      val query = textQuery("beach", params = Map("is_recycled" -> false))
      val statement = SearchQueries.mapBounds(dialect, query, "repo-1")
      val text = statement.toString.replaceAll("\\s+", " ")

      withClue(text) {
        text.contains("WITH points (asset_id, latitude, longitude, taken) AS (") shouldBe true
        text.contains("UNION ALL") shouldBe true
        text.contains("SELECT min(latitude), max(latitude), min(longitude), max(longitude), count(*) FROM points") shouldBe true
        text.contains("BETWEEN") shouldBe false
        // The text filter is applied to both point sources
        "FROM search_document ".r.findAllIn(text).size shouldBe 2
        text.count(_ == '?') shouldBe binds(statement)
      }
    }
  }

  test("The map's Locations are the pinned ones in the box, counted over the matching assets, with their category's name") {
    for ((engine, dialect) <- engines) withClue(engine) {
      val query = new SearchQuery(params = Map("is_recycled" -> false), folderIds = Set("f1"))
      val select = SearchQueries.mapLocations(dialect, query, "repo-1", BoundingBox(-1.0, 179.0, 1.0, -179.0))
      val sql = DbApi.renderSql(select, Db.config, dialect.dialect)

      withClue(sql) {
        sql.contains("FROM location location0") shouldBe true
        sql.contains("LEFT JOIN location location1 ON location0.category_id = location1.id") shouldBe true
        sql.contains("location0.repository_id = ?") shouldBe true
        sql.contains("location0.kind = ?") shouldBe true
        // A box across the antimeridian covers both sides of it
        sql.contains("(location0.longitude >= ? OR location0.longitude <= ?)") shouldBe true
        // The count is a correlated subquery over the search's own matching relation, and only Locations with one are listed
        sql.contains("FROM location_asset") shouldBe true
        sql.contains("asset_id IN (SELECT asset") shouldBe true
        (sql.contains("folder_id = ANY(?)") || sql.contains("folder_id IN (SELECT value FROM json_each(?))")) shouldBe true
        sql.contains("> ?") shouldBe true
      }
    }
  }

  private def flat(engine: SearchDialect, query: SearchQuery): String =
    DbApi.renderSql(SearchQueries.flat(engine, query, "repo-1"), Db.config, engine.dialect)

  /** The engine query strings a Search text binds, in term order: every text value bound other than the repository */
  private def textBinds(engine: SearchDialect, text: String): List[String] =
    bound(engine, textQuery(text)).collect { case value: String if value != "repo-1" => value }

  /** Every value the search's matching relation binds, in order */
  private def bound(engine: SearchDialect, query: SearchQuery): List[Any] =
    def values[Q, R](query: Q)(using queryable: Queryable[Q, R]): List[Any] =
      DbApi.unpackQueryable(query, queryable, Db.config, engine.dialect).interpsIterator.map(_.value).toList

    values(SearchQueries.matching(engine, query, "repo-1"))

  /** A query whose Search text is resolved as a service would hand it to the DAO, every term having matched the given names */
  private def textQuery(
      text: String,
      ids: Map[SearchSource, Set[String]] = Map(),
      params: Map[String, Any] = Map(),
      locationIds: Set[String] = Set(),
      searchSort: List[SearchSort] = Nil): SearchQuery =
    new SearchQuery(
      text = Some(text),
      params = params,
      locationIds = locationIds,
      searchSort = searchSort,
      resolvedText = resolved(text, ids))

  /** The Search text with every one of its terms resolved to the given names */
  private def resolved(text: String, ids: Map[SearchSource, Set[String]] = Map()): Option[ResolvedSearchText] =
    SearchText.parse(text).map {
      expression =>
        ResolvedSearchText(
          expression.groups.map(group => ResolvedSearchGroup(group.alternatives.map(ResolvedSearchTerm(_, ids)))))
    }

  private def placeholders(sql: String): Int = sql.count(_ == '?')

  /** How many values the statement actually binds, which has to be exactly how many placeholders it renders */
  private def binds(statement: SqlStr): Int = SqlStr.flatten(statement).interpsIterator.size
}
