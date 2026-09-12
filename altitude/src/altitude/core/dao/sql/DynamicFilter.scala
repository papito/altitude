package altitude.core.dao.sql

import scalasql.Table
import scalasql.core.Expr
import scalasql.core.SqlStr
import scalasql.core.SqlStr.SqlStringSyntax
import scalasql.dialects.Dialect

import altitude.core.util.Query
import altitude.core.util.Query.QueryParam

/**
 * A `Query`'s string-keyed parameters as one typed predicate.
 *
 * The supported parameter types are the two the hand-built WHERE clause supported, `EQ` and `IN`; anything else throws, as
 * before. `negate` now throws too: it was silently dropped before, which quietly turned a `NOT_EQUALS` into an `EQUALS`. Nothing
 * in the codebase asks for one.
 */
object DynamicFilter:

  def apply(table: Table.Base, columns: Map[String, Expr[?]], query: Query, dialect: Dialect): Expr[Boolean] =
    import dialect.*

    val predicates = query.params.toList.map((name, value) => predicate(table, columns, name, value, dialect))

    // A literal true is dropped when the query is rendered, leaving no WHERE clause at all
    if predicates.isEmpty then dialect.from(true) else predicates.reduce(_ && _)

  private def predicate(
      table: Table.Base,
      columns: Map[String, Expr[?]],
      name: String,
      value: Any,
      dialect: Dialect): Expr[Boolean] =
    val column = Columns.required(columns, table, name)

    value match
      case param: QueryParam =>
        if param.negate then throw IllegalArgumentException(s"A negated parameter is not supported: $name")

        param.paramType match
          case Query.ParamType.EQ => equalTo(column, param.values.head, dialect)
          case Query.ParamType.IN => isIn(column, param.values, dialect)
          case other => throw IllegalArgumentException(s"This type of parameter is not supported: $other")

      case other => equalTo(column, other, dialect)

  private def equalTo(column: Expr[?], value: Any, dialect: Dialect): Expr[Boolean] =
    Expr[Boolean](implicit ctx => sql"$column = ${Columns.literal(value, dialect)}")

  private def isIn(column: Expr[?], values: Set[Any], dialect: Dialect): Expr[Boolean] =
    val bound = SqlStr.join(values.toList.map(Columns.literal(_, dialect)), SqlStr.commaSep)
    Expr[Boolean](implicit ctx => sql"$column IN ($bound)")
