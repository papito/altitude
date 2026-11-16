package altitude.core

import com.typesafe.config.Config
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object Environment:
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  object Name:
    val TEST = "test"
    val PROD = "prod"
    val DEV = "dev"

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
  logger.debug(s"Root path: $ROOT_PATH")

  def isDev: Boolean = CURRENT == Name.DEV

  /** A development-only text key: its value only in dev; set anywhere else, it is ignored (empty), with a WARN */
  def devSetting(config: Config, key: String, environment: String = CURRENT): String =
    val value = config.getString(key)
    if value.isEmpty then ""
    else if environment != Name.DEV then
      logger.warn(s"$key is for the dev environment only: ignored")
      ""
    else value

  /** A development-only flag: on only when it is `true` in dev; set anywhere else, it is off, with a WARN */
  def devSwitch(config: Config, key: String, environment: String = CURRENT): Boolean =
    if !config.getBoolean(key) then false
    else if environment != Name.DEV then
      logger.warn(s"$key is for the dev environment only: ignored")
      false
    else true

  /** Where `resolveResourcePath` extracts resources when running from a JAR */
  private lazy val tempResourceDir: Path =
    val dir = Files.createTempDirectory("altitude-resources")
    logger.debug(s"Created temp resource directory: $dir")
    dir.toFile.deleteOnExit()
    dir

  /**
   * A classpath resource as an absolute filesystem path, extracted to a temp directory when inside a JAR: SQLite loads its vector
   * extension only from a path.
   */
  def resolveResourcePath(classpathPath: String): String =
    val resourceUrl = getClass.getResource(classpathPath)
    if resourceUrl == null then throw RuntimeException(s"Classpath resource not found: $classpathPath")

    resourceUrl.getProtocol match {
      case "file" =>
        new File(resourceUrl.toURI).getAbsolutePath

      case "jar" =>
        val destFile = tempResourceDir.resolve(classpathPath.stripPrefix("/")).toFile

        if !destFile.exists() then
          destFile.getParentFile.mkdirs()
          val stream: InputStream = getClass.getResourceAsStream(classpathPath)
          if stream == null then throw RuntimeException(s"Classpath resource not found: $classpathPath")
          try {
            Files.copy(stream, destFile.toPath, StandardCopyOption.REPLACE_EXISTING)
            destFile.deleteOnExit()
            logger.debug(s"Extracted classpath resource $classpathPath to $destFile")
          } finally {
            stream.close()
          }

        destFile.getAbsolutePath

      case protocol =>
        throw RuntimeException(s"Unsupported resource URL protocol: $protocol for $classpathPath")
    }
