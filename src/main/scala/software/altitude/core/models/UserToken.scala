package software.altitude.core.models

import play.api.libs.json.JsObject
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import software.altitude.core.util.Util
import software.altitude.core.{Const => C}

import java.time.LocalDateTime
import scala.language.implicitConversions

object UserToken {
  implicit def fromJson(json: JsValue): UserToken = {
    val expiresAtStr = (json \ C.UserToken.EXPIRES_AT).as[String]
    UserToken(
      userId = (json \ C.UserToken.ACCOUNT_ID).as[String],
      token = (json \ C.UserToken.TOKEN).as[String],
      expiresAt =  Util.stringToLocalDateTime(expiresAtStr).get
    )
  }

}

case class UserToken(userId: String,
                     token: String,
                     expiresAt: LocalDateTime)  {
  def toJson: JsObject = Json.obj(
    C.UserToken.ACCOUNT_ID -> userId,
    C.UserToken.TOKEN -> token,
    C.UserToken.EXPIRES_AT -> expiresAt
  )
}
