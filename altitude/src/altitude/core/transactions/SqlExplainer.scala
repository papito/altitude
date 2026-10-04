package altitude.core.transactions

import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import scala.util.matching.Regex

import altitude.core.Const

/** The rules of the SQL explain log: which statements it explains, which of them are one query, and how an entry reads */
object SqlExplainer:
  val FILE_NAME = "sql-debug.log"

  /** A value bound to a placeholder, and how to bind it again to another statement */
  case class Bind(value: Any, replay: PreparedStatement => Unit)

  /** A stack frame, printed as `File.scala:line (method)` */
  case class Frame(className: String, file: String, line: Int, method: String):

    /** The method as written in the source (a lambda's frame can be `getTree$$anonfun$1`), if the frame names one */
    def sourceMethod: Option[String] =
      method.split('$').find(part => part.nonEmpty && part != "anonfun" && !part.forall(_.isDigit))

    override def toString: String = s"$file:$line (${sourceMethod.getOrElse(method)})"

  private val EXPLAINABLE: Regex = """(?i)^\s*(SELECT|WITH|INSERT|UPDATE|DELETE)\b""".r
  private val WRITING: Regex = """(?i)\b(INSERT|UPDATE|DELETE)\b""".r

  // A quoted string or identifier, with its doubled quotes
  private val QUOTED: Regex = """'(?:[^']|'')*'|"(?:[^"]|"")*"""".r
  private val PLACEHOLDER: Regex = """\?""".r
  private val PLACEHOLDER_LIST: Regex = """\(\s*\?(?:\s*,\s*\?)*\s*\)""".r
  // Digits that are not part of an identifier, a numbered parameter or another number
  private val NUMBER: Regex = """(?<![\w.$])\d+(?:\.\d+)?(?!\w)""".r

  private val APP_PACKAGE = "altitude."
  private val DAO_PACKAGE = "altitude.core.dao."
  // Where the explainer and its proxies are: never where a statement comes from
  private val OWN_PACKAGE = "altitude.core.transactions."

  private val ENTRY_RULE = "=" * 80
  private val SECTION_RULE = "-" * 80
  private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

  /** Whether the statement is DML, going by its first keyword: housekeeping and DDL are not explained */
  def isExplainable(sql: String): Boolean = EXPLAINABLE.findPrefixOf(sql).isDefined

  /**
   * Whether the statement may write: a writing keyword appears somewhere in it. This errs toward "write" (a
   * `SELECT ... FOR UPDATE`, a data-modifying `WITH`), which is explained without being executed.
   */
  def isWrite(sql: String): Boolean = WRITING.findFirstIn(sql).isDefined

  /**
   * What makes two statements the same query: the SQL with whitespace collapsed, a list of placeholders reduced to one and
   * numeric literals replaced by a marker. Quoted strings are part of the shape as they are.
   */
  def shapeKey(sql: String): String =
    mapUnquoted(sql) {
      unquoted =>
        val collapsed = PLACEHOLDER_LIST.replaceAllIn(unquoted.replaceAll("\\s+", " "), "(?)")
        NUMBER.replaceAllIn(collapsed, "N")
    }.trim

  /** The value as a literal of either engine */
  def literal(value: Any): String = value match
    case null => "NULL"
    case v: (java.lang.Number | java.lang.Boolean) => v.toString
    case v: java.sql.Array => v.getArray.asInstanceOf[Array[?]].map(literal).mkString("ARRAY[", ", ", "]")
    case v => s"'${v.toString.replace("'", "''")}'"

  /**
   * The statement with each placeholder replaced by the literal of the value bound to it, by position. A question mark inside a
   * quoted string is not a placeholder, and a placeholder with no value stays one.
   */
  def runnable(sql: String, values: Map[Int, Any]): String =
    var index = 0
    mapUnquoted(sql) {
      PLACEHOLDER.replaceAllIn(
        _,
        _ =>
          index += 1
          values.get(index).fold("?")(value => Regex.quoteReplacement(literal(value))))
    }

  /**
   * Where a statement comes from, given the stack with its innermost frame first: the innermost application frame, and, when that
   * is a DAO's, the first application frame above it outside the DAOs - the caller a generic `BaseDao` statement is traced to. A
   * lambda's frame that names no method (`$anonfun$1`) is given the method of the nearest frame of its class above it, the one
   * the lambda is written in.
   */
  def origin(stack: Seq[Frame]): (Option[Frame], Option[Frame]) =
    val frames = stack.filter(frame => frame.className.startsWith(APP_PACKAGE) && !frame.className.startsWith(OWN_PACKAGE))

    def named(frame: Frame): Frame =
      if frame.sourceMethod.isDefined then frame
      else
        frames
          .dropWhile(_ ne frame)
          .find(above => above.className == frame.className && above.sourceMethod.isDefined)
          .fold(frame)(above => frame.copy(method = above.method))

    frames.headOption match
      case Some(origin) if origin.className.startsWith(DAO_PACKAGE) =>
        (Some(named(origin)), frames.find(!_.className.startsWith(DAO_PACKAGE)).map(named))
      case origin => (origin.map(named), None)

  /**
   * One entry of the log: a header, where the statement comes from, the statement as prepared, its runnable form when it has
   * bound values, and the plan.
   */
  def entry(
      number: Int,
      time: LocalTime,
      origin: Option[Frame],
      caller: Option[Frame],
      sql: String,
      values: Map[Int, Any],
      plan: String): String =
    val statement = dedent(sql)
    val runnableStatement = dedent(runnable(sql, values))

    List(
      Some(ENTRY_RULE),
      Some(s"#$number  ${if isWrite(sql) then "WRITE" else "READ"}  ${time.format(TIME_FORMAT)}"),
      origin.map(frame => s"Origin:      $frame"),
      caller.map(frame => s"Called from: $frame"),
      Some(SECTION_RULE),
      Some(statement),
      Option.when(runnableStatement != statement)(s"\nRunnable:\n$runnableStatement"),
      Some(SECTION_RULE),
      Some(plan)
    ).flatten.mkString("", "\n", "\n")

  /** The statement without the indentation of the source it was written in, and without the blank lines around it */
  private def dedent(sql: String): String = sql.replaceFirst("^\\s*\\n", "").stripTrailing.stripIndent

  /** Applies `f` to the stretches of the statement outside quoted strings and identifiers, which are kept as they are */
  private def mapUnquoted(sql: String)(f: String => String): String =
    val result = new StringBuilder
    var end = 0
    for quoted <- QUOTED.findAllMatchIn(sql) do
      result ++= f(sql.substring(end, quoted.start)) ++= quoted.matched
      end = quoted.end
    (result ++= f(sql.substring(end))).toString

/**
 * The SQL explain log, a development aid: the first time a query of a new shape ([[SqlExplainer.shapeKey]]) is about to run, it
 * is explained on its own connection and the plan is appended to `file` with where the statement comes from. The file is emptied
 * when the explainer is created, so it holds each query of the running process once.
 *
 * PostgreSQL explains a read with `ANALYZE`, which runs it one extra time, and a write without, so nothing is written twice.
 * SQLite's `EXPLAIN QUERY PLAN` runs nothing.
 */
class SqlExplainer(engine: String, file: File) extends AutoCloseable:
  import SqlExplainer.*

  final private val logger: Logger = LoggerFactory.getLogger(getClass)

  // The shapes offered since the explainer was created, explained or not
  private val seenShapes = ConcurrentHashMap.newKeySet[String]()

  // Creates the file, or empties the one of an earlier process
  private val writer = Files.newBufferedWriter(file.toPath)

  private var entryCount = 0

  /**
   * Logs the statement if its shape is new. `conn` is the connection the statement is about to run on, so the explain sees what
   * the statement will: the transaction's snapshot, its uncommitted rows and its settings. Nothing is thrown: a statement that
   * cannot be explained is logged with the error in place of its plan.
   */
  def offer(conn: Connection, sql: String, binds: Map[Int, Bind]): Unit =
    // `add` admits a shape once, also when two threads meet it at the same time
    if !seenShapes.add(shapeKey(sql)) then return

    try
      val (origin, caller) = SqlExplainer.origin(stack())
      val plan =
        try explain(conn, sql, binds)
        catch
          case NonFatal(ex) =>
            logger.debug(s"Could not explain [$sql]: ${ex.getMessage}")
            s"Could not explain: ${ex.getMessage}"

      val values = binds.view.mapValues(_.value).toMap
      append(number => entry(number, LocalTime.now, origin, caller, sql, values, plan))
    catch case NonFatal(ex) => logger.warn(s"Could not write to the SQL explain log: ${ex.getMessage}")

  def close(): Unit = synchronized(writer.close())

  /** The stack of the calling thread, innermost frame first */
  private def stack(): Seq[Frame] =
    StackWalker.getInstance.walk {
      _.map(
        frame =>
          Frame(
            frame.getClassName,
            Option(frame.getFileName).getOrElse(frame.getClassName),
            frame.getLineNumber,
            frame.getMethodName)).toList.asScala.toList
    }

  /** The engine's plan for the statement, with the values bound to it */
  private def explain(conn: Connection, sql: String, binds: Map[Int, Bind]): String =
    val lines = engine match
      case Const.DbEngineName.POSTGRES =>
        val options = if isWrite(sql) then "SETTINGS" else "ANALYZE, BUFFERS, SETTINGS"
        inSavepoint(conn)(rows(conn, s"EXPLAIN ($options) $sql", binds)(_.getString(1)))

      case Const.DbEngineName.SQLITE =>
        // A row names its parent, which comes before it; the top rows have parent 0
        val depths = mutable.Map(0 -> 0)
        rows(conn, s"EXPLAIN QUERY PLAN $sql", binds)(row => (row.getInt("id"), row.getInt("parent"), row.getString("detail")))
          .map {
            (id, parent, detail) =>
              val depth = depths.getOrElse(parent, 0)
              depths(id) = depth + 1
              "  " * depth + detail
          }

    lines.mkString("\n")

  /**
   * Runs `f` and takes back whatever it did to the transaction. An explain the engine refuses, or cancels at the time limit for
   * reads, would otherwise leave a PostgreSQL transaction aborted.
   */
  private def inSavepoint[A](conn: Connection)(f: => A): A =
    val savepoint = conn.setSavepoint()
    try f
    finally
      conn.rollback(savepoint)
      conn.releaseSavepoint(savepoint)

  /** The rows of a statement run with the recorded values bound to it */
  private def rows[A](conn: Connection, sql: String, binds: Map[Int, Bind])(read: ResultSet => A): List[A] =
    val statement = conn.prepareStatement(sql)
    try
      binds.values.foreach(_.replay(statement))
      val resultSet = statement.executeQuery()
      Iterator.continually(resultSet).takeWhile(_.next()).map(read).toList
    finally statement.close()

  /** Appends one whole entry, numbered in the order entries are written */
  private def append(entry: Int => String): Unit = synchronized {
    entryCount += 1
    writer.write(entry(entryCount))
    writer.flush()
    logger.debug(s"Explained query #$entryCount")
  }
