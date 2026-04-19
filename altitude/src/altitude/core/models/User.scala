package altitude.core.models

import play.api.libs.json.*
import play.api.libs.json.JsonNaming.SnakeCase

object User:
  given config: JsonConfiguration = JsonConfiguration(SnakeCase)
  given format: OFormat[User] = Json.format[User]
  given Conversion[JsValue, User] = json => Json.fromJson[User](json).get

case class User(
    id: Option[String] = None,
    email: String,
    name: String,
    accountType: AccountType,
    lastActiveRepoId: Option[String] = None)
  extends BaseModel
  with NoDates:

  override def toString: String = s"<user> ${id.getOrElse("NO ID")}, email: $email, accountType: $accountType"
  lazy val toJson: JsObject = Json.toJson(this).as[JsObject]

  def forgetMe(): Unit =
    println("User: this is where you'd invalidate the saved token in you User model")
