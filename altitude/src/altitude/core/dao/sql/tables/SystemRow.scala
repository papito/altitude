package altitude.core.dao.sql.tables

import scalasql.Table

/** The `system` table: exactly one row, whose ID is an integer rather than a UUID string. */
case class SystemRow[T[_]](id: T[Int], version: T[Int], isInitialized: T[Boolean])

object SystemRow extends Table[SystemRow]:
  override def tableName: String = "system"
