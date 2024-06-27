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
    accountType = (json \ C.User.ACCOUNT_TYPE).as[AccountType],
  ).withCoreAttr(json)

}

case class User(id: Option[String] = None,
                email: String,
                accountType: AccountType) extends BaseModel {

  override def toJson: JsObject = Json.obj(
    C.Base.ID -> id,
    C.User.EMAIL -> email,
    C.User.ACCOUNT_TYPE -> accountType,
  ) ++ coreJsonAttrs

  override def toString: String = s"<user> ${id.getOrElse("NO ID")}, email: $email, accountType: $accountType"

  def forgetMe(): Unit = {
    println("User: this is where you'd invalidate the saved token in you User model")
  }
}
