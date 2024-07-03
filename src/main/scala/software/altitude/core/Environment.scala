package software.altitude.core

object Environment extends Enumeration {
  type Environment = String

  object Name {
    val TEST = "test"
    val PROD = "prod"
    val DEV = "dev"
  }

  var CURRENT: String = System.getenv().getOrDefault("ENV", Name.PROD) match {
    case "test" | "TEST" => Name.TEST
    case "prod" | "production" | "PROD" | "PRODUCTION" => Name.PROD
    case "dev" | "development" | "DEV" | "DEVELOPMENT" => Name.DEV
    case _ => Name.DEV
  }
}
