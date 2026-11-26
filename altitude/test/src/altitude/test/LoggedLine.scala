package altitude.test

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import java.util.concurrent.ConcurrentLinkedQueue
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters.*

import altitude.core.Const

/** A log line as it was written: its thread, its message, and the request ID and user the MDC held for it */
final case class LoggedLine(thread: String, message: String, requestId: Option[String], user: Option[String]) {

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
          Option(mdc.get(Const.LogContext.USER))))
    }
  }

  /**
   * Runs `f` while the lines the application's loggers write at DEBUG and above, on any thread, are captured in order; `f` is
   * given a reader of the lines captured so far. `logback-test.xml` silences these loggers, so the lines are captured here and
   * written nowhere.
   */
  def capturing[T](f: (() => Seq[LoggedLine]) => T): T = {
    val logger = LoggerFactory.getLogger("altitude.core").asInstanceOf[Logger]
    val capture = new Capture
    val level = logger.getLevel
    capture.setContext(logger.getLoggerContext)
    capture.start()
    logger.addAppender(capture)
    logger.setLevel(Level.DEBUG)
    logger.setAdditive(false)
    try f(() => capture.lines.asScala.toSeq)
    finally {
      logger.setAdditive(true)
      logger.detachAppender(capture)
      logger.setLevel(level)
    }
  }

  /** The lines captured while `f` runs ([[capturing]]) */
  def during(f: => Any): Seq[LoggedLine] = capturing {
    lines =>
      f
      lines()
  }
}
