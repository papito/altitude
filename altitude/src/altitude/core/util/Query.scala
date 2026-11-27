package altitude.core.util

object Query:
  enum ParamType:
    case EQ, GT, LT, GTE, LTE, IN, RANGE, OR, CONTAINS, MATCHES

  case class QueryParam(values: Set[Any], paramType: ParamType, negate: Boolean = false):
    require(values.nonEmpty)

    if paramType == ParamType.RANGE || paramType == ParamType.OR then require(values.size == 2)

    def this(value: Any, paramType: ParamType, negate: Boolean) =
      this(Set(value), paramType, negate)

  def EQUALS(value: Any): QueryParam = QueryParam(Set(value), ParamType.EQ, negate = false)
  def NOT_EQUALS(value: Any): QueryParam = QueryParam(Set(value), ParamType.EQ, negate = true)

  def LT(value: Any): QueryParam = QueryParam(Set(value), ParamType.LT, negate = false)
  def NOT_LT(value: Any): QueryParam = QueryParam(Set(value), ParamType.LT, negate = true)

  def LTE(value: Any): QueryParam = QueryParam(Set(value), ParamType.LTE, negate = false)
  def NOT_LTE(value: Any): QueryParam = QueryParam(Set(value), ParamType.LTE, negate = true)

  def GT(value: Any): QueryParam = QueryParam(Set(value), ParamType.GT, negate = false)
  def NOT_GT(value: Any): QueryParam = QueryParam(Set(value), ParamType.GT, negate = true)

  def GTE(value: Any): QueryParam = QueryParam(Set(value), ParamType.GTE, negate = false)
  def NOT_GTE(value: Any): QueryParam = QueryParam(Set(value), ParamType.GTE, negate = true)

  def RANGE(values: List[Any]): QueryParam = QueryParam(values.toSet, ParamType.RANGE, negate = false)
  def NOT_IN_RANGE(values: List[Any]): QueryParam = QueryParam(values.toSet, ParamType.RANGE, negate = true)

  def OR(values: List[Any]): QueryParam = QueryParam(values.toSet, ParamType.OR, negate = false)
  def NOT_OR(values: List[Any]): QueryParam = QueryParam(values.toSet, ParamType.OR, negate = true)

  def IN(values: Set[Any], negate: Boolean = false): QueryParam =
    // One value is an equality
    if values.size == 1 then QueryParam(values, ParamType.EQ, negate)
    else QueryParam(values, ParamType.IN, negate)
  def NOT_IN(values: Set[Any]): QueryParam = IN(values, negate = true)

  def CONTAINS(value: Any): QueryParam = QueryParam(Set(value), ParamType.CONTAINS, negate = false)
  def NOT_CONTAINS(value: Any): QueryParam = QueryParam(Set(value), ParamType.CONTAINS, negate = true)

  def MATCHES(value: Any): QueryParam = QueryParam(Set(value), ParamType.MATCHES, negate = false)
  def NOT_MATCHES(value: Any): QueryParam = QueryParam(Set(value), ParamType.MATCHES, negate = true)

enum SortDirection(val id: Int):
  case ASC extends SortDirection(0)
  case DESC extends SortDirection(1)

object SortDirection:
  def apply(id: Int): SortDirection = SortDirection.values
    .find(_.id == id)
    .getOrElse(
      throw IllegalArgumentException(s"Invalid SortDirection id: $id")
    )

/**
 * The parameters of a generic read or write: what the rows match, never their order or a page of them (a search's order and pages
 * are `SearchQuery`'s)
 */
class Query(val params: Map[String, Any] = Map()):
  // A copy with the params added
  def add(_params: (String, Any)*): Query = Query(params = params ++ _params)
