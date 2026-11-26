package altitude.core

import ch.qos.logback.core.PropertyDefinerBase

/**
 * The two logging variables of the prod logback configuration (`logback.xml`): `ALTITUDE_LOG_FORMAT` names the root logger's
 * appender and `ALTITUDE_LOG_LEVEL` its level. Each is read from a system property of its name, then from the environment, and is
 * no config key: logback configures itself at the first logger, before the config file is read.
 *
 * logback reads them through [[LogFormatDefiner]] and [[LogLevelDefiner]], while it configures itself. So nothing here may log,
 * nor touch an object that does (`Environment`): that would be logging from inside logback's own start.
 *
 * An unknown value is refused in two steps. logback is answered the default, since left to itself it gives an unknown format no
 * appender and an unknown level DEBUG. Then the start fails ([[requireValid]], called by `App` before it builds anything), and by
 * then there is a working log to say so in.
 */
object LogSettings:

  /**
   * One logging variable.
   *
   * @param variable
   *   the name of the environment variable, and of the system property
   * @param what
   *   what a value names, for a refusal
   * @param accepted
   *   the accepted values, each in the one spelling logback is given
   * @param default
   *   the value of an unset variable
   */
  final case class Setting(variable: String, what: String, accepted: Seq[String], default: String):

    /**
     * The value: the system property's, then the environment variable's, then the default. A value is trimmed and its case
     * ignored, and an empty one counts as unset, so the next source is read. A value that is none of the accepted ones is an
     * error naming its source and the accepted values; the next source is no fallback for it.
     */
    def resolve(properties: Map[String, String], variables: Map[String, String]): Either[String, String] =
      def named(values: Map[String, String], source: String): Option[(String, String)] =
        values.get(variable).map(_.trim).filter(_.nonEmpty).map(_ -> source)

      named(properties, "system property").orElse(named(variables, "environment variable")) match
        case None => Right(default)
        case Some((value, source)) =>
          accepted
            .find(_.equalsIgnoreCase(value))
            .toRight(s"""The $variable $source is "$value", which is no $what. Accepted values: ${accepted.mkString(", ")}""")

    /** What logback is answered: the default in place of an unknown value, which [[requireValid]] refuses */
    def valueOrDefault(properties: Map[String, String], variables: Map[String, String]): String =
      resolve(properties, variables).getOrElse(default)

  /** The root logger's appender, by its name in `logback.xml` */
  val FORMAT: Setting = Setting("ALTITUDE_LOG_FORMAT", "log format", Seq("text", "json"), "text")

  /** The root logger's level */
  val LEVEL: Setting = Setting("ALTITUDE_LOG_LEVEL", "log level", Seq("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF"), "INFO")

  /** Throws when a logging variable has an unknown value, naming each such variable and its accepted values */
  def requireValid(properties: Map[String, String], variables: Map[String, String]): Unit =
    val refusals = Seq(FORMAT, LEVEL).flatMap(_.resolve(properties, variables).left.toOption)
    if refusals.nonEmpty then throw IllegalArgumentException(refusals.mkString(". "))

/** A logging variable as `logback.xml` reads it with `<define>`: the value in the process's system properties and environment */
abstract class LogSettingDefiner(setting: LogSettings.Setting) extends PropertyDefinerBase:
  override def getPropertyValue: String = setting.valueOrDefault(sys.props.toMap, sys.env)

/** `logback.xml`'s `LOG_FORMAT`: the name of the appender the root logger writes to */
class LogFormatDefiner extends LogSettingDefiner(LogSettings.FORMAT)

/** `logback.xml`'s `LOG_LEVEL`: the root logger's level */
class LogLevelDefiner extends LogSettingDefiner(LogSettings.LEVEL)
