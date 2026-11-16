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

  /** The system property that names the environment. It wins over `ALTITUDE_ENV`, so what a launcher passes is not overridden. */
  val PROPERTY = "altitude.env"

  /**
   * The environment variables read here. Those that set a config key are read by `environment.conf`, and the two logging
   * variables by `LogSettings`.
   */
  object Variable:
    val PREFIX = "ALTITUDE_"
    val ENV = "ALTITUDE_ENV"
    val CONFIG = "ALTITUDE_CONFIG"

  // Every accepted spelling of each environment, in the order a refusal lists them
  private val SPELLINGS: Seq[(String, String)] = Seq(
    "dev" -> Name.DEV,
    "development" -> Name.DEV,
    "DEV" -> Name.DEV,
    "DEVELOPMENT" -> Name.DEV,
    "test" -> Name.TEST,
    "TEST" -> Name.TEST,
    "prod" -> Name.PROD,
    "production" -> Name.PROD,
    "PROD" -> Name.PROD,
    "PRODUCTION" -> Name.PROD
  )

  /** The values that name an environment */
  val ACCEPTED: Seq[String] = SPELLINGS.map(_._1)

  /** The process's `ALTITUDE_` variables: all of the process environment that the application reads */
  def variables: Map[String, String] = sys.env.filter((name, _) => name.startsWith(Variable.PREFIX))

  /**
   * The environment a start runs in: the `altitude.env` property, then the `ALTITUDE_ENV` variable, then prod. An empty value
   * counts as unset. A value that names no environment throws, naming the accepted ones, so a misspelled one never runs as
   * another environment. `ENV` is not read: it is a POSIX shell's own variable, the file an interactive `sh` reads at startup.
   */
  def resolve(property: Option[String], variables: Map[String, String]): String =
    def named(value: String, source: String): String =
      SPELLINGS.collectFirst { case (spelling, name) if spelling == value => name }.getOrElse {
        throw IllegalArgumentException(
          s"""$source is "$value", which names no environment. Accepted values: ${ACCEPTED.mkString(", ")}""")
      }

    property
      .filter(_.nonEmpty)
      .map(named(_, s"The $PROPERTY system property"))
      .orElse(variables.get(Variable.ENV).filter(_.nonEmpty).map(named(_, s"The ${Variable.ENV} environment variable")))
      .getOrElse(Name.PROD)

  var CURRENT: String = resolve(Option(System.getProperty(PROPERTY)), variables)

  /**
   * The folder a relative path of the config is under, and where the config file is looked for: the jar's folder in prod, so a
   * start reads the same config and data wherever it is run from; the working directory in dev and test.
   */
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
   * extension only from a path. Not called when the `altitude.native.dir` property names the folder that holds the extension
   * (`TransactionManager.vectorExtensionPath`), as in the Docker image, where nothing is extracted.
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
