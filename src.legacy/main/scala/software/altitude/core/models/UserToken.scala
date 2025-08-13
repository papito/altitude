package software.altitude.core.models

import java.time.LocalDateTime
import play.api.libs.json.JsObject
import play.api.libs.json.Json
import play.api.libs.json.JsSuccess
import play.api.libs.json.JsValue
import play.api.libs.json.OWrites
import play.api.libs.json.Reads

import scala.language.implicitConversions

import software.altitude.core.FieldConst
import software.altitude.core.util.Util

object UserToken {
  implicit val reads: Reads[UserToken] = (json: JsValue) => {
    val expiresAtStr = (json \ FieldConst.UserToken.EXPIRES_AT).as[String]
    JsSuccess(
      UserToken(
        userId = (json \ FieldConst.UserToken.ACCOUNT_ID).as[String],
        token = (json \ FieldConst.UserToken.TOKEN).as[String],
        expiresAt = Util.stringToLocalDateTime(expiresAtStr).get
      ))
  }

  implicit val writes: OWrites[UserToken] = (userToken: UserToken) => {
    Json.obj(
      FieldConst.UserToken.ACCOUNT_ID -> userToken.userId,
      FieldConst.UserToken.TOKEN -> userToken.token,
      FieldConst.UserToken.EXPIRES_AT -> userToken.expiresAt.toString
    )
  }
  implicit def fromJson(json: JsValue): UserToken = Json.fromJson[UserToken](json).get
}

case class UserToken(userId: String, token: String, expiresAt: LocalDateTime) {

  lazy val toJson: JsObject = Json.toJson(this).as[JsObject]
}
