package altitude.core

import com.typesafe.config.Config
import java.net.URL
import java.nio.file.Path
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object Environment:
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  object Name:
    val TEST = "test"
    val PROD = "prod"
    val DEV = "dev"

  /**
   * The system property that names the environment. It wins over an `ALTITUDE_ENV` that names another, so what a launcher passes
   * is not overridden.
   */
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
   * another environment. That holds for the variable even when the property wins: the Docker image passes the property, and a
   * typo in its `ALTITUDE_ENV` stops the start instead of running as prod unnoticed. `ENV` is not read: it is a POSIX shell's own
   * variable, the file an interactive `sh` reads at startup.
   */
  def resolve(property: Option[String], variables: Map[String, String]): String =
    def named(value: String, source: String): String =
      SPELLINGS.collectFirst { case (spelling, name) if spelling == value => name }.getOrElse {
        throw IllegalArgumentException(
          s"""$source is "$value", which names no environment. Accepted values: ${ACCEPTED.mkString(", ")}""")
      }

    // Both are read, the property first, so each one's misspelling is refused whichever of them wins
    val ofProperty = property.filter(_.nonEmpty).map(named(_, s"The $PROPERTY system property"))
    val ofVariable = variables.get(Variable.ENV).filter(_.nonEmpty).map(named(_, s"The ${Variable.ENV} environment variable"))
    ofProperty.orElse(ofVariable).getOrElse(Name.PROD)

  var CURRENT: String = resolve(Option(System.getProperty(PROPERTY)), variables)

  /**
   * Where the application's classes are loaded from: its jar, or the classes folder of a start from the build (Mill's `run`, the
   * tests). None where the class loader names no location.
   */
  def codeLocation: Option[Path] =
    Option(Environment.getClass.getProtectionDomain.getCodeSource).map(source => pathOf(source.getLocation))

  /**
   * A code source's location as a path, read through its URI: `URL.getPath` keeps a space as `%20` and is `/C:/...` on Windows,
   * and `java.io.File(URI)` refuses a jar on a Windows share, `file://server/share/...`, for its authority, which `Path.of` takes
   * as the share's server (`\\server\share\...`).
   */
  def pathOf(location: URL): Path = Path.of(location.toURI)

  /**
   * The folder a relative path of the config is under, and where the config file is looked for: the jar's folder in prod, so a
   * start reads the same config and data wherever it is run from; the working directory in dev and test.
   */
  val ROOT_PATH: String = CURRENT match {
    case Name.PROD =>
      val jar = codeLocation.getOrElse(throw IllegalStateException("The application's jar is unknown: no location is named"))
      jar.toAbsolutePath.getParent.toString
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
