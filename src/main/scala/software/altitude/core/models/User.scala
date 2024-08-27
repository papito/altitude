package software.altitude.core.models

import play.api.libs.json.JsObject
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import software.altitude.core.models.AccountType.AccountType

import scala.language.implicitConversions

object User {
  implicit def fromJson(json: JsValue): User = User(
    id = (json \ Field.ID).asOpt[String],
    email = (json \ Field.User.EMAIL).as[String],
    name = (json \ Field.User.NAME).as[String],
    accountType = (json \ Field.User.ACCOUNT_TYPE).as[AccountType],
    lastActiveRepoId = (json \ Field.User.LAST_ACTIVE_REPO_ID).asOpt[String],
  ).withCoreAttr(json)

}

case class User(id: Option[String] = None,
                email: String,
                name: String,
                accountType: AccountType,
                lastActiveRepoId: Option[String] = None) extends BaseModel {

  override def toJson: JsObject = Json.obj(
    Field.ID -> id,
    Field.User.EMAIL -> email,
    Field.User.NAME -> name,
    Field.User.ACCOUNT_TYPE -> accountType,
    Field.User.LAST_ACTIVE_REPO_ID -> lastActiveRepoId
  ) ++ coreJsonAttrs

  override def toString: String = s"<user> ${id.getOrElse("NO ID")}, email: $email, accountType: $accountType"

  def forgetMe(): Unit = {
    println("User: this is where you'd invalidate the saved token in you User model")
  }
}
