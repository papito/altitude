package altitude.core.integration

import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.tagobjects.Slow
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.FieldConst
import altitude.core.NotFoundException
import altitude.core.RequestContext
import altitude.core.dao.AssetDao
import altitude.core.dao.RepositoryDao
import altitude.core.dao.UserDao
import altitude.core.dao.jdbc.BaseDao
import altitude.core.dao.jdbc.BaseDao.UniqueIndex
import altitude.core.util.Query
import altitude.core.util.Util

/** The reads and writes of `BaseDao` that every DAO shares, on both engines */
@DoNotDiscover class BaseDaoTests(override val testApp: Altitude) extends IntegrationTestCore {

  test("The streaming read keeps what the mapper returns and counts every row it scanned") {

    /**
     * Setup:
     *
     * Five assets of the context repository, and a statement selecting their IDs in ID order. The mapper keeps every other row,
     * counting from the second.
     *
     * Assertions:
     *
     * The read returns exactly the odd-indexed IDs of the full read of the same statement, in its order, and reports all five
     * rows as scanned; a mapper that keeps nothing returns no rows and still reports all five.
     */
    (1 to 5).foreach(_ => testContext.persistAsset())

    val sql = "SELECT id FROM asset WHERE repository_id = ? ORDER BY id"
    val values = List(RequestContext.getRepository.persistedId)

    val all = testApp.txManager.asReadOnly {
      testApp.DAO.asset.manyBySqlQuery(sql, values).map(_("id").toString)
    }
    all should have size 5

    var seen = 0
    val everyOther = testApp.txManager.asReadOnly {
      testApp.DAO.asset.collectBySqlQuery(sql, values) {
        rec =>
          seen += 1
          Option.when(seen % 2 == 0)(rec("id").toString)
      }
    }

    everyOther.kept shouldBe all.zipWithIndex.collect { case (id, index) if index % 2 == 1 => id }
    everyOther.scanned shouldBe 5

    val none = testApp.txManager.asReadOnly {
      testApp.DAO.asset.collectBySqlQuery(sql, values)(_ => None)
    }
    none.kept shouldBe empty
    none.scanned shouldBe 5
  }

  test("A read by more IDs than a statement takes parameters returns the assets among them", Slow) {

    /**
     * Setup:
     *
     * Two assets of the context repository; the first is looked up among 70,000 unknown IDs, more parameters than a PostgreSQL
     * statement takes.
     *
     * Assertions:
     *
     * The read by IDs returns the first asset alone, and so does a query by the same IDs.
     */
    val asset = testContext.insertAsset()
    testContext.insertAsset()
    val ids = amongManyUnknownIds(Set(asset.persistedId))

    testApp.txManager.asReadOnly(testApp.DAO.asset.getByIds(ids)).map(_.persistedId) shouldBe List(asset.persistedId)

    val byQuery = new Query().add(FieldConst.ID -> Query.IN(ids.toSet[Any]))
    testApp.service.asset.queryAll(byQuery).records.map(_.persistedId) shouldBe List(asset.persistedId)
  }

  test("A write by more IDs than a statement takes parameters changes the rows among them", Slow) {

    /**
     * Setup:
     *
     * Two assets of the context repository; the first is renamed by a query of its ID among 70,000 unknown IDs, more parameters
     * than a PostgreSQL statement takes.
     *
     * Assertions:
     *
     * The write changes one row, the first asset's name is the new one, and the second asset keeps its name.
     */
    val asset = testContext.insertAsset()
    val other = testContext.insertAsset()
    val byIds = new Query().add(FieldConst.ID -> Query.IN(amongManyUnknownIds(Set(asset.persistedId)).toSet[Any]))

    testApp.txManager.withTransaction {
      testApp.DAO.asset.updateByQuery(byIds, Map(FieldConst.Asset.FILENAME -> "renamed.jpg"))
    } shouldBe 1

    testApp.service.asset.getById(asset.persistedId).fileName shouldBe "renamed.jpg"
    testApp.service.asset.getById(other.persistedId).fileName shouldBe other.fileName
  }

  test("A NULL bound in a hand-written statement fits a column of any type") {

    /**
     * Setup:
     *
     * A table of a wall-clock timestamp, an instant, an integer, a big integer, a vector, a boolean, a text and a double column,
     * a row inserted with every value NULL, then updated to NULL again, through the hand-written write.
     *
     * Assertions:
     *
     * Both statements run and the row reads back with every column NULL: a NULL is bound with no type, which the database takes
     * from the column, so a hand-written statement needs no type for it and no round trip to ask for one.
     */
    val isPostgres = testApp.dataSourceType == Const.DbEngineName.POSTGRES
    val (vectorType, vectorPlaceholder) = if (isPostgres) ("vector(3)", "?::vector") else ("BLOB", "?")
    val columns = List(
      "at_time" -> "TIMESTAMP",
      "at_instant" -> (if (isPostgres) "TIMESTAMPTZ" else "TEXT"),
      "n" -> "INTEGER",
      "big" -> "BIGINT",
      "embedding" -> vectorType,
      "flag" -> "BOOLEAN",
      "label" -> "TEXT",
      "score" -> "DOUBLE PRECISION"
    )
    val placeholders = columns.map((name, _) => if (name == "embedding") vectorPlaceholder else "?")
    val nulls = List.fill(columns.size)(null)
    val dao = testApp.DAO.user

    val rows = testApp.txManager.withTransaction {
      update(s"CREATE TEMP TABLE bound_nulls (${columns.map((name, kind) => s"$name $kind").mkString(", ")})")
      try {
        dao.updateByBySql(s"INSERT INTO bound_nulls VALUES (${placeholders.mkString(", ")})", nulls) shouldBe 1
        val assignments = columns.zip(placeholders).map { case ((name, _), placeholder) => s"$name = $placeholder" }
        dao.updateByBySql(s"UPDATE bound_nulls SET ${assignments.mkString(", ")}", nulls) shouldBe 1
        dao.manyBySqlQuery("SELECT * FROM bound_nulls")
      } finally update("DROP TABLE bound_nulls")
    }

    rows should have size 1
    rows.head.values.toList shouldBe nulls
  }

  /** Runs `f` in a write transaction over a temporary table of two text columns, which the transaction's end removes */
  private def withPairTable[T](f: => T): T =
    try
      testApp.txManager.withTransaction {
        update("CREATE TEMP TABLE IF NOT EXISTS pairs (a TEXT, b TEXT)")
        f
      }
    finally testApp.txManager.withTransaction(update("DROP TABLE IF EXISTS pairs"))

  test("A hand-written statement given fewer or more values than its placeholders fails, as does a batch row of another length") {

    /**
     * Setup:
     *
     * An insert of two placeholders run with one value and with three, and a batch of it whose second row holds one value.
     *
     * Assertions:
     *
     * Each fails with an `SQLException` naming the statement, rather than binding NULL for a missing value, failing on an index
     * out of bounds, or keeping the first row's second value for the second row.
     */
    val dao = testApp.DAO.user
    val sql = "INSERT INTO pairs VALUES (?, ?)"

    for (values <- List(List("x"), List("x", "y", "z"))) withClue(s"$values: ") {
      val error = intercept[SQLException](withPairTable(dao.updateByBySql(sql, values)))
      error.getMessage should include(sql)
    }
    val error = intercept[SQLException](withPairTable(dao.batchBySql(sql, List(List("a", "b"), List("c")))))
    error.getMessage should include(sql)
  }

  test("A failed batch names its statement, never the values bound to it") {

    /**
     * Setup:
     *
     * A batch of two inserts of a text and a divisor, the second dividing by zero, on PostgreSQL, whose driver reports a failed
     * batch with every entry's values inlined into the statement.
     *
     * Assertions:
     *
     * The batch fails with the server's error and the statement, and neither its message nor that of any exception it chains
     * holds a bound text.
     */
    assume(testApp.dataSourceType == Const.DbEngineName.POSTGRES)
    val sql = "INSERT INTO pairs VALUES (?, (1 / ?)::text)"

    val error = intercept[SQLException] {
      withPairTable(testApp.DAO.user.batchBySql(sql, List(List("secret-one", 1), List("secret-two", 0))))
    }

    error.getMessage should include("division by zero")
    error.getMessage should include(sql)
    Iterator
      .iterate[Throwable](error)(e => Option(e.getCause).orNull)
      .takeWhile(_ != null)
      .foreach(e => (Option(e.getMessage).getOrElse("") should not).include("secret-"))
  }

  test("A hand-written statement binds its values, NULL included, without asking the driver for their types") {

    /**
     * Setup:
     *
     * The transaction's connection wrapped so it counts the calls of `getParameterMetaData` on the statements it prepares, and a
     * hand-written read, write and batch, each binding a NULL, on PostgreSQL, where the call costs a describe round trip.
     *
     * Assertions:
     *
     * None of them asks for the parameter metadata.
     */
    assume(testApp.dataSourceType == Const.DbEngineName.POSTGRES)
    val asked = new AtomicInteger()

    def forward(target: AnyRef, method: Method, args: Array[AnyRef]): AnyRef =
      try method.invoke(target, Option(args).getOrElse(Array.empty[AnyRef])*)
      catch { case e: InvocationTargetException => throw e.getCause }

    def proxy[A](interface: Class[A])(handle: (Method, Array[AnyRef]) => AnyRef): A =
      Proxy
        .newProxyInstance(
          getClass.getClassLoader,
          Array(interface),
          new InvocationHandler {
            override def invoke(self: AnyRef, method: Method, args: Array[AnyRef]): AnyRef = handle(method, args)
          }
        )
        .asInstanceOf[A]

    def counting(conn: Connection): Connection = proxy(classOf[Connection]) {
      (method, args) =>
        forward(conn, method, args) match {
          case statement: PreparedStatement =>
            proxy(classOf[PreparedStatement]) {
              (method, args) =>
                if (method.getName == "getParameterMetaData") asked.incrementAndGet()
                forward(statement, method, args)
            }
          case other => other
        }
    }

    val dao = testApp.DAO.user
    withPairTable {
      RequestContext.conn.withValue(Some(counting(RequestContext.getConn))) {
        dao.updateByBySql("INSERT INTO pairs VALUES (?, ?)", List("a", null)) shouldBe 1
        dao.batchBySql("INSERT INTO pairs VALUES (?, ?)", List(List("b", null), List(null, "c"))) shouldBe Array(1, 1)
        dao.manyBySqlQuery("SELECT a FROM pairs WHERE a = ? AND b IS NOT DISTINCT FROM ?", List("a", null)) should have size 1
      }
    }

    asked.get shouldBe 0
  }

  test("A read by query that finds nothing names the query's fields, never their values") {

    /**
     * Setup:
     *
     * A read of one account by an email address no account has.
     *
     * Assertions:
     *
     * The `NotFoundException` names the table and the field read by, and not the address.
     */
    val email = s"nobody.${Util.randomStr(8)}@example.com"
    val error = intercept[NotFoundException] {
      testApp.txManager.asReadOnly(testApp.DAO.user.getOneByQuery(new Query().add(FieldConst.User.EMAIL -> email)))
    }

    error.getMessage should include("account")
    error.getMessage should include(FieldConst.User.EMAIL)
    (error.getMessage should not).include(email)
  }

  test("A unique violation is told apart by the index it breaks, as the failed statement or the driver reports it") {

    /**
     * Setup:
     *
     * A live asset, and a second asset of the same content added to the same repository, which `asset_01` refuses.
     *
     * Assertions:
     *
     * The failure is a violation of `asset_01` on its columns, whether read from the failed statement's exception or from the
     * driver's own, its cause. It is no violation of another index, nor of `asset_01` named with its columns in another order or
     * one of them alone.
     */
    val first = testContext.makeAsset()
    testApp.txManager.withTransaction(testApp.DAO.asset.add(first))
    val failed = intercept[SQLException] {
      testApp.txManager.withTransaction(testApp.DAO.asset.add(testContext.makeAsset().copy(checksum = first.checksum)))
    }
    val driver = failed.getCause.asInstanceOf[SQLException]

    for (e <- List(failed, driver)) {
      BaseDao.violates(e, AssetDao.LIVE_CHECKSUM_INDEX) shouldBe true
      BaseDao.isUniqueViolation(e) shouldBe true
      BaseDao.violates(e, UserDao.USERNAME_KEY_INDEX) shouldBe false
      BaseDao.violates(e, UniqueIndex("asset_01x", "asset.checksum, asset.repository_id")) shouldBe false
      BaseDao.violates(e, UniqueIndex("asset_0", "asset.checksum")) shouldBe false
    }
  }

  test("A unique index on an expression is told apart by its name, which SQLite reports in place of its columns") {

    /**
     * Setup:
     *
     * SQLite's failure of a unique index on an expression, as sqlite-jdbc reports it (`index '<name>'` in place of the columns),
     * alone and as the cause of a failed statement's exception.
     *
     * Assertions:
     *
     * Each is a unique violation of the index of that name, whatever columns the index is said to have, and of no other.
     *
     * Edge cases:
     *
     * Built rather than caused: no schema of the suite has such an index, and a table created for the test would change the
     * schema under every pooled connection of the suite's shared database.
     */
    val driver = new SQLiteException(
      "[SQLITE_CONSTRAINT_UNIQUE] A UNIQUE constraint failed (UNIQUE constraint failed: index 'scratch_01')",
      SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE)
    val failed = BaseDao.statementFailed("INSERT INTO scratch (value) VALUES (?)", driver)

    for (e <- List(driver, failed)) {
      BaseDao.violates(e, UniqueIndex("scratch_01", "not used")) shouldBe true
      BaseDao.isUniqueViolation(e) shouldBe true
      BaseDao.violates(e, UniqueIndex("scratch_02", "scratch.value")) shouldBe false
    }
  }

  test("A failure that breaks no unique index is no unique violation") {

    /**
     * Setup:
     *
     * An asset added with no checksum, which the column's NOT NULL refuses.
     *
     * Assertions:
     *
     * The failure is no unique violation, so no write reports it as a duplicate.
     *
     * Edge cases:
     *
     * SQLite gives every constraint failure the error code of a unique one (19); only its extended code tells them apart.
     */
    val failed = intercept[SQLException] {
      testApp.txManager.withTransaction(testApp.DAO.asset.add(testContext.makeAsset().copy(checksum = null)))
    }

    BaseDao.isUniqueViolation(failed) shouldBe false
    BaseDao.newDuplicateExceptionOrRethrow(failed) shouldBe failed
  }

  test("Each unique index a violation is told apart by is a unique index of the schema, on the columns it names") {

    /**
     * Setup:
     *
     * Every `UniqueIndex` the DAOs define.
     *
     * Assertions:
     *
     * Each names a unique index of the schema, on its table's columns in the order it lists them, as the engine's catalog reads
     * them: `pragma_index_info` on SQLite, `pg_index` on PostgreSQL.
     */
    val defined =
      List(UserDao.EMAIL_KEY_INDEX, UserDao.USERNAME_KEY_INDEX, AssetDao.LIVE_CHECKSUM_INDEX, RepositoryDao.NAME_KEY_INDEX)
    val isPostgres = testApp.dataSourceType == Const.DbEngineName.POSTGRES

    for (index <- defined) {
      withClue(s"[${index.name}]: ") {
        val (columns, isUnique) = testApp.txManager.asReadOnly {
          if (isPostgres) {
            val rows = query(
              """SELECT t.relname || '.' || a.attname AS column_name, i.indisunique AS is_unique
                |  FROM pg_class ic
                |  JOIN pg_index i ON i.indexrelid = ic.oid
                |  JOIN pg_class t ON t.oid = i.indrelid
                | CROSS JOIN LATERAL unnest(i.indkey::int2[]) WITH ORDINALITY AS k(attnum, ord)
                |  JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum
                | WHERE ic.relname = ? AND ic.relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = current_schema())
                | ORDER BY k.ord""".stripMargin,
              index.name
            )
            (rows.map(_("column_name").toString), rows.forall(_("is_unique") == java.lang.Boolean.TRUE) && rows.nonEmpty)
          } else {
            val table = query("SELECT tbl_name FROM sqlite_master WHERE type = 'index' AND name = ?", index.name)
              .map(_("tbl_name").toString)
            val columns = query("SELECT name FROM pragma_index_info(?) ORDER BY seqno", index.name)
              .flatMap(row => table.map(t => s"$t.${row("name")}"))
            val unique =
              table.flatMap(t => query("SELECT \"unique\" AS is_unique FROM pragma_index_list(?) WHERE name = ?", t, index.name))
            (columns, unique.map(_("is_unique").toString) == List("1"))
          }
        }

        columns.mkString(", ") shouldBe index.columns
        isUnique shouldBe true
      }
    }
  }
}
