package software.altitude.core.util
import software.altitude.core.FieldConst
import software.altitude.core.RequestContext

object Query {
  object ParamType extends Enumeration {
    val EQ, GT, LT, GTE, LTE, IN, RANGE, OR, CONTAINS, MATCHES = Value
  }

  case class QueryParam (values: Set[Any], paramType: ParamType.Value, negate: Boolean = false) {
    require(values.nonEmpty)

    // types that requires two values
    if (paramType == ParamType.RANGE || paramType == ParamType.OR) {
      require(values.size == 2)
    }

    // overloaded to accept one value
    def this(value: Any, paramType: ParamType.Value, negate: Boolean) =
      this(Set(value), paramType, negate)
  }

  def EQUALS(value: Any) = new QueryParam(value, ParamType.EQ, negate = false)
  def NOT_EQUALS(value: Any) = new QueryParam(value, ParamType.EQ, negate = true)

  def LT(value: Any) = new QueryParam(value, ParamType.LT, negate = false)
  def NOT_LT(value: Any) = new QueryParam(value, ParamType.LT, negate = true)

  def LTE(value: Any) = new QueryParam(value, ParamType.LTE, negate = false)
  def NOT_LTE(value: Any) = new QueryParam(value, ParamType.LTE, negate = true)

  def GT(value: Any) = new QueryParam(value, ParamType.GT, negate = false)
  def NOT_GT(value: Any) = new QueryParam(value, ParamType.GT, negate = true)

  def GTE(value: Any) = new QueryParam(value, ParamType.GTE, negate = false)
  def NOT_GTE(value: Any) = new QueryParam(value, ParamType.GTE, negate = true)

  def RANGE(values: List[Any]) = new QueryParam(values, ParamType.RANGE, negate = false)
  def NOT_IN_RANGE(values: List[Any]) = new QueryParam(values, ParamType.RANGE, negate = true)

  def OR(values: List[Any]) = new QueryParam(values, ParamType.OR, negate = false)
  def NOT_OR(values: List[Any]) = new QueryParam(values, ParamType.OR, negate = true)

  def IN(values: Set[Any], negate: Boolean = false): QueryParam = {
    // if only one value given - simplify this to be just an equals
    if (values.size == 1) {
      new QueryParam(values.head, ParamType.EQ, negate)
    } else {
      QueryParam(values, ParamType.IN, negate)
    }
  }
  def NOT_IN(values: Set[Any]): QueryParam  = IN(values, negate = true)

  def CONTAINS (value: Any) = new QueryParam(value, ParamType.CONTAINS, negate = false)
  def NOT_CONTAINS(value: Any) = new QueryParam(value, ParamType.CONTAINS, negate = true)

  def MATCHES(value: Any) = new QueryParam(value, ParamType.MATCHES, negate = false)
  def NOT_MATCHES(value: Any) = new QueryParam(value, ParamType.MATCHES, negate = true)
}

object SortDirection extends Enumeration {
  val ASC, DESC = Value
}

case class Sort(param: String, direction: SortDirection.Value)

class Query(val params: Map[String, Any] = Map(),
            val rpp: Int = 0,
            val page: Int = 1,
            val sort: List[Sort] = List()) {
  if (rpp < 0) throw new IllegalArgumentException(s"Invalid results per page value: $rpp")
  if (page < 1) throw new IllegalArgumentException(s"Invalid page value: $page")

  if (sort.size > 1) {
    throw new IllegalArgumentException("Only one sort currently supported'")
  }

  val isSorted: Boolean = sort.nonEmpty

  // append new params to the query and return a new copy
  def add(_params: (String, Any)*): Query = new Query(
    params = params ++ _params,
    rpp = rpp,
    page = page,
    sort = sort)

  def withRepository(): Query = new Query(
    params = params ++ Map(FieldConst.REPO_ID -> RequestContext.getRepository.persistedId),
    rpp = rpp,
    page = page,
    sort = sort)

}
