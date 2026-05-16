package altitude.core.models

import altitude.core.util.JsonCodec

enum AccountType:
  case Admin, User, Guest

object AccountType:
  given JsonCodec.ReadWriter[AccountType] = JsonCodec
    .readwriter[String]
    .bimap(
      _.toString,
      AccountType.valueOf(_)
    )
