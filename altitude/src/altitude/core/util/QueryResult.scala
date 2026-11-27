package altitude.core.util

import altitude.core.models.BaseModel

/**
 * The rows a read found. `total` is how many match, which a read of the first few alone (`ImportDao.list`) gives beside them;
 * otherwise it is the number of records.
 */
case class QueryResult[Model <: BaseModel](records: List[Model], total: Int):
  val nonEmpty: Boolean = records.nonEmpty
  val isEmpty: Boolean = records.isEmpty

object QueryResult:
  def apply[Model <: BaseModel](records: List[Model]): QueryResult[Model] = QueryResult(records, records.length)
