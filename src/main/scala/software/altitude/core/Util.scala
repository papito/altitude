package software.altitude.core
import org.mindrot.jbcrypt.BCrypt
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object Util {
  protected final val logger: Logger = LoggerFactory.getLogger(getClass)

  def logStacktrace(e: Exception): String = {
    e.printStackTrace()
    val sw: StringWriter = new StringWriter()
    val pw: PrintWriter = new PrintWriter(sw)
    e.printStackTrace(pw)
    val strStacktrace = sw.toString
    logger.error(s"${e.getClass.getName} exception: $strStacktrace")
    strStacktrace
  }

  def localDateTimeToString(dt: Option[LocalDateTime]): String = {
    if (dt.isDefined) {
      val formatter = DateTimeFormatter.ISO_DATE_TIME
      dt.get.format(formatter)
    } else {
      ""
    }
  }

  def stringToLocalDateTime(str: String): Option[LocalDateTime] = {
    if (str.isEmpty) {
      None
    } else {
      val formatter = DateTimeFormatter.ISO_DATE_TIME
      Some(LocalDateTime.parse(str, formatter))
    }
  }

  def randomStr(size: Int = 10): String = scala.util.Random.alphanumeric.take(size).mkString

  def randomLongInt(): Long = scala.util.Random.nextLong(1000000000L)

  def hashPassword(password: String): String = {
    BCrypt.hashpw(password, BCrypt.gensalt())
  }

  def checkPassword(password: String, hashedPassword: String): Boolean = {
    BCrypt.checkpw(password, hashedPassword)
  }
}
