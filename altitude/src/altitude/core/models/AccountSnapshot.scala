package altitude.core.models

import altitude.core.util.JsonCodec
import altitude.core.util.JsonCodec.given

object AccountSnapshot:
  given JsonCodec.ReadWriter[AccountSnapshot] = JsonCodec.macroRW

  def of(account: User): AccountSnapshot =
    AccountSnapshot(id = account.persistedId, name = account.name, username = account.username, email = account.email)

/** An account as it was when a record was written, kept as JSON beside the record since the account may change or be deleted */
final case class AccountSnapshot(id: String, name: String, username: String, email: String):
  lazy val toJson: ujson.Value = JsonCodec.writeJs(this)
