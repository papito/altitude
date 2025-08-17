package altitude.core.models

import upickle.default.ReadWriter

enum AccountType derives ReadWriter {
  case Admin, User, Guest
}
