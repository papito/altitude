package software.altitude.core.dao.jdbc.querybuilder

case class SqlQuery(sqlAsString: String, bindValues: List[Any]) {

  val sqlAsStringCompact: String = collapseSpaces(sqlAsString)

  private def collapseSpaces(input: String): String = {
    input.replaceAll("\\s+", " ").trim
  }
}
