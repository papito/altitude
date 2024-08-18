package software.altitude.core.models

import play.api.libs.json.JsObject
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import software.altitude.core.models.AccountType.AccountType
import software.altitude.core.{Const => C}

import scala.language.implicitConversions

object User {
  implicit def fromJson(json: JsValue): User = User(
    id = (json \ C.Base.ID).asOpt[String],
    email = (json \ C.User.EMAIL).as[String],
    name = (json \ C.User.NAME).as[String],
    accountType = (json \ C.User.ACCOUNT_TYPE).as[AccountType],
    lastActiveRepoId = (json \ C.User.LAST_ACTIVE_REPO_ID).asOpt[String],
  ).withCoreAttr(json)

}

case class User(id: Option[String] = None,
                email: String,
                name: String,
                accountType: AccountType,
                lastActiveRepoId: Option[String] = None) extends BaseModel {

  override def toJson: JsObject = Json.obj(
    C.Base.ID -> id,
    C.User.EMAIL -> email,
    C.User.NAME -> name,
    C.User.ACCOUNT_TYPE -> accountType,
    C.User.LAST_ACTIVE_REPO_ID -> lastActiveRepoId
  ) ++ coreJsonAttrs

  override def toString: String = s"<user> ${id.getOrElse("NO ID")}, email: $email, accountType: $accountType"

  def forgetMe(): Unit = {
    println("User: this is where you'd invalidate the saved token in you User model")
  }
}
