package software.altitude.core.dao.jdbc

trait Stats { this: BaseJdbcDao =>
  override protected def defaultSqlColsForSelect: List[String] = List("*")
}
