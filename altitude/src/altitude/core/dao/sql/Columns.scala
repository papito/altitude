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
   * A bind value of whatever type the caller's untyped map happened to hold. The accepted types are the ones the hand-built SQL
   * accepted, plus an `Option` for a nullable column in an update's `SET` (`None` renders `NULL`; it is not a bind, and it is
   * meaningless in a predicate, where `= NULL` never matches); anything else was, and still is, an error rather than a silently
   * mistyped bind.
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

  /** `column IN (?, ?, ...)`, every value bound */
  def isIn(column: Expr[?], values: Iterable[Any], dialect: Dialect): Expr[Boolean] =
    val bound = SqlStr.join(values.toList.map(literal(_, dialect)), SqlStr.commaSep)
    Expr[Boolean](implicit ctx => sql"$column IN ($bound)")

  /**
   * The column is one of the IDs, with the whole set bound as one parameter, so a set of thousands (a folder subtree, the
   * candidates of a selective text search) neither grows the statement nor reaches an engine's parameter limit: an array on
   * PostgreSQL, a JSON array read back through `json_each` on SQLite. An empty set matches nothing.
   */
  def isInSet(column: Expr[?], ids: Set[String], dialect: Dialect): Expr[Boolean] =
    import dialect.*

    dialect match
      case _: PostgresDialect =>
        given TypeMapper[Set[String]] = varcharArrayMapper
        Expr[Boolean](implicit ctx => sql"$column = ANY($ids)")
      case _: SqliteDialect =>
        val json = ujson.write(ids)
        Expr[Boolean](implicit ctx => sql"$column IN (SELECT value FROM json_each($json))")
      case other => throw IllegalArgumentException(s"No ID set binding for dialect $other")

  /**
   * Binds a set of strings as a PostgreSQL `varchar[]`. The element type matters: against a `CHAR(36)` ID column a `varchar[]` is
   * cast to the column's type and the column's index is used, where a `text[]` would cast the column instead and scan.
   */
  private val varcharArrayMapper: TypeMapper[Set[String]] = new TypeMapper[Set[String]]:
    def jdbcType: JDBCType = JDBCType.ARRAY

    def get(r: ResultSet, idx: Int): Set[String] = throw UnsupportedOperationException("An ID set is only ever bound")

    def put(r: PreparedStatement, idx: Int, v: Set[String]): Unit =
      r.setArray(idx, r.getConnection.createArrayOf("varchar", v.toArray[AnyRef]))
