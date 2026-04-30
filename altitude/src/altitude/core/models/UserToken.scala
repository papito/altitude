package altitude.core.models

import altitude.core.FieldConst
import altitude.core.util.Util
import java.time.LocalDateTime
import altitude.core.util.JsonCodec

object UserToken:
  given JsonCodec.ReadWriter[UserToken] = JsonCodec.readwriter[ujson.Value].bimap(
    (ut: UserToken) => ujson.Obj(
      FieldConst.UserToken.ACCOUNT_ID -> ujson.Str(ut.userId),
      FieldConst.UserToken.TOKEN -> ujson.Str(ut.token),
      FieldConst.UserToken.EXPIRES_AT -> ujson.Str(ut.expiresAt.toString)
    ),
    (json: ujson.Value) => UserToken(
      userId   = json(FieldConst.UserToken.ACCOUNT_ID).str,
      token    = json(FieldConst.UserToken.TOKEN).str,
      expiresAt = Util.stringToLocalDateTime(json(FieldConst.UserToken.EXPIRES_AT).str).get
    )
  )

  given Conversion[ujson.Value, UserToken] = json => JsonCodec.read[UserToken](json)

case class UserToken(userId: String, token: String, expiresAt: LocalDateTime):

  lazy val toJson: ujson.Obj = JsonCodec.writeJs(this).asInstanceOf[ujson.Obj]
