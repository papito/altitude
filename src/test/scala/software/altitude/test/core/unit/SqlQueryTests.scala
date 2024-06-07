package software.altitude.test.core.unit

import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import software.altitude.core.Const
import software.altitude.core.Context
import software.altitude.core.dao.jdbc.querybuilder.SqlQueryBuilder
import software.altitude.core.models.Repository
import software.altitude.core.util.Query
import software.altitude.core.util.Sort
import software.altitude.core.util.SortDirection
import software.altitude.test.core.TestFocus

class SqlQueryTests extends funsuite.AnyFunSuite with TestFocus {
  private val repo = new Repository(
    id = Some("1"),
    name = "repo name",
    rootFolderId = "1",
    triageFolderId = "2",
    fileStoreConfig = Map(),
    fileStoreType = Const.FileStoreType.FS)

  implicit val ctx: Context = new Context(
    repo = repo,
    user = null
  )

  test("Basic WHERE SQL query is built correctly") {
    val builder = new SqlQueryBuilder[Query](List("col1", "col2"), Set("table1", "table2"))
    val q = new Query(params = Map("searchValue" -> 3))
    val sqlQuery = builder.buildSelectSql(q)
    sqlQuery.sqlAsString shouldBe "SELECT col1, col2 FROM table1, table2 WHERE searchValue = ? AND table1.repository_id = ? AND table2.repository_id = ?"
    sqlQuery.bindValues.size shouldBe 3
  }

  test("WHERE SQL query with pagination is built correctly") {
    val builder = new SqlQueryBuilder[Query](List("*"), Set("table1"))
    val q = new Query(params = Map("searchValue" -> 3), rpp = 10, page = 2)
    val sqlQuery = builder.buildSelectSql(q)
    sqlQuery.sqlAsString shouldBe "SELECT * FROM table1 WHERE searchValue = ? AND table1.repository_id = ? LIMIT 10 OFFSET 10"
    sqlQuery.bindValues.size shouldBe 2
  }

  test("Query with sorting is built correctly") {
    val builder = new SqlQueryBuilder[Query](List("*"), Set("table1"))
    val q = new Query(
      rpp = 10,
      page = 2,
      sort = List(Sort("column", SortDirection.ASC))
    )
    val sqlQuery = builder.buildSelectSql(q)
    sqlQuery.sqlAsString shouldBe "SELECT * FROM table1 WHERE table1.repository_id = ? ORDER BY column ASC LIMIT 10 OFFSET 10"
  }
}
