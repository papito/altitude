package altitude.core.models

import altitude.core.util.JsonCodec
import JsonCodec.given
import JsonCodec.macroRW

object User:
  given JsonCodec.ReadWriter[User] = JsonCodec.macroRW
  given Conversion[ujson.Value, User] = json => JsonCodec.read[User](json)

case class User(
    id: Option[String] = None,
    email: String,
    name: String,
    accountType: AccountType,
    lastActiveRepoId: Option[String] = None)
  extends BaseModel
  with NoDates:

  override def toString: String = s"<user> ${id.getOrElse("NO ID")}, email: $email, accountType: $accountType"
  lazy val toJson: ujson.Obj = JsonCodec.writeJs(this).asInstanceOf[ujson.Obj]

  def forgetMe(): Unit =
    println("User: this is where you'd invalidate the saved token in you User model")
