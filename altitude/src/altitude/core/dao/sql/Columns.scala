package altitude.core.dao.sql

import java.sql.JDBCType
import java.sql.PreparedStatement
import java.sql.ResultSet
import scalasql.Table
import scalasql.core.Expr
import scalasql.core.SqlStr
import scalasql.core.SqlStr.SqlStringSyntax
import scalasql.core.TypeMapper
import scalasql.dialects.Dialect
import scalasql.dialects.PostgresDialect
import scalasql.dialects.SqliteDialect

/**
 * Resolves the DAO API's string-keyed `Query` parameters and update maps against a table's typed columns. This is the one place a
 * column name is a string; past it, each expression carries its own bind value, so a predicate and its values cannot drift apart.
 */
object Columns:

  /**
   * A table's columns by SQL name, in declaration order. Field names map to columns through the name mapper alone (no row class
   * declares a `tableColumnNameOverride`), which `RowColumnTests` pins against both schemas.
   */
  def byName(table: Table.Base, exprs: Seq[Expr[?]]): Map[String, Expr[?]] =
    Table.labels(table).map(Db.config.columnNameMapper).zip(exprs).toMap

  def of[V[_[_]]](table: Table[V], row: V[Expr], dialect: Dialect): Map[String, Expr[?]] =
    byName(table, table.containerQr(using dialect).walkExprs(row))

  def required(columns: Map[String, Expr[?]], table: Table.Base, name: String): Expr[?] =
    columns.getOrElse(name, throw IllegalArgumentException(s"No column [$name] on table [${Table.name(table)}]"))

  def required[V[_[_]]](table: Table[V], row: V[Expr], name: String, dialect: Dialect): Expr[?] =
    required(of(table, row, dialect), table, name)

  /**
   * A bind value from an untyped map. An `Option` is for a nullable column in an update's `SET`: `None` renders `NULL`, which in
   * a predicate never matches. Any other type throws rather than bind silently mistyped.
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
      case Some(v) => literal(v, dialect)
      case None => sql"NULL"
      case _ => throw IllegalArgumentException(s"This type of parameter is not supported: $value")

  /** `column = ?`, bound. Written out rather than built with `===`, which would render `IS NOT DISTINCT FROM` for a nullable. */
  def equalTo(column: Expr[?], value: Any, dialect: Dialect): Expr[Boolean] =
    Expr[Boolean](implicit ctx => sql"$column = ${literal(value, dialect)}")

  /** `column IN (?, ?, ...)`, every value bound: for a small set of values that are not strings, which [[isInSet]] binds */
  def isIn(column: Expr[?], values: Iterable[Any], dialect: Dialect): Expr[Boolean] =
    val bound = SqlStr.join(values.toList.map(literal(_, dialect)), SqlStr.commaSep)
    Expr[Boolean](implicit ctx => sql"$column IN ($bound)")

  /**
   * A character column (an ID, in every caller) is one of the values, the set bound as one parameter (an array on PostgreSQL, a
   * JSON array through `json_each` on SQLite), so thousands of values neither grow the statement nor hit an engine's parameter
   * limit. An empty set matches nothing.
   */
  def isInSet(column: Expr[?], values: Set[String], dialect: Dialect): Expr[Boolean] =
    import dialect.*

    dialect match
      case _: PostgresDialect =>
        given TypeMapper[Set[String]] = varcharArrayMapper
        Expr[Boolean](implicit ctx => sql"$column = ANY($values)")
      case _: SqliteDialect =>
        val json = ujson.write(values)
        Expr[Boolean](implicit ctx => sql"$column IN (SELECT value FROM json_each($json))")
      case other => throw IllegalArgumentException(s"No string set binding for dialect $other")

  /**
   * `varchar[]`, not `text[]`: against a `CHAR(36)` ID column the array is cast to the column's type and its index is used, where
   * a `text[]` would cast the column and scan
   */
  private val varcharArrayMapper: TypeMapper[Set[String]] = new TypeMapper[Set[String]]:
    def jdbcType: JDBCType = JDBCType.ARRAY

    def get(r: ResultSet, idx: Int): Set[String] = throw UnsupportedOperationException("A set of strings is only ever bound")

    def put(r: PreparedStatement, idx: Int, v: Set[String]): Unit =
      r.setArray(idx, r.getConnection.createArrayOf("varchar", v.toArray[AnyRef]))
