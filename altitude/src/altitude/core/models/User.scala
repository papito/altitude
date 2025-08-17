package altitude.core.models

import ujson.Value
import upickle.default.ReadWriter
import upickle.default.write
import upickle.default.writeJs

case class User(
    id: Option[String] = None,
    email: String,
    name: String,
    accountType: AccountType,
    lastActiveRepoId: Option[String] = None)
  extends BaseModel
  with NoDates
  derives ReadWriter:

  override def toString: String = s"<user> ${id.getOrElse("NO ID")}, email: $email, accountType: $accountType"
  def toJsonString: String = write(this)
  def toJson: Value = writeJs(this)

  def forgetMe(): Unit = {
    println("User: this is where you'd invalidate the saved token in you User model")
  }
