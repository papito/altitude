package altitude.core.dao.sql.tables

import java.time.OffsetDateTime
import scalasql.Table

/** The `user_token` table. It has no ID and no audit columns on either engine. */
case class UserTokenRow[T[_]](accountId: T[String], token: T[String], expiresAt: T[Option[OffsetDateTime]])

object UserTokenRow extends Table[UserTokenRow]:
  override def tableName: String = "user_token"
