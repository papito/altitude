package altitude.core.dao.sql.tables

import scalasql.Table

/** The `stats` table: one counter per repository and dimension, with no ID and no audit columns on either engine. */
case class StatRow[T[_]](repositoryId: T[String], dimension: T[String], dimVal: T[Long])

object StatRow extends Table[StatRow]:
  override def tableName: String = "stats"
