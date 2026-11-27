package altitude.test

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import java.util.concurrent.ConcurrentLinkedQueue
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters.*

import altitude.core.Const

/** A log line as it was written: its thread, its message, the request ID and user the MDC held for it, and its level */
final case class LoggedLine(thread: String, message: String, requestId: Option[String], user: Option[String], level: Level) {

  /** Whether a request thread of the HTTP server wrote it, as against a pipeline, actor or test thread */
  def onRequestThread: Boolean = thread.contains(" task-")
}

object LoggedLine {

  // Reads each event's MDC as it is logged: logback reads it when first asked, from the thread that asks, and a test asks later
  private class Capture extends AppenderBase[ILoggingEvent] {
    val lines = new ConcurrentLinkedQueue[LoggedLine]()

    override protected def append(event: ILoggingEvent): Unit = {
      val mdc = event.getMDCPropertyMap
      lines.add(
        LoggedLine(
          event.getThreadName,
          event.getFormattedMessage,
          Option(mdc.get(Const.LogContext.REQUEST_ID)),
          Option(mdc.get(Const.LogContext.USER)),
          event.getLevel))
    }
  }

  /**
   * Runs `f` while the lines the application's loggers write at DEBUG and above, on any thread, are captured in order; `f` is
   * given a reader of the lines captured so far. `logback-test.xml` silences these loggers, so the lines are captured here and
   * written nowhere. A logger it pins to a level of its own is captured at DEBUG too ([[capturingAt]]).
   */
  def capturing[T](f: (() => Seq[LoggedLine]) => T): T = capturingAt(Level.DEBUG)(f)

  /** The lines captured while `f` runs, the TRACE lines included: what `ALTITUDE_LOG_LEVEL=TRACE` would write */
  def tracedDuring(f: => Any): Seq[LoggedLine] = capturingAt(Level.TRACE) {
    lines =>
      f
      lines()
  }

  /**
   * Captures at `level` every line of `altitude.core` and of the loggers below it, including those `logback-test.xml` pins to a
   * level of their own (`altitude.core.transactions` off, `altitude.core.actors` at ERROR, the model downloader's at INFO), which
   * keep that level whatever their parent's: each is set to `level` too while `f` runs, and set back after
   */
  private def capturingAt[T](level: Level)(f: (() => Seq[LoggedLine]) => T): T = {
    val logger = LoggerFactory.getLogger("altitude.core").asInstanceOf[Logger]
    val pinned = logger.getLoggerContext.getLoggerList.asScala.toList
      .filter(child => child.getName.startsWith("altitude.core.") && child.getLevel != null)
      .map(child => child -> child.getLevel)
    val capture = new Capture
    val before = logger.getLevel
    capture.setContext(logger.getLoggerContext)
    capture.start()
    logger.addAppender(capture)
    logger.setLevel(level)
    pinned.foreach((child, _) => child.setLevel(level))
    logger.setAdditive(false)
    try f(() => capture.lines.asScala.toSeq)
    finally {
      logger.setAdditive(true)
      logger.detachAppender(capture)
      pinned.foreach((child, was) => child.setLevel(was))
      logger.setLevel(before)
    }
  }

  /** The lines captured while `f` runs ([[capturing]]) */
  def during(f: => Any): Seq[LoggedLine] = capturing {
    lines =>
      f
      lines()
  }
}
