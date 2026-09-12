package altitude.core.dao.sql

import scalasql.Column
import scalasql.Table
import scalasql.core.Expr
import scalasql.dialects.Dialect

/** The `SET` half of an update, resolving the caller's string-keyed data map against the table's typed columns */
object DynamicAssignments:

  def one(table: Table.Base, columns: Map[String, Expr[?]], name: String, value: Any, dialect: Dialect): Column.Assignment[?] =
    val column = Columns.required(columns, table, name)

    /*
     * The column is typed and the value came out of an untyped map, so this pairing is checked by the engine rather than by the
     * compiler - exactly as it was when the same map was bound positionally into a hand-built UPDATE. The cast is confined to
     * this line.
     */
    Column.Assignment(
      column.asInstanceOf[Column[Any]],
      Expr[Any](_ => Columns.literal(value, dialect))
    )
