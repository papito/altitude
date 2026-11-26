package altitude.core.unit

import altitude.test.TestFocus
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers.shouldBe
import scalasql.core.DbApi
import scalasql.dialects.Dialect

import altitude.core.dao.sql.Columns
import altitude.core.dao.sql.Db
import altitude.core.dao.sql.DynamicFilter
import altitude.core.dao.sql.dialects.AltitudePostgresDialect
import altitude.core.dao.sql.dialects.AltitudeSqliteDialect
import altitude.core.dao.sql.tables.AssetRow
import altitude.core.util.Query
import altitude.core.util.Query.QueryParam
import altitude.core.util.Sort
import altitude.core.util.SortDirection

/**
 * The string-keyed `Query` turned into a typed predicate. The assertions are structural rather than exact SQL: values are bound,
 * a set of strings as one placeholder and any other value one per value, and a parameter the layer cannot honor is an error
 * rather than a quietly different query.
 */
@DoNotDiscover class DynamicFilterTests extends funsuite.AnyFunSuite with TestFocus {

  private val dialect: Dialect = AltitudeSqliteDialect

  test("An equality parameter binds its value") {

    /**
     * Setup:
     *
     * A query on the asset table with two plain equality parameters, a boolean and a string.
     *
     * Assertions:
     *
     * Each parameter renders as a column equality with a placeholder, and there are exactly as many placeholders as values.
     */
    val sql = render(new Query(params = Map("is_recycled" -> false, "folder_id" -> "f1")))

    sql.contains("asset0.is_recycled = ?") shouldBe true
    sql.contains("asset0.folder_id = ?") shouldBe true
    placeholders(sql) shouldBe 2
  }

  test("An IN parameter of strings binds its set as one value") {

    /**
     * Setup:
     *
     * A query with an IN parameter of three IDs, rendered for each engine.
     *
     * Assertions:
     *
     * The parameter renders as the column tested against one bound set: an array on PostgreSQL, a JSON array read through
     * `json_each` on SQLite. Each statement has a single placeholder.
     */
    val query = new Query(params = Map("id" -> Query.IN(Set[Any]("a", "b", "c"))))
    val sqlite = render(query)
    val postgres = render(query, AltitudePostgresDialect)

    sqlite.contains("asset0.id IN (SELECT value FROM json_each(?))") shouldBe true
    placeholders(sqlite) shouldBe 1
    postgres.contains("asset0.id = ANY(?)") shouldBe true
    placeholders(postgres) shouldBe 1
  }

  test("An IN parameter of other values binds every value") {

    /**
     * Setup:
     *
     * A query with an IN parameter of three widths, and one of a string and a number.
     *
     * Assertions:
     *
     * Each renders as an IN list with one placeholder per value.
     */
    val widths = render(new Query(params = Map("width" -> Query.IN(Set[Any](10, 20, 30)))))
    widths.contains("asset0.width IN (?, ?, ?)") shouldBe true
    placeholders(widths) shouldBe 3

    val mixed = render(new Query(params = Map("filename" -> Query.IN(Set[Any]("a.jpg", 1)))))
    mixed.contains("asset0.filename IN (?, ?)") shouldBe true
    placeholders(mixed) shouldBe 2
  }

  test("A single-value IN collapses to an equality, as the query model defines it") {

    /**
     * Setup:
     *
     * A query with an IN parameter of a single ID.
     *
     * Assertions:
     *
     * It renders as a plain equality with one placeholder rather than a one-element IN list.
     */
    val sql = render(new Query(params = Map("id" -> Query.IN(Set[Any]("only")))))

    sql.contains("asset0.id = ?") shouldBe true
    placeholders(sql) shouldBe 1
  }

  test("No parameters means no WHERE clause") {

    /**
     * Setup:
     *
     * A query with no parameters.
     *
     * Assertions:
     *
     * The statement has no WHERE clause and binds nothing.
     */
    val sql = render(new Query())

    sql.contains("WHERE") shouldBe false
    placeholders(sql) shouldBe 0
  }

  test("Sorting and paging bind rather than inline, and the window count spans the whole match") {

    /**
     * Setup:
     *
     * A query for non-recycled assets sorted by file name descending, second page of ten, rendered as a paged read is built: a
     * window count over the filtered rows, the sort, then offset and limit.
     *
     * Assertions:
     *
     * The count is a window in a single SELECT rather than a wrapped subquery, the sort orders by the column's alias, and the
     * limit and offset are bound.
     */
    val query =
      new Query(params = Map("is_recycled" -> false), rpp = 10, page = 2, sort = List(Sort("filename", SortDirection.DESC)))
    val sql = renderPage(query)

    withClue(sql) {
      sql.contains("COUNT(1) OVER ()") shouldBe true
      // Both engines order by the column's select-list alias
      "ORDER BY \\S*filename DESC".r.findFirstIn(sql).isDefined shouldBe true
      sql.contains("LIMIT ?") shouldBe true
      sql.contains("OFFSET ?") shouldBe true

      "SELECT".r.findAllIn(sql).size shouldBe 1
    }
  }

  test("An unknown column is an error") {

    /**
     * Setup:
     *
     * A query with a parameter that names no column of the asset table.
     *
     * Assertions:
     *
     * Rendering it fails, and the error names the unknown column.
     */
    val thrown = intercept[IllegalArgumentException](render(new Query(params = Map("not_a_column" -> "x"))))
    thrown.getMessage.contains("not_a_column") shouldBe true
  }

  test("A negated parameter is an error rather than being silently dropped") {

    /**
     * Setup:
     *
     * Two queries with a negated parameter: a NOT_EQUALS and a NOT_IN of two values.
     *
     * Assertions:
     *
     * Both fail to render instead of quietly turning into their positive form.
     */
    intercept[IllegalArgumentException](render(new Query(params = Map("folder_id" -> Query.NOT_EQUALS("f1")))))
    intercept[IllegalArgumentException](render(new Query(params = Map("id" -> Query.NOT_IN(Set[Any]("a", "b"))))))
  }

  test("An unsupported parameter type is an error") {

    /**
     * Setup:
     *
     * Two queries with parameter types the filter does not support: a greater-than and a range.
     *
     * Assertions:
     *
     * Both fail to render, since only equality and IN are supported.
     */
    intercept[IllegalArgumentException](render(new Query(params = Map("width" -> Query.GT(10)))))
    intercept[IllegalArgumentException](
      render(new Query(params = Map("width" -> QueryParam(Set[Any](1, 2), Query.ParamType.RANGE)))))
  }

  test("An unsupported value type is an error") {

    /**
     * Setup:
     *
     * A query whose equality parameter has a BigInt value.
     *
     * Assertions:
     *
     * Rendering it fails, since a value of that type cannot be bound.
     */
    intercept[IllegalArgumentException](render(new Query(params = Map("folder_id" -> BigInt(1)))))
  }

  private def render(query: Query, engine: Dialect = dialect): String =
    DbApi.renderSql(matching(query, engine), Db.config, engine)

  private def renderPage(query: Query): String = {
    import dialect._

    val counted = matching(query).mapAggregate((row, aggregate) => (row, aggregate.size.over))
    val sorted = query.sort.headOption.fold(counted) {
      sort =>
        val ordered = counted.sortBy(row => Columns.required(columns(row._1), AssetRow, sort.param))
        if (sort.direction == SortDirection.DESC) ordered.desc else ordered.asc
    }

    DbApi.renderSql(sorted.drop((query.page - 1) * query.rpp).take(query.rpp), Db.config, dialect)
  }

  private def matching(query: Query, engine: Dialect = dialect) = {
    import engine._
    AssetRow.select.filter(row => DynamicFilter(AssetRow, columns(row, engine), query, engine))
  }

  private def columns(row: AssetRow[scalasql.core.Expr], engine: Dialect = dialect) =
    Columns.byName(AssetRow, AssetRow.containerQr(using engine).walkExprs(row))

  private def placeholders(sql: String): Int = sql.count(_ == '?')
}
