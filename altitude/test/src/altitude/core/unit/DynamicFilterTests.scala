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
import altitude.core.dao.sql.dialects.AltitudeSqliteDialect
import altitude.core.dao.sql.tables.AssetRow
import altitude.core.util.Query
import altitude.core.util.Query.QueryParam
import altitude.core.util.Sort
import altitude.core.util.SortDirection

/**
 * The string-keyed `Query` turned into a typed predicate.
 *
 * The assertions are structural rather than exact SQL: what matters is that each value is bound rather than inlined, that the
 * number of placeholders matches the number of values, and that a parameter the layer cannot honor is an error instead of a
 * quietly different query.
 */
@DoNotDiscover class DynamicFilterTests extends funsuite.AnyFunSuite with TestFocus {

  private val dialect: Dialect = AltitudeSqliteDialect

  test("An equality parameter binds its value") {
    val sql = render(new Query(params = Map("is_recycled" -> false, "folder_id" -> "f1")))

    sql.contains("asset0.is_recycled = ?") shouldBe true
    sql.contains("asset0.folder_id = ?") shouldBe true
    placeholders(sql) shouldBe 2
  }

  test("An IN parameter binds every value") {
    val query = new Query(params = Map("id" -> Query.IN(Set[Any]("a", "b", "c"))))
    val sql = render(query)

    sql.contains("asset0.id IN (?, ?, ?)") shouldBe true
    placeholders(sql) shouldBe 3
  }

  test("A single-value IN collapses to an equality, as the query model defines it") {
    val sql = render(new Query(params = Map("id" -> Query.IN(Set[Any]("only")))))

    sql.contains("asset0.id = ?") shouldBe true
    placeholders(sql) shouldBe 1
  }

  test("No parameters means no WHERE clause") {
    val sql = render(new Query())

    sql.contains("WHERE") shouldBe false
    placeholders(sql) shouldBe 0
  }

  test("Sorting and paging bind rather than inline, and the window count spans the whole match") {
    val query =
      new Query(params = Map("is_recycled" -> false), rpp = 10, page = 2, sort = List(Sort("filename", SortDirection.DESC)))
    val sql = renderPage(query)

    withClue(sql) {
      sql.contains("COUNT(1) OVER ()") shouldBe true
      // The sort term renders as the column's select-list alias, which is what both engines order by
      "ORDER BY \\S*filename DESC".r.findFirstIn(sql).isDefined shouldBe true
      sql.contains("LIMIT ?") shouldBe true
      sql.contains("OFFSET ?") shouldBe true

      // One SELECT level: the count is a window over the filtered rows, not a wrapped subquery
      "SELECT".r.findAllIn(sql).size shouldBe 1
    }
  }

  test("An unknown column is an error") {
    val thrown = intercept[IllegalArgumentException](render(new Query(params = Map("not_a_column" -> "x"))))
    thrown.getMessage.contains("not_a_column") shouldBe true
  }

  test("A negated parameter is an error rather than being silently dropped") {
    intercept[IllegalArgumentException](render(new Query(params = Map("folder_id" -> Query.NOT_EQUALS("f1")))))
    intercept[IllegalArgumentException](render(new Query(params = Map("id" -> Query.NOT_IN(Set[Any]("a", "b"))))))
  }

  test("An unsupported parameter type is an error") {
    intercept[IllegalArgumentException](render(new Query(params = Map("width" -> Query.GT(10)))))
    intercept[IllegalArgumentException](
      render(new Query(params = Map("width" -> QueryParam(Set[Any](1, 2), Query.ParamType.RANGE)))))
  }

  test("An unsupported value type is an error") {
    intercept[IllegalArgumentException](render(new Query(params = Map("folder_id" -> BigInt(1)))))
  }

  private def render(query: Query): String = {
    import dialect._
    DbApi.renderSql(matching(query), Db.config, dialect)
  }

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

  private def matching(query: Query) = {
    import dialect._
    AssetRow.select.filter(row => DynamicFilter(AssetRow, columns(row), query, dialect))
  }

  private def columns(row: AssetRow[scalasql.core.Expr]) =
    Columns.byName(AssetRow, AssetRow.containerQr(using dialect).walkExprs(row))

  private def placeholders(sql: String): Int = sql.count(_ == '?')
}
