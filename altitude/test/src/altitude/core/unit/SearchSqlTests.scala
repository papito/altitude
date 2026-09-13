package altitude.core.unit

import altitude.test.TestFocus
import java.time.LocalDate
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers.shouldBe
import scalasql.Table
import scalasql.core.DbApi
import scalasql.core.SqlStr

import altitude.core.dao.sql.Db
import altitude.core.dao.sql.search.PostgresSearchDialect
import altitude.core.dao.sql.search.SearchDialect
import altitude.core.dao.sql.search.SearchQueries
import altitude.core.dao.sql.search.SqliteSearchDialect
import altitude.core.dao.sql.tables.AssetRow
import altitude.core.util.GroupBy
import altitude.core.util.Query
import altitude.core.util.SearchCursor
import altitude.core.util.SearchGrouping
import altitude.core.util.SearchQuery
import altitude.core.util.SearchSort
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
    val postgres = flat(PostgresSearchDialect, new SearchQuery(text = Some("beach")))
    val sqlite = flat(SqliteSearchDialect, new SearchQuery(text = Some("beach")))

    withClue(postgres) {
      postgres.contains("asset0.id IN (SELECT") shouldBe true
      postgres.contains("FROM search_document search_document1") shouldBe true
      postgres.contains("tsv @@ to_tsquery(?)") shouldBe true
      // repository, pipeline flag, the document's repository and the text
      placeholders(postgres) shouldBe 4
    }

    withClue(sqlite) {
      sqlite.contains("asset0.id IN (SELECT") shouldBe true
      sqlite.contains("search_document1.body MATCH ?") shouldBe true
      placeholders(sqlite) shouldBe 4
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
    val albums = flat(SqliteSearchDialect, new SearchQuery(albumIds = Set("a1")))

    withClue(people) {
      people.contains("asset0.id IN (SELECT") shouldBe true
      people.contains("FROM face face1") shouldBe true
      people.contains("JOIN person person2") shouldBe true
      people.contains("person2.id IN (?, ?)") shouldBe true
    }

    withClue(albums) {
      albums.contains("FROM album_asset album_asset1") shouldBe true
      albums.contains("album_asset1.album_id IN (?)") shouldBe true
    }
  }

  test("Folder and column filters bind their values") {
    val query = new SearchQuery(params = Map("is_recycled" -> false), folderIds = Set("f1", "f2"))
    val sql = flat(SqliteSearchDialect, query)

    withClue(sql) {
      sql.contains("asset0.is_recycled = ?") shouldBe true
      sql.contains("asset0.folder_id IN (?, ?)") shouldBe true
      placeholders(sql) shouldBe 5
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
          Option.when(position == "dated")(LocalDate.parse("2026-09-06")),
          if (field == "original_created_at") SortValue.Null else SortValue.Text("image.jpg"),
          "id",
          "scope"
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
        text.contains("undated (id, day, sort_value) AS MATERIALIZED") shouldBe (position == "dated" && !leads)
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
      cursor = Some(SearchCursor(Some(LocalDate.parse("2026-09-06")), SortValue.Text("image.jpg"), "id-1", "scope"))
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
      searchSort = List(SearchSort("filename", SortDirection.ASC)),
      grouping = Some(SearchGrouping(GroupBy.DateTaken, SortDirection.DESC))
    )
    val text = SearchQueries.grouped(SqliteSearchDialect, query, "repo-1").toString.replaceAll("\\s+", " ")

    // The candidate slice, both day-count probes and the overall count: four copies of the same predicate
    withClue(text) {
      "search_document\\d+\\.body MATCH \\?".r.findAllIn(text).size shouldBe 4
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
    text.contains(s"SELECT $declared, p.day AS day, p.sort_value AS sort_value, d.n AS day_total") shouldBe true
  }

  private def flat(engine: SearchDialect, query: SearchQuery): String =
    DbApi.renderSql(SearchQueries.flat(engine, query, "repo-1"), Db.config, engine.dialect)

  private def placeholders(sql: String): Int = sql.count(_ == '?')

  /** How many values the statement actually binds, which has to be exactly how many placeholders it renders */
  private def binds(statement: SqlStr): Int = SqlStr.flatten(statement).interpsIterator.size
}
