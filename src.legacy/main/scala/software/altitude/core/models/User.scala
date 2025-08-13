package software.altitude.core.models

import play.api.libs.json.JsObject
import play.api.libs.json.Json
import play.api.libs.json.JsonConfiguration
import play.api.libs.json.JsonNaming.SnakeCase
import play.api.libs.json.JsValue
import play.api.libs.json.OFormat

import scala.language.implicitConversions

import software.altitude.core.models.AccountType.AccountType

object User {
  implicit val config: JsonConfiguration = JsonConfiguration(SnakeCase)
  implicit val format: OFormat[User] = Json.format[User]
  implicit def fromJson(json: JsValue): User = Json.fromJson[User](json).get
}

case class User(
    id: Option[String] = None,
    email: String,
    name: String,
    accountType: AccountType,
    lastActiveRepoId: Option[String] = None)
  extends BaseModel
  with NoDates {

  lazy val toJson: JsObject = Json.toJson(this).as[JsObject]

  override def toString: String = s"<user> ${id.getOrElse("NO ID")}, email: $email, accountType: $accountType"

  def forgetMe(): Unit = {
    println("User: this is where you'd invalidate the saved token in you User model")
  }
}
