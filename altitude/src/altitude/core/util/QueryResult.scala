package altitude.core.util

object QueryResult {
  val EMPTY: QueryResult = QueryResult(List[ujson.Obj](), total = 1, rpp = 0, sort = List())
}

case class QueryResult(records: List[ujson.Obj], total: Int, rpp: Int, sort: List[Sort]) {
  val nonEmpty: Boolean = records.nonEmpty
  val isEmpty: Boolean = records.isEmpty
  val totalPages: Int = Math.ceil(total / rpp.toDouble).toInt
}
