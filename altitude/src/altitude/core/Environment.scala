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

  /**
   * The one rule for a development-only key: it is on only when it is `true` and the environment is dev. Set anywhere else, it is
   * off, with a WARN.
   */
  def devSwitch(config: Config, key: String, environment: String = CURRENT): Boolean =
    if !config.getBoolean(key) then false
    else if environment != Name.DEV then
      logger.warn(s"$key is for the dev environment only: ignored")
      false
    else true

  /**
   * Lazily created temp directory for extracting classpath resources that need to be accessed as filesystem paths (e.g. OpenCV
   * model files). Only used when running from a JAR (prod) where classpath resources are not directly on the filesystem.
   */
  private lazy val tempResourceDir: Path =
    val dir = Files.createTempDirectory("altitude-resources")
    logger.debug(s"Created temp resource directory: $dir")
    dir.toFile.deleteOnExit()
    dir

  /**
   * Resolve a classpath resource to a filesystem path. If the resource lives directly on the filesystem (dev/test), we return its
   * path. If it's inside a JAR (prod), we extract it to a temp directory first.
   *
   * @param classpathPath
   *   the classpath resource path, e.g. "/opencv/deploy.prototxt"
   * @return
   *   an absolute filesystem path to the resource file
   */
  def resolveResourcePath(classpathPath: String): String =
    val resourceUrl = getClass.getResource(classpathPath)
    if resourceUrl == null then throw RuntimeException(s"Classpath resource not found: $classpathPath")

    resourceUrl.getProtocol match {
      case "file" =>
        // Resource is directly on the filesystem (dev/test or exploded classpath)
        new File(resourceUrl.toURI).getAbsolutePath

      case "jar" =>
        // Resource is inside a JAR — extract to a temp directory
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
