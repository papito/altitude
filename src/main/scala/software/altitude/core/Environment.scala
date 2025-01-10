package software.altitude.core

import java.io.File
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object Environment extends Enumeration {
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

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

  val ROOT_PATH: String = CURRENT match {
    case Name.PROD =>
      val url = Environment.getClass.getProtectionDomain.getCodeSource.getLocation
      new File(url.toURI).getParentFile.getAbsolutePath
    case _ => System.getProperty("user.dir")
  }
  logger.info(s"Root path: $ROOT_PATH")

  private val RESOURCES_PATH: String = CURRENT match {
    case Name.PROD => new File(ROOT_PATH, "resources").getAbsolutePath
    case _ => "src/main/resources"
  }
  logger.info(s"Resources path: $RESOURCES_PATH")

  val OPENCV_RESOURCE_PATH: String = new File(RESOURCES_PATH, "opencv").getAbsolutePath
}
