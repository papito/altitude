package software.altitude.test.core.unit

import com.typesafe.config.ConfigFactory
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import play.api.libs.json.{JsObject, Json}
import software.altitude.core.{Const => C}
import software.altitude.core.RequestContext
import software.altitude.core.dao.jdbc.BaseDao
import software.altitude.core.util.Query

import java.sql.DriverManager
import java.time.LocalDateTime

@DoNotDiscover class DeleteByQueryTests extends funsuite.AnyFunSuite {

  class TestDao(val config: com.typesafe.config.Config) extends BaseDao {
    override val tableName: String = "test_table"
    override def count(recs: List[Map[String, AnyRef]]): Int = recs.length
    override protected def jsonFunc: String = ""
    override protected def nativeBool(value: Boolean): Any = if (value) 1 else 0
    override protected def getBooleanField(value: AnyRef): Boolean = value.toString == "1"
    override protected def makeModel(rec: Map[String, AnyRef]): JsObject = Json.obj()
    override protected def getDateTimeField(value: Option[AnyRef]): Option[LocalDateTime] = None
  }

  test("deleteByQuery combines multiple parameters with AND") {
    val config = ConfigFactory.parseString(
      s"""
${C.Conf.DB_ENGINE} = "${C.DbEngineName.SQLITE}"
${C.Conf.SQLITE_URL} = "jdbc:sqlite::memory:"
"""
    )

    val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
    RequestContext.conn.value = Some(conn)
    try {
      val stmt = conn.createStatement()
      stmt.executeUpdate("CREATE TABLE test_table (id TEXT, name TEXT, value TEXT)")
      stmt.executeUpdate("INSERT INTO test_table (id, name, value) VALUES ('1','foo','bar')")
      stmt.executeUpdate("INSERT INTO test_table (id, name, value) VALUES ('2','foo','baz')")
      stmt.close()

      val dao = new TestDao(config)
      val q = new Query(params = Map("name" -> "foo", "value" -> "baz"))
      dao.deleteByQuery(q) shouldBe 1

      val rs = conn.createStatement().executeQuery("SELECT COUNT(*) AS cnt FROM test_table")
      rs.next()
      rs.getInt("cnt") shouldBe 1
      rs.close()
    } finally {
      conn.close()
      RequestContext.conn.value = None
    }
  }
}
