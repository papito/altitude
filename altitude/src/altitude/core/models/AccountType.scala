package altitude.core.models


import play.api.libs.json.*

enum AccountType:
  case Admin, User, Guest

object AccountType:
  given Reads[AccountType] = Reads {
    case JsString(value) => JsSuccess(AccountType.valueOf(value))
    case _ => JsError("Expected a string for AccountType")
  }
  given Writes[AccountType] = Writes(accountType => JsString(accountType.toString))
