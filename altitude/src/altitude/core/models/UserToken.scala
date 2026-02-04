package altitude.core.models

import altitude.core.FieldConst
import altitude.core.util.Util
import java.time.LocalDateTime
import play.api.libs.json.JsObject
import play.api.libs.json.Json
import play.api.libs.json.JsSuccess
import play.api.libs.json.JsValue
import play.api.libs.json.OWrites
import play.api.libs.json.Reads

object UserToken:
  given reads: Reads[UserToken] = (json: JsValue) =>
    val expiresAtStr = (json \ FieldConst.UserToken.EXPIRES_AT).as[String]
    JsSuccess(
      UserToken(
        userId = (json \ FieldConst.UserToken.ACCOUNT_ID).as[String],
        token = (json \ FieldConst.UserToken.TOKEN).as[String],
        expiresAt = Util.stringToLocalDateTime(expiresAtStr).get
      ))

  given writes: OWrites[UserToken] = (userToken: UserToken) =>
    Json.obj(
      FieldConst.UserToken.ACCOUNT_ID -> userToken.userId,
      FieldConst.UserToken.TOKEN -> userToken.token,
      FieldConst.UserToken.EXPIRES_AT -> userToken.expiresAt.toString
    )
  given Conversion[JsValue, UserToken] = json => Json.fromJson[UserToken](json).get

case class UserToken(userId: String, token: String, expiresAt: LocalDateTime):

  lazy val toJson: JsObject = Json.toJson(this).as[JsObject]
