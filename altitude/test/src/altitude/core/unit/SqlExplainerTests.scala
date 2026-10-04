package altitude.core.unit

import java.time.LocalDateTime
import java.time.LocalTime
import org.mockito.Mockito.mock
import org.mockito.Mockito.when
import org.scalatest.DoNotDiscover
import org.scalatest.OptionValues
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers.*

import altitude.core.transactions.SqlExplainer
import altitude.core.transactions.SqlExplainer.Frame

@DoNotDiscover class SqlExplainerTests extends AnyFunSuite with OptionValues {

  test("Statements that differ in whitespace, placeholder count of a list or numeric literals have one shape") {

    /**
     * Setup:
     *
     * A statement, and the same statement laid out differently, with a longer placeholder list and with another number.
     *
     * Assertions:
     *
     * All have the same shape key, while a statement with another condition has a different one.
     *
     * Edge cases:
     *
     * A digit that is part of an identifier or of a numbered parameter is not a numeric literal, and nothing inside a quoted
     * string is touched.
     */
    val key = SqlExplainer.shapeKey("SELECT x1 FROM asset WHERE id IN (?, ?) LIMIT 50")

    SqlExplainer.shapeKey("  SELECT x1\n    FROM asset\n   WHERE id IN (?,?,  ?)\n   LIMIT 7 ") shouldBe key
    SqlExplainer.shapeKey("SELECT x1 FROM asset WHERE id IN (?) LIMIT 2.5") shouldBe key
    SqlExplainer.shapeKey("SELECT x1 FROM asset WHERE name IN (?, ?) LIMIT 50") should not be key

    SqlExplainer.shapeKey("SELECT x1, $1 FROM t") shouldBe "SELECT x1, $1 FROM t"
    SqlExplainer.shapeKey("SELECT 'a  1 (?, ?)' FROM t") shouldBe "SELECT 'a  1 (?, ?)' FROM t"
    SqlExplainer.shapeKey("SELECT 'it''s  2' FROM t") shouldBe "SELECT 'it''s  2' FROM t"
  }

  test("Only prepared DML is explainable, and a statement that may write is a write") {

    /**
     * Setup:
     *
     * Statements that begin with each DML keyword, housekeeping and DDL statements, and reads that lock or modify.
     *
     * Assertions:
     *
     * A statement is explainable when its first keyword is SELECT, WITH, INSERT, UPDATE or DELETE, in any case. It is a write
     * when one of the writing keywords appears anywhere in it as a word.
     *
     * Edge cases:
     *
     * A column whose name contains a writing keyword does not make a read a write.
     */
    List(
      "SELECT 1",
      "  select 1",
      "WITH a AS (SELECT 1) SELECT * FROM a",
      "INSERT INTO t VALUES (?)",
      "update t SET a = ?",
      "DELETE FROM t").foreach(sql => withClue(sql)(SqlExplainer.isExplainable(sql) shouldBe true))

    List("SET LOCAL statement_timeout = 5", "PRAGMA optimize", "CREATE TABLE t (id INT)", "DROP TABLE t", "SELECTED").foreach(
      sql => withClue(sql)(SqlExplainer.isExplainable(sql) shouldBe false))

    SqlExplainer.isWrite("SELECT updated_at, is_deleted FROM asset") shouldBe false
    SqlExplainer.isWrite("SELECT * FROM asset WHERE id = ? FOR UPDATE") shouldBe true
    SqlExplainer.isWrite("WITH gone AS (delete FROM t RETURNING id) SELECT * FROM gone") shouldBe true
    SqlExplainer.isWrite("INSERT INTO t VALUES (?)") shouldBe true
  }

  test("A bound value is rendered as the literal the engine reads back as the same value") {

    /**
     * Setup:
     *
     * A null, a string with a quote in it, numbers, a boolean, a timestamp and a JDBC array of strings.
     *
     * Assertions:
     *
     * Strings and temporal values are quoted, with quotes doubled; numbers and booleans are bare; a null is NULL; an array is an
     * ARRAY constructor of its elements' literals.
     */
    SqlExplainer.literal(null) shouldBe "NULL"
    SqlExplainer.literal("it's") shouldBe "'it''s'"
    SqlExplainer.literal(42) shouldBe "42"
    SqlExplainer.literal(2.5d) shouldBe "2.5"
    SqlExplainer.literal(true) shouldBe "true"
    SqlExplainer.literal(LocalDateTime.of(2024, 3, 9, 14, 2, 31)) shouldBe "'2024-03-09T14:02:31'"

    val array = mock(classOf[java.sql.Array])
    when(array.getArray).thenReturn(Array[AnyRef]("a", "b'c"), null)
    SqlExplainer.literal(array) shouldBe "ARRAY['a', 'b''c']"
  }

  test("The runnable statement has each placeholder replaced by its bound value") {

    /**
     * Setup:
     *
     * A statement with two placeholders, a question mark inside a quoted string and a placeholder followed by a cast.
     *
     * Assertions:
     *
     * Placeholders are replaced in order by the literals of their values.
     *
     * Edge cases:
     *
     * A question mark inside a quoted string is left alone, and a placeholder with no recorded value stays a placeholder.
     */
    SqlExplainer.runnable("SELECT 'why?' FROM t WHERE a = ? AND b = ?::vector AND c = ?", Map(1 -> "x", 2 -> "[1,2]")) shouldBe
      "SELECT 'why?' FROM t WHERE a = 'x' AND b = '[1,2]'::vector AND c = ?"
  }

  test("The origin is the innermost application frame, and a DAO statement also names its first caller outside the DAOs") {

    /**
     * Setup:
     *
     * A stack, innermost frame first, of the explainer's own frames, driver frames, two DAO frames, a service lambda and a
     * controller; then a stack with no DAO frame.
     *
     * Assertions:
     *
     * The origin is the first DAO frame and the caller is the service frame, with the lambda's name reduced to its method. A
     * statement issued outside a DAO has an origin and no caller.
     *
     * Edge cases:
     *
     * A lambda's frame that names no method takes the method of the nearest frame of its class above it, and keeps its line.
     */
    val stack = List(
      Frame("altitude.core.transactions.SqlExplainer", "SqlExplainer.scala", 10, "offer"),
      Frame("altitude.core.transactions.ExplainingConnection$", "ExplainingConnection.scala", 20, "$anonfun$1"),
      Frame("org.apache.commons.dbutils.QueryRunner", "QueryRunner.java", 30, "query"),
      Frame("altitude.core.dao.jdbc.SearchDao", "SearchDao.scala", 275, "countByFolder"),
      Frame("altitude.core.dao.jdbc.BaseDao", "BaseDao.scala", 40, "query"),
      Frame("altitude.core.service.FolderService", "FolderService.scala", 91, "getTree$$anonfun$1"),
      Frame("altitude.core.controller.FolderController", "FolderController.scala", 50, "tree")
    )

    SqlExplainer.origin(stack) shouldBe
      (
        Some(Frame("altitude.core.dao.jdbc.SearchDao", "SearchDao.scala", 275, "countByFolder")),
        Some(Frame("altitude.core.service.FolderService", "FolderService.scala", 91, "getTree$$anonfun$1")))

    SqlExplainer.origin(stack.drop(5)) shouldBe (Some(stack(5)), None)

    val lambda = Frame("altitude.core.dao.jdbc.BaseDao", "BaseDao.scala", 135, "$anonfun$1")
    val around = Frame("altitude.core.dao.jdbc.BaseDao", "BaseDao.scala", 134, "getOneByQuery")
    SqlExplainer.origin(lambda +: stack.take(3) :+ around)._1.value.toString shouldBe "BaseDao.scala:135 (getOneByQuery)"

    stack(3).toString shouldBe "SearchDao.scala:275 (countByFolder)"
    stack(5).toString shouldBe "FolderService.scala:91 (getTree)"
  }

  test("An entry is a ruled block: header, origin, the statement without its indentation, the runnable form and the plan") {

    /**
     * Setup:
     *
     * A read statement indented as it is in the source, with one bound value, its origin and caller, and a two-line plan.
     *
     * Assertions:
     *
     * The entry is laid out exactly: a double rule, the number, kind and time, the two origin lines, a rule, the statement with
     * its common indentation and surrounding blank lines removed, the runnable statement, a rule and the plan.
     *
     * Edge cases:
     *
     * A statement with no bound values has no runnable form, which would repeat it, and one issued outside a DAO has no caller
     * line.
     */
    val sql = "\n      SELECT id FROM asset\n       WHERE repository_id = ?\n    "
    val origin = Frame("altitude.core.dao.jdbc.SearchDao", "SearchDao.scala", 275, "countByFolder")
    val caller = Frame("altitude.core.service.FolderService", "FolderService.scala", 91, "getTree")

    SqlExplainer.entry(
      number = 12,
      time = LocalTime.of(14, 2, 31, 482000000),
      origin = Some(origin),
      caller = Some(caller),
      sql = sql,
      values = Map(1 -> "a3f1"),
      plan = "Seq Scan on asset\nPlanning Time: 0.12 ms"
    ) shouldBe
      s"""${"=" * 80}
         |#12  READ  14:02:31.482
         |Origin:      SearchDao.scala:275 (countByFolder)
         |Called from: FolderService.scala:91 (getTree)
         |${"-" * 80}
         |SELECT id FROM asset
         | WHERE repository_id = ?
         |
         |Runnable:
         |SELECT id FROM asset
         | WHERE repository_id = 'a3f1'
         |${"-" * 80}
         |Seq Scan on asset
         |Planning Time: 0.12 ms
         |""".stripMargin

    SqlExplainer.entry(3, LocalTime.of(1, 2, 3), Some(caller), None, "DELETE FROM asset", Map.empty, "DELETE") shouldBe
      s"""${"=" * 80}
         |#3  WRITE  01:02:03.000
         |Origin:      FolderService.scala:91 (getTree)
         |${"-" * 80}
         |DELETE FROM asset
         |${"-" * 80}
         |DELETE
         |""".stripMargin
  }
}
