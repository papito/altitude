package software.altitude.core.models

import play.api.libs.json.JsObject
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import software.altitude.core.util.Util

import java.time.LocalDateTime
import scala.language.implicitConversions

object UserToken {
  implicit def fromJson(json: JsValue): UserToken = {
    val expiresAtStr = (json \ Field.UserToken.EXPIRES_AT).as[String]
    UserToken(
      userId = (json \ Field.UserToken.ACCOUNT_ID).as[String],
      token = (json \ Field.UserToken.TOKEN).as[String],
      expiresAt =  Util.stringToLocalDateTime(expiresAtStr).get
    )
  }

}

case class UserToken(userId: String,
                     token: String,
                     expiresAt: LocalDateTime)  {
  def toJson: JsObject = Json.obj(
    Field.UserToken.ACCOUNT_ID -> userId,
    Field.UserToken.TOKEN -> token,
    Field.UserToken.EXPIRES_AT -> expiresAt
  )
}
