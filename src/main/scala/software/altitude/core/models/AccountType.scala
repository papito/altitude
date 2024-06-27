package software.altitude.core.models

object AccountType extends Enumeration {
  type AccountType = String
  val Admin = "ADMIN"
  val User = "USER"
  val Guest = "GUEST"
}
