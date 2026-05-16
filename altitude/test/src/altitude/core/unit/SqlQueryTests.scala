package altitude.core.unit

import altitude.test.TestFocus
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers.shouldBe

import altitude.core.Const
import altitude.core.RequestContext
import altitude.core.dao.jdbc.BaseDao
import altitude.core.dao.jdbc.querybuilder.SqlQueryBuilder
import altitude.core.models.Repository
import altitude.core.util.Query
import altitude.core.util.Sort
import altitude.core.util.SortDirection
import altitude.core.util.Util

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
    val builder = new SqlQueryBuilder[Query](List("*"), "table1")
    val q = new Query(params = Map("searchValue" -> 3), rpp = 10, page = 2)
    val sqlQuery = builder.buildSelectSql(q.withRepository())
    sqlQuery.sqlAsStringCompact shouldBe s"SELECT *, ${BaseDao.totalRecsWindowFunction} FROM table1 WHERE table1.searchValue = ? AND table1.repository_id = ? LIMIT 10 OFFSET 10"
    sqlQuery.bindValues.size shouldBe 2
  }

  test("Query with sorting is built correctly") {
    val builder = new SqlQueryBuilder[Query](List("*"), "table1")
    val q = new Query(
      rpp = 10,
      page = 2,
      sort = List(Sort("column", SortDirection.ASC))
    )
    val sqlQuery = builder.buildSelectSql(q.withRepository())
    sqlQuery.sqlAsStringCompact shouldBe s"SELECT *, ${BaseDao.totalRecsWindowFunction} FROM table1 WHERE table1.repository_id = ? ORDER BY table1.column ASC LIMIT 10 OFFSET 10"
  }
}
