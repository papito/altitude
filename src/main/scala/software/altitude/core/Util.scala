package software.altitude.core

import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.ISODateTimeFormat
import org.slf4j.LoggerFactory

import java.io.PrintWriter
import java.io.StringWriter

/**
 * This grab-bag if miscellany will be up for some spring cleaning at some point.
 */
object Util {
  private final val log = LoggerFactory.getLogger(getClass)

  def utcNow: DateTime = new DateTime().withZoneRetainFields(DateTimeZone.forID("UTC")).withMillisOfSecond(0)
  def utcNowNoTZ: DateTime = new DateTime().withMillisOfSecond(0)

  def logStacktrace(e: Exception): String = {
    e.printStackTrace()
    val sw: StringWriter = new StringWriter()
    val pw: PrintWriter = new PrintWriter(sw)
    e.printStackTrace(pw)
    val strStacktrace = sw.toString
    log.error(s"${e.getClass.getName} exception: $strStacktrace")
    strStacktrace
  }

  def isoDateTime (dt: Option[DateTime]): String = {
    if (dt.isDefined) ISODateTimeFormat.dateTime().print(dt.get) else ""
  }

  def randomStr(size: Int = 10): String = scala.util.Random.alphanumeric.take(size).mkString
}
