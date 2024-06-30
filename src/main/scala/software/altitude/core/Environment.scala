package software.altitude.core

object Environment extends Enumeration {
  val TEST, PROD, DEV = Value
  var ENV: Environment.Value = System.getenv().getOrDefault("ENV", "DEV") match {
    case "test" => TEST
    case "prod" => PROD
    case _ => DEV
  }
}
