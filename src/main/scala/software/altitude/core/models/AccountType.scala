package software.altitude.core.models

import play.api.libs.json.Format
import play.api.libs.json.Json

object AccountType extends Enumeration {
  type AccountType = Value
  val Admin: Value = Value("ADMIN")
  val User: Value = Value("USER")

  implicit val format: Format[AccountType] = Json.formatEnum(this)
}
