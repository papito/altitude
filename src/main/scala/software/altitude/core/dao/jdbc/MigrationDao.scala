package software.altitude.core.dao.jdbc
import play.api.libs.json.JsObject
import software.altitude.core.Configuration
import software.altitude.core.RequestContext

abstract class MigrationDao(override val config: Configuration)
  extends BaseDao with software.altitude.core.dao.MigrationDao {

  override val tableName = "system"

  /**
   * Get current version of the schema
   *
   * @return The integer version
   */
  override def currentVersion: Int = {
    val sql = s"SELECT version FROM $tableName"
    val version = try {
      val rec = getOneBySql(sql)
      rec("version").asInstanceOf[Int]
    }
    catch {
      // table does not exist
      case _: java.sql.SQLException => 0
    }
    version
  }

  /**
   * Execute an arbitrary command
   */
  override def executeCommand(command: String): Unit = {
    val stmt = RequestContext.getConn.createStatement()
    stmt.executeUpdate(command)
    stmt.close()
  }

  protected override  def makeModel(rec: Map[String, AnyRef]): JsObject = throw new NotImplementedError
}
