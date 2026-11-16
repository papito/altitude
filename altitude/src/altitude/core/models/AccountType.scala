package altitude.core.models

import altitude.core.util.JsonCodec

/** The kind of an account, stored and carried in the login token by its name. `description` is its one-line summary in the UI. */
enum AccountType(val description: String):
  case Admin extends AccountType("Every Permission in every Library, and the accounts and Settings")
  case User extends AccountType("The Permissions chosen in each Library")
  case Guest extends AccountType("Browses the Libraries shared with it")

object AccountType:
  given JsonCodec.ReadWriter[AccountType] = JsonCodec
    .readwriter[String]
    .bimap(
      _.toString,
      AccountType.valueOf(_)
    )
