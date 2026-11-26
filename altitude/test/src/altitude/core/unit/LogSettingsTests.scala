package altitude.core.unit

import altitude.test.TestFocus
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.joran.util.ConfigurationWatchListUtil
import ch.qos.logback.core.status.Status
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers.*
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters.*

import altitude.core.Const
import altitude.core.LogSettings

/**
 * The two logging variables (`LogSettings`) and the logback configurations they act on. The resolution tests pass their own
 * system properties and environment. The tests that load `logback.xml` set both variables as system properties, which win over
 * the environment, so no test depends on the environment of the process that runs it.
 */
@DoNotDiscover class LogSettingsTests extends funsuite.AnyFunSuite with TestFocus {

  private val FORMAT = LogSettings.FORMAT
  private val LEVEL = LogSettings.LEVEL
  private val none = Map.empty[String, String]

  /** The prod configuration, which a start reads when nothing names another */
  private val PROD_FILE = "/logback.xml"

  /** What `run` and `runBackground` name in `build.mill` */
  private val DEV_FILE = "/logback-dev.xml"

  /**
   * Runs `f` over a logger context of its own, configured from a classpath file as logback configures a start's, with the two
   * logging variables set as system properties for as long as the configuration is read.
   */
  private def configured[T](file: String, format: String, level: String)(f: LoggerContext => T): T = {
    val context = LoggerContext()
    val named = Seq(FORMAT.variable -> format, LEVEL.variable -> level)
    // Put back afterwards, should the test run itself have been started with one
    val before = named.map((name, value) => name -> Option(System.setProperty(name, value)))
    try {
      val configurator = JoranConfigurator()
      configurator.setContext(context)
      configurator.doConfigure(getClass.getResource(file))
      f(context)
    } finally {
      before.foreach((name, value) => value.fold(System.clearProperty(name))(System.setProperty(name, _)))
      context.stop()
    }
  }

  /** What logback reported while it read the configuration at WARN or above; any of these makes a start print its whole status */
  private def problemsOf(context: LoggerContext): Seq[String] =
    context.getStatusManager.getCopyOfStatusList.asScala.toSeq.filter(_.getLevel >= Status.WARN).map(_.getMessage)

  /** The names of the appenders the root logger writes to */
  private def rootAppendersOf(context: LoggerContext): Seq[String] =
    context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).iteratorForAppenders.asScala.toSeq.map(_.getName)

  /** An event as the root's one appender writes it: the bytes that reach the console */
  private def written(context: LoggerContext, event: ILoggingEvent): String = {
    val root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
    val appender = root.iteratorForAppenders.asScala.toSeq.head.asInstanceOf[ConsoleAppender[ILoggingEvent]]
    String(appender.getEncoder.encode(event), StandardCharsets.UTF_8)
  }

  /** An account's ID, as the MDC holds a request's user */
  private val ACCOUNT_ID = "3f2b8c1e-7a4d-4e9b-9c55-0d6e1f2a3b4c"

  /**
   * An event of a signed-in request's thread: a message pattern with arguments, the MDC's request ID and user, and optionally a
   * failure
   */
  private def eventOf(
      context: LoggerContext,
      failure: Option[Throwable] = None,
      mdc: Map[String, String] = Map(Const.LogContext.REQUEST_ID -> "ab12cd", Const.LogContext.USER -> ACCOUNT_ID))
      : LoggingEvent = {
    val event = LoggingEvent(
      classOf[Logger].getName,
      context.getLogger("altitude.core.service.Example"),
      if failure.isDefined then Level.ERROR else Level.INFO,
      "Stored {} of {} files\nfor \"{}\"",
      failure.orNull,
      Array[AnyRef]("3", "7", "an import")
    )
    event.setMDCPropertyMap(mdc.asJava)
    event
  }

  test("Each logging variable takes its accepted values in either case, trimmed") {

    /**
     * Setup:
     *
     * Every accepted format and level, spelled in lower, upper and mixed case and padded with whitespace, given as the
     * environment variable and as the system property of the same name.
     *
     * Assertions:
     *
     * Each spelling resolves, from either source, to the one spelling logback takes: the appender's name for a format and the
     * level's name for a level. The accepted formats are text and json, and the accepted levels TRACE, DEBUG, INFO, WARN, ERROR
     * and OFF.
     */
    FORMAT.accepted shouldBe Seq("text", "json")
    LEVEL.accepted shouldBe Seq("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF")

    Seq(FORMAT, LEVEL).foreach {
      setting =>
        setting.accepted.foreach {
          value =>
            val mixed = s"${value.take(1).toUpperCase}${value.drop(1).toLowerCase}"
            Seq(value, value.toLowerCase, value.toUpperCase, mixed, s" $value ", s"\t${value.toLowerCase}\n").foreach {
              spelling =>
                withClue(s"${setting.variable} [$spelling]: ") {
                  setting.resolve(none, Map(setting.variable -> spelling)) shouldBe Right(value)
                  setting.resolve(Map(setting.variable -> spelling), none) shouldBe Right(value)
                }
            }
        }
    }
  }

  test("An unset or empty logging variable is its default, and the system property wins over the environment") {

    /**
     * Setup:
     *
     * Each logging variable unset, empty and blank, in the environment and as a system property; and a property and a variable
     * naming different values.
     *
     * Assertions:
     *
     * With nothing set the format is text and the level INFO. An empty or blank value counts as unset, so the next source is
     * read, and the default with none left. A property with a value wins over the variable.
     *
     * Edge cases:
     *
     * A variable of another name, the other logging variable's included, sets nothing.
     */
    FORMAT.default shouldBe "text"
    LEVEL.default shouldBe "INFO"

    Seq(FORMAT -> "json", LEVEL -> "DEBUG").foreach {
      (setting, other) =>
        withClue(s"${setting.variable}: ") {
          val named = Map(setting.variable -> other)

          setting.resolve(none, none) shouldBe Right(setting.default)
          Seq("", " ", "\t \n").foreach {
            blank =>
              val blanked = Map(setting.variable -> blank)
              setting.resolve(none, blanked) shouldBe Right(setting.default)
              setting.resolve(blanked, none) shouldBe Right(setting.default)
              setting.resolve(blanked, blanked) shouldBe Right(setting.default)
              setting.resolve(blanked, named) shouldBe Right(other)
          }

          setting.resolve(Map(setting.variable -> setting.default), named) shouldBe Right(setting.default)
          setting.resolve(named, Map(setting.variable -> setting.default)) shouldBe Right(other)

          setting.resolve(none, Map(s"${setting.variable}S" -> other, "LOG_LEVEL" -> other)) shouldBe Right(setting.default)
        }
    }

    FORMAT.resolve(none, Map(LEVEL.variable -> "json")) shouldBe Right("text")
    LEVEL.resolve(none, Map(FORMAT.variable -> "DEBUG")) shouldBe Right("INFO")
  }

  test("An unknown value of a logging variable answers logback the default and fails the start") {

    /**
     * Setup:
     *
     * A misspelled format and a level logback does not have, each given as the environment variable and as the system property;
     * then both at once, and every accepted pair.
     *
     * Assertions:
     *
     * An unknown value resolves to an error naming its source, the value and every accepted value, while logback is answered the
     * default, so the start can log. The start's check throws that error, naming both variables when both are unknown, and passes
     * every accepted pair and the unset variables.
     *
     * Edge cases:
     *
     * An unknown property is refused even when the environment variable is good: the next source is no fallback for a bad value.
     * A level logback would itself read as DEBUG (any unknown word) and its ALL, which is not offered, are both refused.
     */
    val unknown = Seq(FORMAT -> "jsno", FORMAT -> "logfmt", LEVEL -> "verbose", LEVEL -> "ALL", LEVEL -> "WARNING", LEVEL -> "1")

    unknown.foreach {
      (setting, value) =>
        withClue(s"${setting.variable} [$value]: ") {
          val ofVariable = setting.resolve(none, Map(setting.variable -> value)).left.getOrElse(fail("Resolved"))
          ofVariable should include(s"The ${setting.variable} environment variable")
          ofVariable should include(s"\"$value\"")
          ofVariable should include(s"Accepted values: ${setting.accepted.mkString(", ")}")
          setting.valueOrDefault(none, Map(setting.variable -> value)) shouldBe setting.default

          val good = Map(setting.variable -> setting.accepted.last)
          val ofProperty = setting.resolve(Map(setting.variable -> value), good).left.getOrElse(fail("Resolved"))
          ofProperty should include(s"The ${setting.variable} system property")
          ofProperty should include(s"Accepted values: ${setting.accepted.mkString(", ")}")
          setting.valueOrDefault(Map(setting.variable -> value), good) shouldBe setting.default

          val refusal = the[IllegalArgumentException] thrownBy LogSettings.requireValid(none, Map(setting.variable -> value))
          refusal.getMessage shouldBe ofVariable
        }
    }

    val both = the[IllegalArgumentException] thrownBy
      LogSettings.requireValid(none, Map(FORMAT.variable -> "jsno", LEVEL.variable -> "verbose"))
    both.getMessage should include(FORMAT.variable)
    both.getMessage should include(FORMAT.accepted.mkString(", "))
    both.getMessage should include(LEVEL.variable)
    both.getMessage should include(LEVEL.accepted.mkString(", "))

    LogSettings.requireValid(none, none)
    LogSettings.requireValid(none, Map(FORMAT.variable -> "", LEVEL.variable -> " "))
    for format <- FORMAT.accepted; level <- LEVEL.accepted do
      LogSettings.requireValid(none, Map(FORMAT.variable -> format.toUpperCase, LEVEL.variable -> level.toLowerCase))
      FORMAT.valueOrDefault(none, Map(FORMAT.variable -> format.toUpperCase)) shouldBe format
      LEVEL.valueOrDefault(none, Map(LEVEL.variable -> level.toLowerCase)) shouldBe level
  }

  test("The prod configuration logs a line of text with an ISO date and zone, and neither colours nor caller data") {

    /**
     * Setup:
     *
     * `logback.xml` read with the format text and the level INFO, and an event of a signed-in request's thread: a message pattern
     * with three arguments and a line break, the MDC's request ID and user, and an exception with a cause.
     *
     * Assertions:
     *
     * logback reports no warning or error, so a start prints no status. The root logs at INFO to the `text` appender alone, which
     * writes the date and time in ISO 8601 with the zone's offset, the level, the request ID, the user beside it, the logger and
     * the formatted message, then the stack trace with its cause.
     *
     * Edge cases:
     *
     * The line holds no ANSI escape, and no method name or line number of the caller. No logger is set apart to log request
     * lines: the request logger's level is the root's. A line of no request, and one of a request nobody is signed in to, show
     * the request ID or the user as empty brackets.
     */
    configured(PROD_FILE, format = "text", level = "INFO") {
      context =>
        problemsOf(context) shouldBe empty
        rootAppendersOf(context) shouldBe Seq("text")
        context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getLevel shouldBe Level.INFO
        context.getLogger("altitude.core.routes.decorators$").getEffectiveLevel shouldBe Level.INFO

        val line = written(context, eventOf(context))
        (line should fullyMatch).regex(
          """\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}(Z|[+-]\d{2}:\d{2}) INFO  \[ab12cd\] \[""" + ACCOUNT_ID +
            """\] altitude\.core\.service\.Example - Stored 3 of 7 files\nfor "an import"""" + Pattern.quote(
              System.lineSeparator))
        (line should not).include("\u001b")

        val failed = written(context, eventOf(context, Some(IllegalStateException("No space", RuntimeException("Disk full")))))
        failed should include(s" ERROR [ab12cd] [$ACCOUNT_ID] altitude.core.service.Example - Stored 3 of 7 files")
        failed should include("java.lang.IllegalStateException: No space")
        failed should include("\tat ")
        failed should include("Caused by: java.lang.RuntimeException: Disk full")
        (failed should not).include("\u001b")

        written(context, eventOf(context, mdc = Map.empty)) should include(" INFO  [] [] altitude.core.service.Example - ")
        written(context, eventOf(context, mdc = Map(Const.LogContext.REQUEST_ID -> "ab12cd"))) should
          include(" INFO  [ab12cd] [] altitude.core.service.Example - ")
    }
  }

  test("ALTITUDE_LOG_FORMAT=json logs one JSON object a line") {

    /**
     * Setup:
     *
     * `logback.xml` read with the format json, in upper case, and the same two events: one plain, one with an exception that has
     * a cause.
     *
     * Assertions:
     *
     * logback reports no warning or error. The root logs to the `json` appender alone, and each event is one line that parses as
     * a JSON object holding the time, the level, the thread, the logger, the MDC with the request ID and the user, and the
     * formatted message. The failure's object holds the exception's class, message and stack frames, and its cause the same.
     *
     * Edge cases:
     *
     * The message's own line break and quotes are escaped, so the event stays one line. The message pattern and its arguments are
     * not written, only what they format to.
     */
    configured(PROD_FILE, format = "JSON", level = "INFO") {
      context =>
        problemsOf(context) shouldBe empty
        rootAppendersOf(context) shouldBe Seq("json")

        val line = written(context, eventOf(context))
        line should endWith("\n")
        (line.stripSuffix("\n") should not).include("\n")

        val json = ujson.read(line).obj
        json("formattedMessage").str shouldBe "Stored 3 of 7 files\nfor \"an import\""
        json("level").str shouldBe "INFO"
        json("loggerName").str shouldBe "altitude.core.service.Example"
        json("threadName").str shouldBe Thread.currentThread.getName
        json("timestamp").num should be > 0d
        json("mdc").obj.toMap shouldBe Map("REQUEST_ID" -> ujson.Str("ab12cd"), "USER" -> ujson.Str(ACCOUNT_ID))
        json("throwable").isNull shouldBe true
        json.keySet should contain noneOf ("message", "arguments")

        val failed = written(context, eventOf(context, Some(IllegalStateException("No space", RuntimeException("Disk full")))))
        (failed.stripSuffix("\n") should not).include("\n")

        val throwable = ujson.read(failed).obj("throwable").obj
        throwable("className").str shouldBe "java.lang.IllegalStateException"
        throwable("message").str shouldBe "No space"
        throwable("stepArray").arr should not be empty
        throwable("stepArray").arr.head.obj("className").str should startWith(getClass.getName)
        throwable("cause").obj("className").str shouldBe "java.lang.RuntimeException"
        throwable("cause").obj("message").str shouldBe "Disk full"
    }
  }

  test("ALTITUDE_LOG_LEVEL sets the root's level") {

    /**
     * Setup:
     *
     * `logback.xml` read with each accepted level, in lower case and padded with spaces.
     *
     * Assertions:
     *
     * The root logs at that level, which is every other logger's too, the request logger's included: no logger has a level of its
     * own. logback reports no warning or error for any of them.
     */
    LEVEL.accepted.foreach {
      level =>
        withClue(s"[$level]: ") {
          configured(PROD_FILE, format = "text", level = s" ${level.toLowerCase} ") {
            context =>
              problemsOf(context) shouldBe empty
              context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getLevel shouldBe Level.valueOf(level)
              context.getLoggerList.asScala.filter(_.getName != org.slf4j.Logger.ROOT_LOGGER_NAME).foreach {
                logger =>
                  if logger.iteratorForAppenders.hasNext then logger.getLevel shouldBe Level.OFF
                  else logger.getLevel shouldBe null
              }
              context.getLogger("altitude.core.routes.decorators$").getEffectiveLevel shouldBe Level.valueOf(level)
          }
        }
    }
  }

  test("With an unknown format or level the prod configuration logs by the defaults") {

    /**
     * Setup:
     *
     * `logback.xml` read with a misspelled format and a level logback does not have.
     *
     * Assertions:
     *
     * The root logs at INFO to the `text` appender, so the start's refusal of the values is itself logged. logback reports no
     * warning or error: left to itself it would give the root no appender for the format and DEBUG for the level.
     */
    configured(PROD_FILE, format = "jsno", level = "verbose") {
      context =>
        problemsOf(context) shouldBe empty
        rootAppendersOf(context) shouldBe Seq("text")
        context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getLevel shouldBe Level.INFO
    }
  }

  test("The dev configuration keeps its own levels and request lines, whatever the logging variables say") {

    /**
     * Setup:
     *
     * `logback-dev.xml`, the file a dev run names, read with the format json and the level ERROR.
     *
     * Assertions:
     *
     * The variables change nothing: the root logs at DEBUG to the coloured console appender, the application at INFO, and the
     * request logger at DEBUG, which is what writes the request lines. A line shows the MDC's request ID and user. logback
     * reports no warning or error.
     */
    configured(DEV_FILE, format = "json", level = "ERROR") {
      context =>
        problemsOf(context) shouldBe empty
        rootAppendersOf(context) shouldBe Seq("STDOUT")
        context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getLevel shouldBe Level.DEBUG
        context.getLogger("altitude.core").getLevel shouldBe Level.INFO
        context.getLogger("altitude.core.routes.decorators$").getLevel shouldBe Level.DEBUG

        val line = written(context, eventOf(context))
        line should include("\u001b[")
        line should include(ACCOUNT_ID)
        line should include("ab12cd")
    }
  }

  test("A test run is configured by logback-test.xml") {

    /**
     * Setup:
     *
     * The logger context of the test run itself. The build names its configuration file: the test module's `forkArgs` pass
     * `-Dlogback.configurationFile=logback-test.xml`.
     *
     * Assertions:
     *
     * logback configured it from the file the build names, `logback-test.xml`, and its root logger is off: neither the prod
     * configuration nor the dev one a dev run names reaches a test run. The order logback looks files up in by itself is not
     * under test, since a named file comes before it.
     */
    val context = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]

    ConfigurationWatchListUtil.getMainWatchURL(context).getPath should endWith("/logback-test.xml")
    context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getLevel shouldBe Level.OFF
  }
}
