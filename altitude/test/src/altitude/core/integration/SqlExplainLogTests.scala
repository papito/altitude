package altitude.core.integration

import com.typesafe.config.ConfigValueFactory
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.sql.PreparedStatement
import java.sql.SQLException
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.RequestContext
import altitude.core.transactions.SqlExplainer
import altitude.core.transactions.SqlExplainer.Bind
import altitude.core.transactions.TransactionManager

/**
 * The SQL explain log: each new query a transaction sends is explained once, on the transaction's own connection, and written to
 * a file with where it comes from.
 */
@DoNotDiscover class SqlExplainLogTests(override val testApp: Altitude) extends IntegrationTestCore {

  private val isSqlite = testApp.dataSourceType == Const.DbEngineName.SQLITE

  private val logFile = new File(testApp.config.getString(Const.Conf.TEST_DIR), SqlExplainer.FILE_NAME)

  /** Runs `f` with an explainer that writes to the test directory, and closes it */
  private def withExplainer[A](f: SqlExplainer => A): A = {
    logFile.getParentFile.mkdirs()
    val explainer = new SqlExplainer(testApp.dataSourceType, logFile)
    try f(explainer)
    finally explainer.close()
  }

  /** Runs `f` with a transaction manager of its own over the test database, which explains what its transactions send */
  private def withExplainingManager[A](f: TransactionManager => A): A =
    withExplainer {
      explainer =>
        // A pool of its own, next to the test application's: kept small
        val config = testApp.config.withValue(Const.Conf.POSTGRES_POOL_SIZE, ConfigValueFactory.fromAnyRef(2))
        val manager = new TransactionManager(config, Some(explainer))
        try f(manager)
        finally manager.shutdown()
    }

  private def execute(statement: PreparedStatement): Unit =
    try statement.execute()
    finally statement.close()

  private def logged: String = Files.readString(logFile.toPath)

  private def entryCount: Int = logged.linesIterator.count(_.startsWith("#"))

  /** String values bound in order, as a prepared statement's proxy records them */
  private def binds(values: String*): Map[Int, Bind] =
    values.zipWithIndex.map((value, i) => (i + 1) -> Bind(value, _.setString(i + 1, value))).toMap

  /** The assets of the test's repository: the suites share a database */
  private def countAssets: Int =
    query("SELECT count(*) AS n FROM asset WHERE repository_id = ?", testContext.repository.persistedId)
      .head("n")
      .toString
      .toInt

  test("A new query is logged once, with its plan, its origin and its runnable form") {

    /**
     * Setup:
     *
     * One asset, and a read transaction that offers the explainer a statement, the same statement with other values, and the same
     * statement with a longer IN list.
     *
     * Assertions:
     *
     * The log has one entry: a read, numbered 1, with the line of this test as its origin and no caller, the statement with the
     * first values in place of its placeholders, and the engine's plan - on PostgreSQL, one that was executed.
     */
    val asset = testContext.persistAsset()
    val repoId = testContext.repository.persistedId

    withExplainer {
      explainer =>
        testApp.txManager.asReadOnly {
          val sql = "SELECT id FROM asset WHERE repository_id = ? AND id IN (?, ?)"
          explainer.offer(RequestContext.getConn, sql, binds(repoId, asset.persistedId, "none"))
          explainer.offer(RequestContext.getConn, sql, binds("other", "a", "b"))
          explainer.offer(
            RequestContext.getConn,
            "SELECT id FROM asset WHERE repository_id = ? AND id IN (?, ?, ?)",
            binds(repoId, "a", "b", "c"))
        }
    }

    entryCount shouldBe 1
    logged should include("#1  READ")
    logged should include("Origin:      SqlExplainLogTests.scala:")
    (logged should not).include("Called from:")
    logged should include(s"WHERE repository_id = '$repoId' AND id IN ('${asset.persistedId}', 'none')")
    (logged should include).regex(if (isSqlite) "(SEARCH|SCAN) asset" else "Execution Time:")
  }

  test("A write is explained without being executed") {

    /**
     * Setup:
     *
     * One asset, and a write transaction that offers the explainer a statement deleting the repository's assets.
     *
     * Assertions:
     *
     * The asset is still there, and the entry is a write with a plan that was not executed.
     */
    testContext.persistAsset()

    withExplainer {
      explainer =>
        testApp.txManager.withTransaction {
          explainer.offer(
            RequestContext.getConn,
            "DELETE FROM asset WHERE repository_id = ?",
            binds(testContext.repository.persistedId))
          countAssets shouldBe 1
        }
    }

    logged should include("#1  WRITE")
    (logged should include).regex(if (isSqlite) "(SEARCH|SCAN) asset" else "Delete on asset")
    (logged should not).include("Execution Time:")
  }

  test("A statement that cannot be explained is logged with the error, and its transaction goes on") {

    /**
     * Setup:
     *
     * A read transaction that offers the explainer a statement selecting a column that does not exist, twice, and then counts the
     * assets.
     *
     * Assertions:
     *
     * Nothing is thrown, the transaction still runs statements, and the log has one entry, with the engine's error in place of
     * the plan.
     */
    withExplainer {
      explainer =>
        testApp.txManager.asReadOnly {
          val sql = "SELECT no_such_column FROM asset WHERE id = ?"
          explainer.offer(RequestContext.getConn, sql, binds("a"))
          explainer.offer(RequestContext.getConn, sql, binds("b"))
          countAssets shouldBe 0
        }
    }

    entryCount shouldBe 1
    (logged should include).regex("does not exist|no such column")
  }

  test("The log is emptied when an explainer is created") {

    /**
     * Setup:
     *
     * An explainer that logs one entry and is closed, then a second explainer on the same file.
     *
     * Assertions:
     *
     * The file has the first explainer's entry until the second one is created, and is empty afterwards.
     */
    withExplainer(explainer => testApp.txManager.asReadOnly(explainer.offer(RequestContext.getConn, "SELECT 1", Map.empty)))
    entryCount shouldBe 1

    withExplainer(_ => logged shouldBe "")
  }

  test("A typed query, a query runner statement and a raw batch are each explained once") {

    /**
     * Setup:
     *
     * One asset, and a write transaction of an explaining manager that reads the asset through its service (a typed query in the
     * base DAO), updates a statistic through a query runner, and inserts two statistics as one raw batch.
     *
     * Assertions:
     *
     * The log has three entries. The DAO's read names the DAO line as its origin and the service as its caller; the batch is
     * explained once, with the values of its first row, and both of its rows are inserted.
     */
    val asset = testContext.persistAsset()
    val repoId = testContext.repository.persistedId

    withExplainingManager {
      manager =>
        manager.withTransaction {
          testApp.service.asset.getById(asset.persistedId)

          update("UPDATE stats SET dim_val = dim_val + 1 WHERE repository_id = ? AND dimension = ?", repoId, "none")

          val batch =
            RequestContext.getConn.prepareStatement("INSERT INTO stats (repository_id, dimension, dim_val) VALUES (?, ?, ?)")
          try {
            List("explain_a", "explain_b").foreach {
              dimension =>
                batch.clearParameters()
                batch.setString(1, repoId)
                batch.setString(2, dimension)
                batch.setInt(3, 7)
                batch.addBatch()
            }
            batch.executeBatch()
          } finally batch.close()

          query("SELECT dimension FROM stats WHERE repository_id = ? AND dim_val = 7", repoId).length shouldBe 2
        }
    }

    // The three statements, and the count that checks the batch
    entryCount shouldBe 4
    (logged should include).regex("""Origin:      \w+Dao\.scala:\d+ \(\w+\)\nCalled from: \w+Service\.scala:\d+ \(\w+\)""")
    logged should include("UPDATE stats SET dim_val = dim_val + 1 WHERE repository_id = ? AND dimension = ?")
    logged should include(s"VALUES ('$repoId', 'explain_a', 7)")
    (logged should not).include("explain_b")
  }

  test("Statements that are not prepared DML are not explained") {

    /**
     * Setup:
     *
     * A write transaction of an explaining manager that runs a query through a plain statement, and creates and drops a temporary
     * table through prepared statements.
     *
     * Assertions:
     *
     * The log stays empty: the plain statement, the DDL and the manager's own housekeeping at the start of a transaction never
     * reach the explainer.
     */
    withExplainingManager {
      manager =>
        manager.withTransaction {
          val conn = RequestContext.getConn
          val statement = conn.createStatement()
          try statement.execute("SELECT 1")
          finally statement.close()

          execute(conn.prepareStatement("CREATE TEMPORARY TABLE explain_probe (id INT)"))
          execute(conn.prepareStatement("DROP TABLE explain_probe"))
        }
        manager.asReadOnly(())
    }

    logged shouldBe ""
  }

  test("An explained query is counted once") {

    /**
     * Setup:
     *
     * One asset, read through its service in a transaction of the test application's manager and then in one of an explaining
     * manager.
     *
     * Assertions:
     *
     * Both reads add the same number to the read query count, although the second one is also explained.
     */
    val asset = testContext.persistAsset()

    def countedReads(manager: TransactionManager): Int = {
      val before = RequestContext.readQueryCount.value
      manager.asReadOnly(testApp.service.asset.getById(asset.persistedId))
      RequestContext.readQueryCount.value - before
    }

    val plainReads = countedReads(testApp.txManager)

    withExplainingManager(countedReads(_) shouldBe plainReads)
    entryCount should be > 0
  }

  test("Only a manager with an explainer wraps its connections, and a wrapped statement fails with its own exception") {

    /**
     * Setup:
     *
     * A connection of the test application's manager and one of an explaining manager, then a statement on the latter that
     * selects from a table that does not exist.
     *
     * Assertions:
     *
     * The test application's connection is the pooled one, the explaining manager's is a proxy, and the failing statement throws
     * the driver's SQL exception rather than a wrapper of the proxy.
     */
    def isProxied(manager: TransactionManager): Boolean = {
      val conn = manager.connection(readOnly = true)
      try Proxy.isProxyClass(conn.getClass)
      finally {
        conn.rollback()
        conn.close()
      }
    }

    isProxied(testApp.txManager) shouldBe false

    withExplainingManager {
      manager =>
        isProxied(manager) shouldBe true

        intercept[SQLException] {
          manager.asReadOnly(execute(RequestContext.getConn.prepareStatement("SELECT id FROM no_such_table WHERE id = ?")))
        }
    }
  }
}
