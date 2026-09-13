package altitude.core.dao.sql

import scalasql.Table
import scalasql.core.Expr
import scalasql.core.SqlStr
import scalasql.core.SqlStr.SqlStringSyntax
import scalasql.dialects.Dialect

/**
 * Resolves the string-keyed `Query` parameters and update maps of the DAO API against a table's typed columns.
 *
 * This is the one place where a column name is still a string; everything past it is an expression that carries its own bind
 * value, so a predicate and its values can no longer drift apart.
 */
object Columns:

  /**
   * A table's columns by SQL name, in declaration order.
   *
   * No row class declares a `tableColumnNameOverride`: every field name maps onto its column through the configured name mapper
   * alone, which `RowColumnTests` pins against both schemas.
   */
  def byName(table: Table.Base, exprs: Seq[Expr[?]]): Map[String, Expr[?]] =
    Table.labels(table).map(Db.config.columnNameMapper).zip(exprs).toMap

  /** The columns of one row of a known table, by SQL name */
  def of[V[_[_]]](table: Table[V], row: V[Expr], dialect: Dialect): Map[String, Expr[?]] =
    byName(table, table.containerQr(using dialect).walkExprs(row))

  def required(columns: Map[String, Expr[?]], table: Table.Base, name: String): Expr[?] =
    columns.getOrElse(name, throw IllegalArgumentException(s"No column [$name] on table [${Table.name(table)}]"))

  /** A column of a known table, by SQL name */
  def required[V[_[_]]](table: Table[V], row: V[Expr], name: String, dialect: Dialect): Expr[?] =
    required(of(table, row, dialect), table, name)

  /**
   * A bind value of whatever type the caller's untyped map happened to hold. The accepted types are exactly the ones the
   * hand-built SQL accepted; anything else was, and still is, an error rather than a silently mistyped bind.
   */
  def literal(value: Any, dialect: Dialect): SqlStr =
    import dialect.*

    value match
      case v: String => sql"$v"
      case v: Boolean => sql"$v"
      case v: Int => sql"$v"
      case v: Long => sql"$v"
      case v: Short => sql"$v"
      case v: Byte => sql"$v"
      case v: Double => sql"$v"
      case v: Float => sql"$v"
      case _ => throw IllegalArgumentException(s"This type of parameter is not supported: $value")

  /** `column = ?`, bound. Written out rather than built with `===`, which would render `IS NOT DISTINCT FROM` for a nullable. */
  def equalTo(column: Expr[?], value: Any, dialect: Dialect): Expr[Boolean] =
    Expr[Boolean](implicit ctx => sql"$column = ${literal(value, dialect)}")

  /** `column IN (?, ?, ...)`, every value bound */
  def isIn(column: Expr[?], values: Iterable[Any], dialect: Dialect): Expr[Boolean] =
    val bound = SqlStr.join(values.toList.map(literal(_, dialect)), SqlStr.commaSep)
    Expr[Boolean](implicit ctx => sql"$column IN ($bound)")
