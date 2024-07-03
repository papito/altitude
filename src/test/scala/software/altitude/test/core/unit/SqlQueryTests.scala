package software.altitude.test.core.unit

import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import software.altitude.core.Const
import software.altitude.core.RequestContext
import software.altitude.core.Util
import software.altitude.core.dao.jdbc.BaseDao
import software.altitude.core.dao.jdbc.querybuilder.SqlQueryBuilder
import software.altitude.core.models.Repository
import software.altitude.core.util.Query
import software.altitude.core.util.Sort
import software.altitude.core.util.SortDirection
import software.altitude.test.core.TestFocus

@DoNotDiscover class SqlQueryTests extends funsuite.AnyFunSuite with TestFocus {
  private val repo = new Repository(
    id = Some("1"),
    name = "repo name",
    ownerAccountId = Util.randomStr(),
    rootFolderId = "1",
    fileStoreConfig = Map(),
    fileStoreType = Const.StorageEngineName.FS)

  RequestContext.repository.value = Some(repo)
  RequestContext.account.value = None

  test("WHERE SQL query with pagination is built correctly") {
    val builder = new SqlQueryBuilder[Query](List("*"), Set("table1"))
    val q = new Query(params = Map("searchValue" -> 3), rpp = 10, page = 2)
    val sqlQuery = builder.buildSelectSql(q.withRepository())
    sqlQuery.sqlAsString shouldBe s"SELECT *, ${BaseDao.totalRecsWindowFunction} FROM table1 WHERE searchValue = ? AND repository_id = ? LIMIT 10 OFFSET 10"
    sqlQuery.bindValues.size shouldBe 2
  }

  test("Query with sorting is built correctly") {
    val builder = new SqlQueryBuilder[Query](List("*"), Set("table1"))
    val q = new Query(
      rpp = 10,
      page = 2,
      sort = List(Sort("column", SortDirection.ASC))
    )
    val sqlQuery = builder.buildSelectSql(q.withRepository())
    sqlQuery.sqlAsString shouldBe s"SELECT *, ${BaseDao.totalRecsWindowFunction} FROM table1 WHERE repository_id = ? ORDER BY column ASC LIMIT 10 OFFSET 10"
  }
}
