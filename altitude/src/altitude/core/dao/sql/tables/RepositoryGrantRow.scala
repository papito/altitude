package altitude.core.dao.sql.tables

import scalasql.Table

/** The `repository_grant` table: an account's access to a Library and its Permissions there, as a bitmask */
case class RepositoryGrantRow[T[_]](accountId: T[String], repositoryId: T[String], permissions: T[Int])

object RepositoryGrantRow extends Table[RepositoryGrantRow]:
  override def tableName: String = "repository_grant"
