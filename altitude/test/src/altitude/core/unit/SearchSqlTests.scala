package altitude.core.unit

import altitude.test.TestFocus
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers.*
import scalasql.Table
import scalasql.core.DbApi
import scalasql.core.Queryable
import scalasql.core.SqlStr

import altitude.core.Const
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

    /**
     * Setup:
     *
     * An empty query's matching relation, rendered on both engines.
     *
     * Assertions:
     *
     * The relation reads the asset table limited to the repository and to assets the pipeline has processed, binding those two
     * values and nothing else.
     */
    for ((engine, dialect) <- engines) withClue(engine) {
      val sql = matchingSql(dialect, new SearchQuery())

      sql.contains("FROM asset asset0") shouldBe true
      sql.contains("asset0.repository_id = ?") shouldBe true
      sql.contains("asset0.is_pipeline_processed = ?") shouldBe true
      placeholders(sql) shouldBe 2
    }
  }

  test("A flat page picks its rows over narrow ones, one past the page, then reads the page in full") {

    /**
     * Setup:
     *
     * A query of 25 rows a page sorted by file name descending, rendered as its first and third flat page on both engines.
     *
     * Assertions:
     *
     * The page is a materialized slice of IDs and sort values, ordered by its own columns and limited to one row past the page at
     * the page's offset; only those rows are joined back to the asset table in full, in the same order, with no window count and
     * no explicit null ordering. Only the first page carries the overall count, capped one past 10,000, and every placeholder has
     * a bound value.
     *
     * Edge cases:
     *
     * A query without a page size is refused, so no flat page is unbounded.
     */
    for ((engine, dialect) <- engines) withClue(engine) {
      def page(number: Int): SqlStr = SearchQueries.flat(
        dialect,
        new SearchQuery(rpp = 25, page = number, searchSort = List(SearchSort("filename", SortDirection.DESC))),
        "repo-1")
      val third = page(3)
      val text = third.toString.replaceAll("\\s+", " ").trim

      withClue(text) {
        text.contains("WITH page (id, sort_value, second_sort_value) AS MATERIALIZED (SELECT asset0.id AS ") shouldBe true
        // Narrow rows, ordered by the slice's own columns, one past the page at the page's offset
        "ORDER BY \\w+ DESC, \\w+ ASC LIMIT \\? OFFSET \\?\\)".r.findFirstIn(text).isDefined shouldBe true
        bound(third).takeRight(2) shouldBe List(26, 50)
        text.contains("COUNT(1) OVER") shouldBe false
        // Only the page's own rows are read in full, in the same order
        text.contains("FROM page AS p JOIN asset ON asset.id = p.id") shouldBe true
        text.contains("(SELECT count(*) FROM page) AS page_count") shouldBe true
        text.contains("ORDER BY p.sort_value DESC, p.id ASC LIMIT 25") shouldBe true
        text.contains("NULLS FIRST") shouldBe false
        text.contains("NULLS LAST") shouldBe false
        // Only a first page counts the matches, up to one past the cap
        text.contains("total AS MATERIALIZED") shouldBe false
        page(1).toString.replaceAll("\\s+", " ") should include("LIMIT 10001) AS m)")
        text.count(_ == '?') shouldBe binds(third)
      }

      // Every flat page is bounded
      intercept[IllegalArgumentException](SearchQueries.flat(dialect, new SearchQuery(), "repo-1"))
    }
  }

  test("A term's document source is a CTE at the head of the statement, matched in the engine's own dialect") {

    /**
     * Setup:
     *
     * The text "beach", resolved to no names, rendered as an exact count on each engine.
     *
     * Assertions:
     *
     * The statement opens with a materialized CTE over the search documents, which the asset tests membership in, and the
     * document source carries no repository filter of its own: the asset is scoped to it. PostgreSQL matches the document's
     * tsvector with `to_tsquery`, SQLite matches through its full-text table, and only the text, the repository and the pipeline
     * flag are bound.
     */
    val postgres = countSql(PostgresSearchDialect, textQuery("beach"))
    val sqlite = countSql(SqliteSearchDialect, textQuery("beach"))

    for (sql <- List(postgres, sqlite)) withClue(sql) {
      "^WITH text_0_0_document \\(asset_id\\) AS MATERIALIZED \\(SELECT search_document\\d\\.asset_id AS \\w+ FROM search_document search_document\\d WHERE ".r
        .findFirstIn(sql)
        .isDefined shouldBe true
      sql.contains("(asset0.id IN (SELECT asset_id FROM text_0_0_document))") shouldBe true
      // The document source carries no repository of its own: the asset is scoped to it
      sql.contains("search_document0.repository_id") shouldBe false
      // the text, then the repository and the pipeline flag
      placeholders(sql) shouldBe 3
    }
    postgres.contains("WHERE tsv @@ to_tsquery('simple', ?))") shouldBe true
    sqlite.contains("WHERE id IN (SELECT rowid FROM search_document_fts WHERE search_document_fts MATCH ?))") shouldBe true
  }

  test("Each term tests membership in its sources: groups AND-ed, alternatives OR-ed, an excluded alternative negated") {

    /**
     * Setup:
     *
     * The text `beach OR -lake "golden gate"` - a group of two alternatives, one of them excluded, and a phrase - rendered as an
     * exact count on both engines.
     *
     * Assertions:
     *
     * Alternatives are OR-ed with the excluded one negated, the groups are AND-ed, each term's document source is built once at
     * the head of the statement, and each term binds one query string.
     */
    for ((engine, dialect) <- engines) {
      val sql = countSql(dialect, textQuery("""beach OR -lake "golden gate""""))

      withClue(s"$engine: $sql") {
        sql.contains(
          "(asset0.id IN (SELECT asset_id FROM text_0_0_document)) OR " +
            "(NOT (asset0.id IN (SELECT asset_id FROM text_0_1_document)))") shouldBe true
        sql.contains("AND (asset0.id IN (SELECT asset_id FROM text_1_0_document))") shouldBe true
        // Each source is built once, at the head of the statement
        "FROM search_document ".r.findAllIn(sql).size shouldBe 3
        // the engine's query string per term, then the repository and the pipeline flag
        placeholders(sql) shouldBe 5
      }
    }
  }

  test("A term is bound as one query string in the engine's syntax: a prefix on the last word unless it is a phrase") {

    /**
     * Setup:
     *
     * The text `beach IMG_12 "golden gate" -"lake"`: a word, a file-name-like word, a phrase and an excluded phrase.
     *
     * Assertions:
     *
     * Each term binds one string in the engine's own full-text syntax - adjacent `<->` words on PostgreSQL, a quoted phrase on
     * SQLite - with a prefix match on the last word of a word term and none on a phrase.
     *
     * Edge cases:
     *
     * `IMG_12` splits into two adjacent words, and the excluded term is bound like any other: its negation lives in the SQL.
     */
    val text = """beach IMG_12 "golden gate" -"lake""""

    textBinds(PostgresSearchDialect, text) shouldBe List("beach:*", "img <-> 12:*", "golden <-> gate", "lake")
    textBinds(SqliteSearchDialect, text) shouldBe List("\"beach\" *", "\"img 12\" *", "\"golden gate\"", "\"lake\"")
  }

  test("A term with a camelCase hump is bound as one query string, the OR of its readings") {

    /**
     * Setup:
     *
     * The text `McDonald "LaGuardia airport"`: a camelCase word and a phrase that starts with one.
     *
     * Assertions:
     *
     * Each term binds one string on both engines that ORs the split reading with the joined one, the word keeping its prefix
     * match and the phrase taking none.
     */
    val text = """McDonald "LaGuardia airport""""

    textBinds(PostgresSearchDialect, text) shouldBe
      List("mc <-> donald:* | mcdonald:*", "la <-> guardia <-> airport | laguardia <-> airport")
    textBinds(SqliteSearchDialect, text) shouldBe
      List("\"mc donald\" * OR \"mcdonald\" *", "\"la guardia airport\" OR \"laguardia airport\"")
  }

  test("Text without a usable term adds no filter") {

    /**
     * Setup:
     *
     * Text made only of an operator, punctuation and a dangling `OR`.
     *
     * Assertions:
     *
     * The matching relation reads no search document on either engine.
     */
    for ((engine, dialect) <- engines) withClue(engine) {
      matchingSql(dialect, new SearchQuery(text = Some("- !!! OR"))).contains("search_document") shouldBe false
    }
  }

  test("A term is the OR of the name sources it resolved to and the document, each ID set bound as one parameter") {

    /**
     * Setup:
     *
     * The text "alice" resolved to an album, two people, three folders, a category and a Location, rendered as an exact count on
     * both engines.
     *
     * Assertions:
     *
     * The term ORs one membership test per source in declaration order - person, Location, category, folder, album, document -
     * each a CTE except the folder, which is the asset's own column. The person CTE reads faces by person ID, and each ID set is
     * bound as one parameter in the engine's set form.
     */
    val ids = Map(
      SearchSource.Album -> Set("a1"),
      SearchSource.Person -> Set("p1", "p2"),
      SearchSource.Folder -> Set("f1", "f2", "f3"),
      SearchSource.Category -> Set("l2"),
      SearchSource.Location -> Set("l1")
    )

    for ((engine, dialect) <- engines) {
      val sql = countSql(dialect, textQuery("alice", ids))
      val inSet = if (dialect == PostgresSearchDialect) raw"= ANY\(\?\)" else raw"IN \(SELECT value FROM json_each\(\?\)\)"
      def in(source: String) = raw"\(asset0\.id IN \(SELECT asset_id FROM text_0_0_$source\)\)"

      withClue(s"$engine: $sql") {
        // Sources in declaration order: person, Location, Category (as its Locations), folder, album, document. The folder is
        // the asset's own column; every other source is a CTE.
        (raw"${in("person")}\)* OR ${in("location")}\)* OR ${in("category")}\)* OR \(?asset0\.folder_id $inSet\)* OR " +
          raw"${in("album")}\)* OR ${in("document")}").r.findFirstIn(sql).isDefined shouldBe true
        raw"text_0_0_person \(asset_id\) AS MATERIALIZED \(SELECT face\d\.asset_id AS \w+ FROM face face\d WHERE \(?face\d\.person_id $inSet".r
          .findFirstIn(sql)
          .isDefined shouldBe true
        sql.contains("text_0_0_folder") shouldBe false
        // one parameter per ID set, the document's query string, then the repository and the pipeline flag
        placeholders(sql) shouldBe 8
      }
    }
  }

  test("A group of one excluded term is an anti-join on each of its sources") {

    /**
     * Setup:
     *
     * The text `-alice` resolved to one person, rendered as an exact count on each engine.
     *
     * Assertions:
     *
     * Each of the term's sources, the person and the document, is AND-ed as an anti-join: `NOT EXISTS` on PostgreSQL and `NOT IN`
     * on SQLite.
     */
    val postgres = countSql(PostgresSearchDialect, textQuery("-alice", Map(SearchSource.Person -> Set("p1"))))
    val sqlite = countSql(SqliteSearchDialect, textQuery("-alice", Map(SearchSource.Person -> Set("p1"))))

    withClue(postgres) {
      postgres.contains(
        "(NOT EXISTS (SELECT 1 FROM text_0_0_person WHERE text_0_0_person.asset_id = asset0.id)) AND " +
          "(NOT EXISTS (SELECT 1 FROM text_0_0_document WHERE text_0_0_document.asset_id = asset0.id))") shouldBe true
    }
    withClue(sqlite) {
      sqlite.contains(
        "(asset0.id NOT IN (SELECT asset_id FROM text_0_0_person)) AND " +
          "(asset0.id NOT IN (SELECT asset_id FROM text_0_0_document))") shouldBe true
    }
  }

  test("The probe is one statement: a branch per positive group, one row past the limit, every source scoped to its repository") {

    /**
     * Setup:
     *
     * The text `alice OR bob beach -lake` with every term resolved to one person, probed with a limit of 10 on both engines.
     *
     * Assertions:
     *
     * The probe has one branch per positive group, each read to one row past the limit, every person and document source of
     * alice, bob and beach is scoped to the repository, and every placeholder has a bound value.
     *
     * Edge cases:
     *
     * Text of an exclusion alone has no probe at all.
     */
    for ((engine, dialect) <- engines) {
      // Every term resolved to one person
      val text = resolved("alice OR bob beach -lake", Map(SearchSource.Person -> Set("p1"))).get
      val statement = SearchQueries.textProbe(dialect, text, "repo-1", 10).get
      val sql = statement.toString.replaceAll("\\s+", " ").trim

      withClue(s"$engine: $sql") {
        // The first two groups are positive; the excluded term has no hits to read
        "SELECT \\d+ AS grp, hits\\.\\* FROM \\(".r.findAllIn(sql).toList shouldBe
          List("SELECT 0 AS grp, hits.* FROM (", "SELECT 1 AS grp, hits.* FROM (")
        "LIMIT 11\\) AS hits".r.findAllIn(sql).size shouldBe 2
        // alice, bob and beach, each through its person and its document, every one scoped to the repository
        "face\\d+\\.repository_id = \\?".r.findAllIn(sql).size shouldBe 3
        "search_document\\d+\\.repository_id = \\?".r.findAllIn(sql).size shouldBe 3
        sql.count(_ == '?') shouldBe binds(statement)
      }

      SearchQueries.textProbe(dialect, resolved("-lake").get, "repo-1", 10) shouldBe None
    }
  }

  test("On the selective path the text is the candidate set, and what remains of it is tested on the asset's own row") {

    /**
     * Setup:
     *
     * The text `alice beach -lake` resolved to one person, with a probe outcome stitched in by hand: alice's group complete,
     * beach too broad, and two candidate assets. The query is sorted by Relevance, 50 rows a page, on both engines.
     *
     * Assertions:
     *
     * The count builds no text CTE: it filters by the candidate set and tests beach and lake as correlated `EXISTS` probes on the
     * asset's own row through each source, the excluded term negated. The flat page scores the Relevance of alice and of beach,
     * each as a `CASE` over its sources' probes.
     */
    for ((engine, dialect) <- engines) {
      val text = resolved("alice beach -lake", Map(SearchSource.Person -> Set("p1"))).get
      // The probe found alice complete and beach too broad; an exclusion is never complete
      val selective = text.copy(
        groups = text.groups.zipWithIndex.map((group, index) => group.copy(isComplete = index == 0)),
        candidates = Some(Set("a1", "a2")))
      val query = new SearchQuery(
        text = Some("alice beach -lake"),
        resolvedText = Some(selective),
        rpp = 50,
        searchSort = List(SearchSort.Relevance))
      val count = countSql(dialect, query)
      val page = flatSql(dialect, query)
      val candidates =
        if (dialect == PostgresSearchDialect) "asset0.id = ANY(?)" else "asset0.id IN (SELECT value FROM json_each(?))"
      val probe =
        "EXISTS \\(SELECT (face|search_document)\\d+\\.asset_id AS \\w+ FROM (face|search_document) \\w+ WHERE \\(*\\w+\\.asset_id = asset0\\.id"

      withClue(s"$engine: $count") {
        count.contains("text_") shouldBe false
        count.contains(candidates) shouldBe true
        // beach and lake, each through its person and its document; the candidates stand for alice, and lake is in none of its
        // sources
        probe.r.findAllIn(count).size shouldBe 4
        "\\(NOT \\(EXISTS \\(SELECT".r.findAllIn(count).size shouldBe 2
      }
      withClue(s"$engine: $page") {
        // The Relevance of alice and of beach, each a CASE over its sources' probes
        "CASE WHEN \\(EXISTS \\(SELECT face".r.findAllIn(page).size shouldBe 2
      }
    }
  }

  test("An ID set is bound whole: an array on PostgreSQL, a JSON array on SQLite") {

    /**
     * Setup:
     *
     * A folder filter of two folder IDs.
     *
     * Assertions:
     *
     * The set is bound as one value on each engine: the set itself on PostgreSQL and its JSON array text on SQLite.
     */
    val query = new SearchQuery(folderIds = Set("f1", "f2"))

    bound(PostgresSearchDialect, query) should contain(Set("f1", "f2"))
    bound(SqliteSearchDialect, query) should contain("""["f1","f2"]""")
  }

  test("Text that was not resolved against the names is refused, not searched by the document alone") {

    /**
     * Setup:
     *
     * A query with the text "beach" but no resolved text.
     *
     * Assertions:
     *
     * Rendering it fails with an `IllegalStateException` on both engines.
     */
    for ((engine, dialect) <- engines) withClue(engine) {
      intercept[IllegalStateException](matchingSql(dialect, new SearchQuery(text = Some("beach"))))
    }
  }

  test("Metadata filters are one semi-join that counts the values an asset carries") {

    /**
     * Setup:
     *
     * Three metadata filters - a keyword, a number and a boolean - rendered on PostgreSQL.
     *
     * Assertions:
     *
     * The filters are one subquery on the asset ID over the metadata parameters that matches each field and value, groups by
     * asset and keeps the assets carrying as many matches as there are filters. The outer query neither joins nor groups, and
     * every value is bound.
     */
    val query =
      new SearchQuery(metadataFilters = Map("kw_field" -> "beach", "num_field" -> 12, "bool_field" -> Query.EQUALS(false)))
    val sql = matchingSql(PostgresSearchDialect, query)

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

    /**
     * Setup:
     *
     * A filter of two people rendered on SQLite, and a filter of one album rendered on PostgreSQL.
     *
     * Assertions:
     *
     * Each filter is a subquery on the asset ID with its ID set bound as one parameter: the person filter reads the face's own
     * person ID without joining people, and the album filter reads the album memberships.
     */
    val people = matchingSql(SqliteSearchDialect, new SearchQuery(personIds = Set("p1", "p2")))
    val albums = matchingSql(PostgresSearchDialect, new SearchQuery(albumIds = Set("a1")))

    // The face's own person ID is all the filter reads
    withClue(people) {
      people.contains("asset0.id IN (SELECT") shouldBe true
      people.contains("FROM face face1") shouldBe true
      people.contains("JOIN person") shouldBe false
      people.contains("face1.person_id IN (SELECT value FROM json_each(?))") shouldBe true
    }

    withClue(albums) {
      albums.contains("FROM album_asset album_asset1") shouldBe true
      albums.contains("album_asset1.album_id = ANY(?)") shouldBe true
    }
  }

  test("Location and bounding-box filters are semi-joins that bind their values") {

    /**
     * Setup:
     *
     * A filter of two Locations rendered on SQLite, a bounding box around Paris rendered on PostgreSQL, and a box across the
     * antimeridian rendered on SQLite.
     *
     * Assertions:
     *
     * The Location filter is a semi-join over the memberships with its ID set bound as one parameter. The box matches an asset's
     * own point or, when it has none, the pin of a Location it is in, every edge bound.
     *
     * Edge cases:
     *
     * A box across the antimeridian turns the longitude range into two open-ended halves.
     */
    val byLocation = matchingSql(SqliteSearchDialect, new SearchQuery(locationIds = Set("l1", "l2")))
    withClue(byLocation) {
      byLocation.contains("asset0.id IN (SELECT") shouldBe true
      byLocation.contains("FROM location_asset location_asset1") shouldBe true
      byLocation.contains("location_asset1.location_id IN (SELECT value FROM json_each(?))") shouldBe true
      // repository, pipeline flag and the ID set
      placeholders(byLocation) shouldBe 3
    }

    // An asset's own point, or - with no point of its own - the pin of a Location it is in
    val box = matchingSql(PostgresSearchDialect, new SearchQuery(bbox = Some(BoundingBox(48.0, 2.0, 49.0, 3.0))))
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
    val across = matchingSql(SqliteSearchDialect, new SearchQuery(bbox = Some(BoundingBox(-1.0, 179.0, 1.0, -179.0))))
    withClue(across) {
      across.contains("(asset0.longitude >= ? OR asset0.longitude <= ?)") shouldBe true
      across.contains("BETWEEN ? AND ? AND (") shouldBe true
    }
  }

  test("A count is one COUNT over the matching relation; a capped one stops one past its cap") {

    /**
     * Setup:
     *
     * The text "beach" with a recycled flag, a Location filter and a cap of 2, rendered as an exact and a capped count on both
     * engines.
     *
     * Assertions:
     *
     * Both counts are one `count(*)` over the matching relation after the text's CTE, carrying every filter and no ordering; the
     * exact count is unlimited, and the capped one stops at one past its cap.
     */
    for ((engine, dialect) <- engines) withClue(engine) {
      val query = textQuery("beach", params = Map("is_recycled" -> false), locationIds = Set("l1"), totalCap = 2)
      val exact = SearchQueries.count(dialect, query, "repo-1").toString.replaceAll("\\s+", " ").trim
      val capped = SearchQueries.cappedCount(dialect, query, "repo-1").toString.replaceAll("\\s+", " ").trim

      for (sql <- List(exact, capped)) withClue(sql) {
        // after the text's CTE
        sql.contains(") SELECT count(*) FROM (SELECT asset0.id AS ") shouldBe true
        sql.contains("FROM asset asset0") shouldBe true
        sql.contains("asset0.is_recycled = ?") shouldBe true
        sql.contains("location_asset") shouldBe true
        sql.contains("ORDER BY") shouldBe false
      }
      exact.contains("LIMIT") shouldBe false
      capped.endsWith("LIMIT 3) AS m") shouldBe true
    }
  }

  test("Folder and column filters bind their values") {

    /**
     * Setup:
     *
     * A recycled-flag filter and a filter of two folders, rendered on SQLite.
     *
     * Assertions:
     *
     * Both are bound predicates on the asset's own columns, the folder set bound as one JSON parameter.
     */
    val query = new SearchQuery(params = Map("is_recycled" -> false), folderIds = Set("f1", "f2"))
    val sql = matchingSql(SqliteSearchDialect, query)

    withClue(sql) {
      sql.contains("asset0.is_recycled = ?") shouldBe true
      sql.contains("asset0.folder_id IN (SELECT value FROM json_each(?))") shouldBe true
      // repository, pipeline flag, the recycled flag and the folder set
      placeholders(sql) shouldBe 4
    }
  }

  test("A grouped page keeps its guarded null slices and its separately indexable day counts") {

    /**
     * Setup:
     *
     * A query of 50 rows a page grouped by Date Taken, with a recycled flag, a folder and a metadata filter, rendered over every
     * combination that changes its shape: both engines, both group directions, a first page and a continuation from either a
     * dated or an undated anchor, sorted by the grouping column itself or by another.
     *
     * Assertions:
     *
     * The undated slice and its guard appear only when a continuation from a dated anchor can run into undated assets that sort
     * after it. The day counts are separate dated and undated queries joined to the page null-safely, no null ordering is spelled
     * out, only a first page counts the total (capped one past 10,000), and every placeholder has a bound value.
     *
     * Edge cases:
     *
     * An undated anchor sorted by capture time continues within the undated group or past it, depending on where the engine puts
     * nulls for the direction.
     */
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
        // Only a first page pays for the overall count, which stops one past the cap
        text.contains("total AS MATERIALIZED") shouldBe (position == "first")
        text.contains("LIMIT 10001) AS m)") shouldBe (position == "first")
        text.count(_ == '?') shouldBe binds(statement)

        if (position == "undated" && field == "original_created_at") {
          val after = if (leads) s"($day IS NOT NULL OR asset0.id > ?)" else s"$day IS NULL AND asset0.id > ?"
          text.contains(after) shouldBe true
        }
      }
    }
  }

  test("A grouped slice reads in day-index order, with SQLite's planner hint on a non-grouping sort") {

    /**
     * Setup:
     *
     * A query of 50 rows a page grouped by Date Taken descending, sorted by file name or by capture time.
     *
     * Assertions:
     *
     * The slice orders by the engine's own day index expression, then the sort, then the ID. On SQLite a file name sort term
     * carries the unary `+` that keeps the planner off its index, while a capture time sort does not.
     */
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

    /**
     * Setup:
     *
     * A PostgreSQL continuation of a page grouped by Date Taken descending and sorted by file name, from an anchor on 2026-09-06.
     *
     * Assertions:
     *
     * Besides the comparison that continues after the anchor, the day carries an inclusive upper bound the day index can seek to.
     */
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

    /**
     * Setup:
     *
     * A first SQLite page for "beach" with a recycled flag, grouped by Date Taken and sorted by file name.
     *
     * Assertions:
     *
     * The text is matched once, and the candidate slice, both day-count probes and the overall count all apply the same
     * predicates through the one CTE of the term's document source.
     */
    val query = new SearchQuery(
      params = Map("is_recycled" -> false),
      rpp = 50,
      text = Some("beach"),
      resolvedText = resolved("beach"),
      searchSort = List(SearchSort("filename", SortDirection.ASC)),
      grouping = Some(SearchGrouping(GroupBy.DateTaken, SortDirection.DESC))
    )
    val text = SearchQueries.grouped(SqliteSearchDialect, query, "repo-1").toString.replaceAll("\\s+", " ")

    // The candidate slice, both day-count probes and the overall count: four copies of the same predicate, all reading the one
    // CTE of the term's document source
    withClue(text) {
      "search_document_fts MATCH \\?".r.findAllIn(text).size shouldBe 1
      "IN \\(SELECT asset_id FROM text_0_0_document\\)".r.findAllIn(text).size shouldBe 4
      "asset0\\.is_recycled = \\?".r.findAllIn(text).size shouldBe 4
    }
  }

  test("The grouped statement selects the asset columns its row class reads, in that order") {

    /**
     * Setup:
     *
     * A PostgreSQL page grouped by Date Taken and sorted by file name.
     *
     * Assertions:
     *
     * The select list is the asset row's columns in declaration order, followed by the day, the sort values and the day total.
     */
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

  test("A Location page slices the located and the unlocated relation, guards the second, and binds every value") {

    /**
     * Setup:
     *
     * A query of 50 rows a page for "beach" grouped by Location, with a recycled flag and a folder, rendered over what changes
     * its shape: both engines, a first page and a continuation from an anchor in a Location (Rome in Italy) or in the trailing
     * "No location" group, sorted by a nullable timestamp or by a text column.
     *
     * Assertions:
     *
     * Until the anchor is in the trailing group, the page has a located slice joining the memberships, the Location and its
     * category, and a guarded unlocated slice of the matches in no Location. Located rows come first in path order whatever the
     * engine does with nulls, the group counts are joined to the page null-safely, only a first page counts the total, every
     * branch reads the one CTE of the term's document source, and every placeholder has a bound value.
     *
     * Edge cases:
     *
     * A continuation from a located anchor compares its path key, then its Location ID, then the sort.
     */
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
        // The located relation is the asset joined to its memberships, the Location and its category; the group counts join
        // the memberships alone
        text.contains("JOIN location_asset location_asset1 ON") shouldBe true
        text.contains("JOIN location location2 ON") shouldBe (position != "unlocated")
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
        // Every branch applies the same filters: the located slice, the unlocated slice, both group counts and the total, all
        // reading the one CTE of the term's document source
        "search_document_fts MATCH \\?|tsv @@ to_tsquery\\('simple', \\?\\)".r.findAllIn(text).size shouldBe 1
        "IN \\(SELECT asset_id FROM text_0_0_document\\)".r.findAllIn(text).size shouldBe
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

    /**
     * Setup:
     *
     * A query of 50 rows a page grouped by Location and sorted by file name descending, on both engines.
     *
     * Assertions:
     *
     * The located slice orders by the path key, the Location ID, the sort and the ID, and the trailing group by the sort alone.
     * SQLite puts its planner hint on every sort term, since there is no grouping index to protect.
     */
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

    /**
     * Setup:
     *
     * A PostgreSQL page grouped by Location and sorted by file name.
     *
     * Assertions:
     *
     * The select list is the asset row's columns in declaration order, followed by the Location, its path, name and category, the
     * sort values and the group total.
     */
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

  test("Relevance is rendered only under its sort, once on a flat page: one CASE per scoring group, the best source first") {

    /**
     * Setup:
     *
     * The text `alice OR bob paris -lake` resolved to a folder and a person, rendered as a flat page of 50 under the Relevance
     * sort and again under a file name sort, on both engines.
     *
     * Assertions:
     *
     * Under the Relevance sort each scoring group is one `CASE` - the alternatives share it and the exclusion has none - testing
     * its best-scoring source first. The slice orders by the Relevance and capture time it selected rather than computing them
     * again, and the page orders by the columns the slice carries.
     *
     * Edge cases:
     *
     * A column sort renders no Relevance at all.
     */
    val ids = Map(SearchSource.Folder -> Set("f1"), SearchSource.Person -> Set("p1"))
    val text = "alice OR bob paris -lake"

    for ((engine, dialect) <- engines) {
      val sql = flatSql(dialect, textQuery(text, ids, searchSort = List(SearchSort.Relevance), rpp = 50))

      withClue(s"$engine: $sql") {
        // The alternatives share one CASE, so their group scores once, as its best match; the exclusion scores nothing
        "CASE WHEN".r.findAllIn(sql).size shouldBe 2
        "THEN (\\d)".r.findAllMatchIn(sql).map(_.group(1)).mkString shouldBe "552211521"
        // The slice orders by what it selected, so the Relevance is not computed again for the order
        "\\(CASE WHEN .* ELSE 0 END \\+ CASE WHEN .* ELSE 0 END\\) AS \\w+, asset0\\.original_created_at AS".r
          .findFirstIn(sql)
          .isDefined shouldBe true
        "ORDER BY \\w+ DESC, \\w+ DESC NULLS LAST, \\w+ ASC LIMIT".r.findFirstIn(sql).isDefined shouldBe true
        sql.contains("ORDER BY p.sort_value DESC, p.second_sort_value DESC NULLS LAST, p.id ASC LIMIT 50") shouldBe true
      }

      val byFilename = List(SearchSort("filename", SortDirection.ASC))
      flatSql(dialect, textQuery(text, ids, searchSort = byFilename, rpp = 50)).contains("CASE WHEN") shouldBe false
    }
  }

  test("A grouped Relevance page scores every match once, then orders, slices and continues over the scores") {

    /**
     * Setup:
     *
     * A continuation of a page for "beach" sorted by Relevance and grouped by Date Taken or by Location, on both engines, from an
     * anchor with a capture time or with none.
     *
     * Assertions:
     *
     * The Relevance is computed once per match into a `scored` CTE and read from there: the continuation compares the Relevance,
     * then the capture time with undated assets last, then the ID, and the slice, the candidates and the page all order by the
     * scores. Every placeholder has a bound value.
     *
     * Edge cases:
     *
     * An anchor without a capture time continues among the undated assets, and a cursor missing the capture time altogether is
     * refused.
     */
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
      val scored = "scored\\d+"

      withClue(s"$engine/$by/$second: $text") {
        // The Relevance is computed once per match, into scored, and read from there
        text.contains("scored (id, day, sort_value, second_sort_value) AS MATERIALIZED (SELECT asset0.id AS ") shouldBe true
        "CASE WHEN \\(asset0".r.findAllIn(text).size shouldBe 1
        // Among the anchor's Relevance an older capture follows it, and so does every asset with none: they are last
        val afterTie =
          if (second == SortValue.Null) s"\\($scored\\.second_sort_value IS NULL AND $scored\\.id > \\?\\)"
          else
            s"\\($scored\\.second_sort_value < \\? OR $scored\\.second_sort_value IS NULL OR " +
              s"\\($scored\\.second_sort_value = \\? AND $scored\\.id > \\?\\)\\)"
        s"\\($scored\\.sort_value < \\? OR \\($scored\\.sort_value = \\? AND $afterTie\\)\\)".r
          .findFirstIn(text)
          .isDefined shouldBe true
        // The slice orders by the scores; the page orders its candidates by the columns they carry
        s"$scored\\.sort_value DESC, $scored\\.second_sort_value DESC NULLS LAST, $scored\\.id ASC LIMIT".r
          .findFirstIn(text)
          .isDefined shouldBe true
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

    /**
     * Setup:
     *
     * A query with a recycled flag and a Location filter, rendered as the cells of a box around Paris at a cell size of 0.25
     * degrees on both engines.
     *
     * Assertions:
     *
     * The plotted points are the union of an asset's own point in the box and, for an asset without one, the pin of each Location
     * it is in that is in the box, both branches carrying the search's filters. The points are gridded into cells, and one window
     * pass counts and averages each cell and picks its newest asset, undated last, with no null ordering spelled out and every
     * placeholder bound.
     */
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

    /**
     * Setup:
     *
     * The text "beach" with a recycled flag, rendered as the map bounds on both engines.
     *
     * Assertions:
     *
     * The bounds are one min, max and count aggregate over the same plotted points, with no box, and both point sources apply the
     * text through the one CTE of the term's document source.
     */
    for ((engine, dialect) <- engines) withClue(engine) {
      val query = textQuery("beach", params = Map("is_recycled" -> false))
      val statement = SearchQueries.mapBounds(dialect, query, "repo-1")
      val text = statement.toString.replaceAll("\\s+", " ")

      withClue(text) {
        text.contains(", points (asset_id, latitude, longitude, taken) AS (") shouldBe true
        text.contains("UNION ALL") shouldBe true
        text.contains("SELECT min(latitude), max(latitude), min(longitude), max(longitude), count(*) FROM points") shouldBe true
        text.contains("BETWEEN") shouldBe false
        // The text filter is applied to both point sources, which read the one CTE of the term's document source
        "FROM search_document ".r.findAllIn(text).size shouldBe 1
        "IN \\(SELECT asset_id FROM text_0_0_document\\)".r.findAllIn(text).size shouldBe 2
        text.count(_ == '?') shouldBe binds(statement)
      }
    }
  }

  test("The map's Locations are one statement: the matches once, then the pinned Locations in the box joined to their members") {

    /**
     * Setup:
     *
     * A query with a recycled flag and a folder filter, rendered as the map's Locations in a box across the antimeridian on both
     * engines.
     *
     * Assertions:
     *
     * The matching relation is materialized once, and the repository's pinned Locations in the box are joined to their matching
     * members and their category and grouped per Location, with every placeholder bound.
     *
     * Edge cases:
     *
     * The box across the antimeridian covers the longitudes on both sides of it.
     */
    for ((engine, dialect) <- engines) withClue(engine) {
      val query = new SearchQuery(params = Map("is_recycled" -> false), folderIds = Set("f1"))
      val statement = SearchQueries.mapLocations(dialect, query, "repo-1", BoundingBox(-1.0, 179.0, 1.0, -179.0))
      val text = statement.toString.replaceAll("\\s+", " ").trim

      withClue(text) {
        // The matching relation is evaluated once, not once per Location
        text.startsWith("WITH matched (id) AS MATERIALIZED (SELECT asset0.id AS ") shouldBe true
        "FROM asset ".r.findAllIn(text).size shouldBe 1
        (text.contains("folder_id = ANY(?)") || text.contains("folder_id IN (SELECT value FROM json_each(?))")) shouldBe true
        text.contains(
          "FROM location JOIN location_asset ON location_asset.location_id = location.id " +
            "JOIN matched ON matched.id = location_asset.asset_id " +
            "LEFT JOIN location AS category ON category.id = location.category_id") shouldBe true
        text.contains("WHERE location.repository_id = ? AND location.kind = ?") shouldBe true
        // A box across the antimeridian covers both sides of it
        text.contains("(location.longitude >= ? OR location.longitude <= ?)") shouldBe true
        text.contains("GROUP BY location.id, location.name, category.name, location.latitude, location.longitude") shouldBe true
        text.count(_ == '?') shouldBe binds(statement)
      }
    }
  }

  test("A Location's group count is driven by its own members, not by a pass over the library") {

    /**
     * Setup:
     *
     * A query of 50 rows a page with a recycled flag, grouped by Location and sorted by file name, on both engines.
     *
     * Assertions:
     *
     * The group count joins the page Location's own memberships to the asset under the search's predicates, rather than testing
     * every asset's membership with an `IN` subquery.
     */
    for ((engine, dialect) <- engines) withClue(engine) {
      val query = new SearchQuery(
        params = Map("is_recycled" -> false),
        rpp = 50,
        searchSort = List(SearchSort("filename", SortDirection.ASC)),
        grouping = Some(SearchGrouping(GroupBy.Location)))
      val text = SearchQueries.groupedByLocation(dialect, query, "repo-1").toString.replaceAll("\\s+", " ").trim
      val counts = text.substring(text.indexOf("group_counts AS MATERIALIZED"), text.indexOf("UNION ALL SELECT p.location_id"))

      withClue(counts) {
        // The page Location's memberships joined to the asset, under the search's own predicates
        "FROM asset asset\\d+ JOIN location_asset location_asset\\d+ ON \\(?asset\\d+\\.id = location_asset\\d+\\.asset_id".r
          .findFirstIn(counts)
          .isDefined shouldBe true
        "location_asset\\d+\\.location_id = p\\.location_id".r.findFirstIn(counts).isDefined shouldBe true
        counts.contains("asset0.is_recycled = ?") shouldBe true
        counts.contains("IN (SELECT location_asset") shouldBe false
      }
    }
  }

  /** The matching relation alone, which every statement of a search is a shell over */
  private def matchingSql(engine: SearchDialect, query: SearchQuery): String =
    DbApi.renderSql(SearchQueries.matching(engine, query, "repo-1"), Db.config, engine.dialect)

  /** The exact count's statement, whitespace collapsed: the matching relation with the text's CTEs at its head */
  private def countSql(engine: SearchDialect, query: SearchQuery): String =
    SearchQueries.count(engine, query, "repo-1").toString.replaceAll("\\s+", " ").trim

  /** A flat page's statement, whitespace collapsed */
  private def flatSql(engine: SearchDialect, query: SearchQuery): String =
    SearchQueries.flat(engine, query, "repo-1").toString.replaceAll("\\s+", " ").trim

  /** The engine query strings a Search text binds, in term order: every text value bound other than the repository */
  private def textBinds(engine: SearchDialect, text: String): List[String] =
    bound(SearchQueries.count(engine, textQuery(text), "repo-1")).collect { case value: String if value != "repo-1" => value }

  /** Every value a statement binds, in order */
  private def bound(statement: SqlStr): List[Any] = SqlStr.flatten(statement).interpsIterator.map(_.value).toList

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
      searchSort: List[SearchSort] = Nil,
      rpp: Int = 0,
      totalCap: Int = Const.Search.TOTAL_CAP): SearchQuery =
    new SearchQuery(
      text = Some(text),
      params = params,
      locationIds = locationIds,
      searchSort = searchSort,
      rpp = rpp,
      totalCap = totalCap,
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
