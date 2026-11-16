package altitude.core.dao.sql

import scalasql.Column
import scalasql.Table
import scalasql.core.Expr
import scalasql.dialects.Dialect

/** The `SET` half of an update, resolving the caller's string-keyed data map against the table's typed columns */
object DynamicAssignments:

  def one(table: Table.Base, columns: Map[String, Expr[?]], name: String, value: Any, dialect: Dialect): Column.Assignment[?] =
    val column = Columns.required(columns, table, name)

    // The value comes from an untyped map, so the engine, not the compiler, checks it against the column's type
    Column.Assignment(
      column.asInstanceOf[Column[Any]],
      Expr[Any](_ => Columns.literal(value, dialect))
    )
