package altitude.core.util

import altitude.core.models.BaseModel

object QueryResult:
  def empty[Model <: BaseModel]: QueryResult[Model] = QueryResult(List.empty, total = 1, rpp = 0, sort = List())

case class QueryResult[Model <: BaseModel](records: List[Model], total: Int, rpp: Int, sort: List[Sort]):
  val nonEmpty: Boolean = records.nonEmpty
  val isEmpty: Boolean = records.isEmpty
  val totalPages: Int = Math.ceil(total / rpp.toDouble).toInt
