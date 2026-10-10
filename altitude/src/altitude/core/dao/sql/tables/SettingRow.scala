package altitude.core.dao.sql.tables

import scalasql.Table

/** The `setting` table: one row per Setting, keyed by its dotted name, with its value and its default as text. */
case class SettingRow[T[_]](name: T[String], value: T[String], defaultValue: T[String])

object SettingRow extends Table[SettingRow]:
  override def tableName: String = "setting"
