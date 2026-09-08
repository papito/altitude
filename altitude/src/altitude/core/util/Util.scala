package altitude.core.util

import java.io.PrintWriter
import java.io.StringWriter
import java.sql.SQLException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.mindrot.jbcrypt.BCrypt
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import altitude.core.DuplicateException

object Util:
  final protected val logger: Logger = LoggerFactory.getLogger(getClass)

  def logStacktrace(e: Exception): String =
    e.printStackTrace()
    val sw: StringWriter = new StringWriter()
    val pw: PrintWriter = new PrintWriter(sw)
    e.printStackTrace(pw)
    val strStacktrace = sw.toString
    logger.error(s"${e.getClass.getName} exception: $strStacktrace")
    strStacktrace

  def localDateTimeToString(dt: Option[LocalDateTime]): String =
    if dt.isDefined then
      val formatter = DateTimeFormatter.ISO_DATE_TIME
      dt.get.format(formatter)
    else ""

  def stringToLocalDateTime(str: String): Option[LocalDateTime] =
    if str.isEmpty then None
    else
      val formatter = DateTimeFormatter.ISO_DATE_TIME
      Some(LocalDateTime.parse(str, formatter))

  private val outputFormatter = DateTimeFormatter.ofPattern("MMM d yyyy, h:mma", Locale.ENGLISH)

  def humanReadableDateTime(dateTime: Option[LocalDateTime]): String =
    if dateTime.isEmpty then return "N/A"
    dateTime.get.format(outputFormatter)

  private val dateFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.ENGLISH)

  /** A calendar day as a date group header reads it, "Sunday, September 6, 2026". No zone is involved: the day is the day */
  def humanReadableDate(date: LocalDate): String = date.format(dateFormatter)

  /**
   * A date group's match count as its header reads it, "(3 items)". The client rebuilds the same text as the count falls
   * (`itemCountText` in js/search-results/date-groups.js); the two have to agree.
   */
  def humanReadableItemCount(count: Int): String = if count == 1 then "(1 item)" else s"($count items)"

  def humanReadableByteCount(bytes: Long): String =
    if bytes <= 0 then return "0 B"

    val unit = 1024
    if bytes < unit then s"$bytes B"
    else
      val exp = (Math.log(bytes.toDouble) / Math.log(unit)).toInt
      val pre = ("KMGTPE").charAt(exp - 1)
      f"${bytes / Math.pow(unit, exp)}%.1f ${pre}B"

  def randomStr(size: Int = 10): String = scala.util.Random.alphanumeric.take(size).mkString

  def hashPassword(password: String): String =
    BCrypt.hashpw(password, BCrypt.gensalt())

  def checkPassword(password: String, hashedPassword: String): Boolean =
    BCrypt.checkpw(password, hashedPassword)

  def newDuplicateExceptionOrRethrow(e: SQLException, message: Option[String] = None): Exception =
    if e.getErrorCode == /* SQLITE */ 19 || e.getSQLState == /* POSTGRES */ "23505" then DuplicateException(message = message)
    else e
