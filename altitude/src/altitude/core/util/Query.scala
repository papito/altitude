package altitude.core.util

import altitude.core.FieldConst
import altitude.core.RequestContext

object Query:
  enum ParamType:
    case EQ, GT, LT, GTE, LTE, IN, RANGE, OR, CONTAINS, MATCHES

  case class QueryParam(values: Set[Any], paramType: ParamType, negate: Boolean = false):
    require(values.nonEmpty)

    // types that requires two values
    if paramType == ParamType.RANGE || paramType == ParamType.OR then require(values.size == 2)

    // overloaded to accept one value
    def this(value: Any, paramType: ParamType, negate: Boolean) =
      this(Set(value), paramType, negate)

  def EQUALS(value: Any) = QueryParam(Set(value), ParamType.EQ, negate = false)
  def NOT_EQUALS(value: Any) = QueryParam(Set(value), ParamType.EQ, negate = true)

  def LT(value: Any) = QueryParam(Set(value), ParamType.LT, negate = false)
  def NOT_LT(value: Any) = QueryParam(Set(value), ParamType.LT, negate = true)

  def LTE(value: Any) = QueryParam(Set(value), ParamType.LTE, negate = false)
  def NOT_LTE(value: Any) = QueryParam(Set(value), ParamType.LTE, negate = true)

  def GT(value: Any) = QueryParam(Set(value), ParamType.GT, negate = false)
  def NOT_GT(value: Any) = QueryParam(Set(value), ParamType.GT, negate = true)

  def GTE(value: Any) = QueryParam(Set(value), ParamType.GTE, negate = false)
  def NOT_GTE(value: Any) = QueryParam(Set(value), ParamType.GTE, negate = true)

  def RANGE(values: List[Any]) = QueryParam(values.toSet, ParamType.RANGE, negate = false)
  def NOT_IN_RANGE(values: List[Any]) = QueryParam(values.toSet, ParamType.RANGE, negate = true)

  def OR(values: List[Any]) = QueryParam(values.toSet, ParamType.OR, negate = false)
  def NOT_OR(values: List[Any]) = QueryParam(values.toSet, ParamType.OR, negate = true)

  def IN(values: Set[Any], negate: Boolean = false): QueryParam =
    // if only one value given - simplify this to be just an equals
    if values.size == 1 then QueryParam(values, ParamType.EQ, negate)
    else QueryParam(values, ParamType.IN, negate)
  def NOT_IN(values: Set[Any]): QueryParam = IN(values, negate = true)

  def CONTAINS(value: Any) = QueryParam(Set(value), ParamType.CONTAINS, negate = false)
  def NOT_CONTAINS(value: Any) = QueryParam(Set(value), ParamType.CONTAINS, negate = true)

  def MATCHES(value: Any) = QueryParam(Set(value), ParamType.MATCHES, negate = false)
  def NOT_MATCHES(value: Any) = QueryParam(Set(value), ParamType.MATCHES, negate = true)

enum SortDirection(val id: Int):
  case ASC extends SortDirection(0)
  case DESC extends SortDirection(1)

object SortDirection:
  def apply(id: Int): SortDirection = SortDirection.values
    .find(_.id == id)
    .getOrElse(
      throw IllegalArgumentException(s"Invalid SortDirection id: $id")
    )

case class Sort(param: String, direction: SortDirection)

class Query(val params: Map[String, Any] = Map(), val rpp: Int = 0, val page: Int = 1, val sort: List[Sort] = List()):
  if rpp < 0 then throw IllegalArgumentException(s"Invalid results per page value: $rpp")
  if page < 1 then throw IllegalArgumentException(s"Invalid page value: $page")

  if sort.size > 1 then throw IllegalArgumentException("Only one sort currently supported")

  val isSorted: Boolean = sort.nonEmpty

  // append new params to the query and return a new copy
  def add(_params: (String, Any)*): Query = Query(params = params ++ _params, rpp = rpp, page = page, sort = sort)

  def withRepository(): Query =
    Query(
      params = params ++ Map(FieldConst.REPO_ID -> RequestContext.getRepository.persistedId),
      rpp = rpp,
      page = page,
      sort = sort)