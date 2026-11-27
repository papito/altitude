package altitude.core.integration

import altitude.test.{
  IntegrationTestUtil,
  ModelSwitch,
  SwitchableDescribeEmbedder,
  SwitchableSceneEmbedder,
  TestContext,
  TestFocus
}
import java.util.HexFormat
import java.util.Locale
import java.util.concurrent.{ CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.AtomicReference
import org.apache.commons.dbutils.QueryRunner
import org.apache.commons.dbutils.handlers.MapListHandler
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.util.Timeout
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.*
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers.shouldBe
import org.scalatest.time.{ Millis, Seconds, Span }
import org.slf4j.{ Logger, LoggerFactory }

import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.language.implicitConversions

import altitude.core.*
import altitude.core.dao.sqlite.SqliteOverrides
import altitude.core.models.*
import altitude.core.util.SortKey

abstract class IntegrationTestCore
  extends funsuite.AnyFunSuite
  with AltitudeTestApp
  with BeforeAndAfter
  with BeforeAndAfterEach
  with BeforeAndAfterAll
  with OptionValues
  with TestFocus {
  final protected val log: Logger = LoggerFactory.getLogger(getClass)

  var testContext: TestContext = new TestContext(testApp)

  implicit val scheduler: Scheduler = testApp.actorSystem.scheduler
  implicit val timeout: Timeout = 3.seconds

  def query(sql: String, values: Any*): List[Map[String, AnyRef]] = {
    val res =
      new QueryRunner()
        .query(RequestContext.getConn, sql, new MapListHandler(), values.map(_.asInstanceOf[Object])*)
        .asScala
        .toList

    res.map(_.asScala.toMap[String, AnyRef])
  }

  def update(sql: String, values: Any*): Unit = {
    new QueryRunner().update(RequestContext.getConn, sql, values.map(_.asInstanceOf[Object])*)
  }

  /** A timestamp in the engine's storage form; SQLite's is the canonical text every writer must produce */
  def getSqlDateTime(t: java.sql.Timestamp): Any = {
    testApp.dataSourceType match {
      case Const.DbEngineName.POSTGRES => t
      case Const.DbEngineName.SQLITE => t.toLocalDateTime.format(SqliteOverrides.DATETIME_FORMATTER)
      case _ => throw new IllegalArgumentException("Unsupported data source type")
    }
  }

  /**
   * Whether the suite's tests run the real Scene models. Off, a fixture import carries no Scene embedding, so its frames pair by
   * the hash alone, sparing about 0.75 s an import. The models open when the switch is first turned on, so one that fails to open
   * fails the suite or test with its own error.
   */
  protected def usesSceneModels: Boolean = false

  /** The test instance's Scene embedder, which the test bundles build switched off (`TestAltitudeApp.build`) */
  private def sceneModels: SwitchableSceneEmbedder = testApp.sceneEmbedder match {
    case switchable: SwitchableSceneEmbedder => switchable
    case other =>
      throw new IllegalStateException(
        s"The test instance's Scene embedder is a ${other.getClass.getName}, which cannot be switched: build the instance with " +
          "TestAltitudeApp.build")
  }

  /**
   * Whether the suite's tests embed descriptions with the real SigLIP 2 text tower. Off, a description has no embedding. The
   * tower opens on the first description embedded with the switch on.
   */
  protected def usesDescribeModel: Boolean = false

  /** The test instance's Describe embedder, which the test bundles build switched off (`TestAltitudeApp.build`) */
  private def describeModel: SwitchableDescribeEmbedder = testApp.describeEmbedder match {
    case switchable: SwitchableDescribeEmbedder => switchable
    case other =>
      throw new IllegalStateException(
        s"The test instance's Describe embedder is a ${other.getClass.getName}, which cannot be switched: build the instance " +
          "with TestAltitudeApp.build")
  }

  // The suites of a bundle run one after another, so the switches set here are this suite's alone until afterAll turns them off
  override def beforeAll(): Unit = {
    if (usesSceneModels) sceneModels.turnOn() else sceneModels.turnOff()
    if (usesDescribeModel) describeModel.turnOn() else describeModel.turnOff()
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    try super.afterAll()
    finally {
      sceneModels.turnOff()
      describeModel.turnOff()
    }
  }

  /** Runs `f` with the real Scene models on, for one test of a suite that leaves them off, and puts the switch back as it was */
  def withSceneModels[T](f: => T): T = withSwitchOn(sceneModels)(f)

  /** Runs `f` with the Scene embedder refusing every image as a closed one does, as during a stop whose drain ran out */
  def withSceneEmbedderClosed[T](f: => T): T = sceneModels.closedWhile(f)

  /** Runs `f` with the real Describe model on, for one test of a suite that leaves it off, and puts the switch back as it was */
  def withDescribeModel[T](f: => T): T = withSwitchOn(describeModel)(f)

  private def withSwitchOn[T](switch: ModelSwitch)(f: => T): T = {
    val was = switch.on
    switch.turnOn()
    try f
    finally if (!was) switch.turnOff()
  }

  override def beforeEach(): Unit = {
    testApp.clearState()
    testApp.isInitialized = false
    testContext = new TestContext(testApp)

    // A bundle's database keeps every test's accounts, and a new Library grants each account whose new-Library switch is on, so
    // no switch outlives its test. Every test starts on an instance not yet set up, in the database as in memory, so a test of
    // Setup is not refused for an earlier test's.
    testApp.txManager.withTransaction {
      new QueryRunner().update(RequestContext.getConn, "UPDATE account SET grants_new_libraries = ?", Boolean.box(false))
      testApp.DAO.systemMetadata.setUninitialized()
    }

    // One repository and one account (a User with no grant), since every asset has an owner
    testContext.persistRepository()
    // Every Permission, as an Admin's request has; a test of a rule that reads them narrows them with
    // `RequestContext.permissions.withValue`
    RequestContext.permissions.value = Permissions.all

    // nuke the data dir tree
    IntegrationTestUtil.createFileStoreDir(testApp)
  }

  /**
   * The IDs among more unknown ones than a PostgreSQL statement takes parameters for (65,535): a selection that only a statement
   * binding its ID set as one value can run
   */
  def amongManyUnknownIds(ids: Set[String]): Set[String] =
    ids ++ (1 to 70000).map(n => f"unknown-$n%028d")

  /** Replaces the repository's `files` directory with a plain file, so storing any asset of the repository fails */
  def breakFileStore(repository: Repository): Unit = {
    val files =
      java.nio.file.Paths.get(testApp.dataPath, Const.DataStore.REPOSITORIES, repository.persistedId, Const.DataStore.FILES)
    org.apache.commons.io.FileUtils.deleteDirectory(files.toFile)
    java.nio.file.Files.createDirectories(files.getParent)
    java.nio.file.Files.createFile(files): Unit
  }

  /** Puts the repository's `files` directory back after [[breakFileStore]] */
  def restoreFileStore(repository: Repository): Unit =
    java.nio.file.Files.delete(
      java.nio.file.Paths.get(testApp.dataPath, Const.DataStore.REPOSITORIES, repository.persistedId, Const.DataStore.FILES))

  def switchContextUser(user: User): Unit = {
    testApp.service.user.switchContextToUser(user)
  }

  def switchContextRepo(repository: Repository): Unit = {
    testApp.service.repository.switchContextToRepository(repository)
  }

  /**
   * Starts `f` on a thread of its own, which inherits the test's repository and account but not its transaction. Returns the
   * thread and what `f` threw, which is set by the time the thread is joined.
   */
  protected def onAnotherThread(f: => Unit): (Thread, AtomicReference[Option[Throwable]]) = {
    val failure = new AtomicReference[Option[Throwable]](None)
    val thread = new Thread(
      () =>
        try f
        catch { case ex: Throwable => failure.set(Some(ex)) })
    thread.start()
    (thread, failure)
  }

  /**
   * Runs `first` and `second` on threads of their own, each in its transaction, `first` holding its open until `second` waits on
   * it: for a row lock on PostgreSQL (seen by backend PID), for the one write connection on SQLite (a thread parked in the pool).
   */
  def contend(first: => Unit, second: => Unit): Unit = {
    val holding = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val secondBackend = new AtomicReference[Option[AnyRef]](None)

    val (firstThread, firstFailure) = onAnotherThread {
      testApp.txManager.withTransaction {
        first
        holding.countDown()
        release.await(30, TimeUnit.SECONDS): Unit
      }
    }

    try {
      // A first that failed never holds: its failure is the reason, where the timeout alone would say nothing
      val held = holding.await(30, TimeUnit.SECONDS)
      firstFailure.get.foreach(throw _)
      held shouldBe true

      val (secondThread, secondFailure) = onAnotherThread {
        testApp.txManager.withTransaction {
          if (testApp.dataSourceType == Const.DbEngineName.POSTGRES) secondBackend.set(Some(backendPid()))
          second
        }
      }

      def secondIsWaiting: Boolean = testApp.dataSourceType match {
        case Const.DbEngineName.POSTGRES => secondBackend.get.exists(waitsOnLock)
        case _ => secondThread.getState == Thread.State.TIMED_WAITING || secondThread.getState == Thread.State.WAITING
      }

      Eventually.eventually(Eventually.timeout(Span(30, Seconds)), Eventually.interval(Span(20, Millis))) {
        secondIsWaiting shouldBe true
      }
      release.countDown()
      secondThread.join()
      secondFailure.get.foreach(throw _)
    } finally {
      release.countDown()
      firstThread.join()
    }
    firstFailure.get.foreach(throw _)
  }

  /**
   * On PostgreSQL, an interleaving of more writers than [[contend]] takes: runs `first` in a transaction of its own and holds it
   * open, then starts each of `writers` in order, each on a thread of its own in a transaction of its own, once the one before
   * waits on a lock or has ended. Then lets `first` commit, waits for every writer and rethrows the first failure.
   */
  def whileHolding(first: => Unit)(writers: (() => Unit)*): Unit = {
    require(testApp.dataSourceType == Const.DbEngineName.POSTGRES, "Row locks to wait on are PostgreSQL's")
    val holding = new CountDownLatch(1)
    val release = new CountDownLatch(1)

    val (holder, holderFailure) = onAnotherThread {
      testApp.txManager.withTransaction {
        first
        holding.countDown()
        release.await(30, TimeUnit.SECONDS): Unit
      }
    }

    try {
      val held = holding.await(30, TimeUnit.SECONDS)
      holderFailure.get.foreach(throw _)
      held shouldBe true

      val started = writers.map {
        writer =>
          val backend = new AtomicReference[Option[AnyRef]](None)
          val (thread, failure) = onAnotherThread {
            testApp.txManager.withTransaction {
              backend.set(Some(backendPid()))
              writer()
            }
          }
          Eventually.eventually(Eventually.timeout(Span(30, Seconds)), Eventually.interval(Span(20, Millis))) {
            (backend.get.exists(waitsOnLock) || !thread.isAlive) shouldBe true
          }
          (thread, failure)
      }

      release.countDown()
      started.foreach(_._1.join())
      started.foreach(_._2.get.foreach(throw _))
    } finally {
      release.countDown()
      holder.join()
    }
    holderFailure.get.foreach(throw _)
  }

  /** The context repository's Faces whose Person is merged away: none, as no path leaves a Face on one */
  def facesOfPeopleMergedAway: List[Map[String, AnyRef]] =
    testApp.txManager.asReadOnly {
      query(
        "SELECT face.id FROM face JOIN person ON person.id = face.person_id WHERE face.repository_id = ? AND person.is_deleted = ?",
        testContext.repository.persistedId,
        true
      )
    }

  /** The PostgreSQL backend of the calling thread's transaction, for another thread to watch with [[waitsOnLock]] */
  def backendPid(): AnyRef = query("SELECT pg_backend_pid() AS pid").head("pid")

  /** Whether the PostgreSQL backend waits on a lock. pg_stat_activity is read once per transaction, so each call is one. */
  def waitsOnLock(pid: AnyRef): Boolean =
    testApp.txManager.asReadOnly {
      query("SELECT wait_event_type FROM pg_stat_activity WHERE pid = ?", pid).exists(_("wait_event_type") == "Lock")
    }

  /** How many PostgreSQL backends of the test database wait on a lock, for work running on threads the test cannot name */
  def backendsWaitingOnLocks: Int =
    testApp.txManager.asReadOnly {
      query("SELECT count(*) AS n FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()")
        .head("n")
        .toString
        .toInt
    }

  /** The advisory key [[pausingInserts]] waits on, which a holder takes with `SELECT pg_advisory_xact_lock(...)` */
  val INSERT_PAUSE_KEY = 4242

  /**
   * On PostgreSQL, until the cleanup runs: an insert into `table` of the row whose `asset_id` the inserting transaction names in
   * `test.pause_on` (`SET LOCAL`) first waits for a shared hold of [[INSERT_PAUSE_KEY]], so a holder of the key stops a multi-row
   * insert between two of its rows
   */
  protected def pausingInserts(table: String): () => Unit = {
    require(testApp.dataSourceType == Const.DbEngineName.POSTGRES, "Advisory locks to wait on are PostgreSQL's")
    testApp.txManager.withTransaction {
      update(
        "CREATE FUNCTION paused_by_test() RETURNS trigger AS $$ BEGIN " +
          "IF NEW.asset_id = current_setting('test.pause_on', true) " +
          s"THEN PERFORM pg_advisory_xact_lock_shared($INSERT_PAUSE_KEY); END IF; RETURN NEW; END $$$$ LANGUAGE plpgsql")
      update(s"CREATE TRIGGER paused_by_test BEFORE INSERT ON $table FOR EACH ROW EXECUTE FUNCTION paused_by_test()")
    }
    () =>
      testApp.txManager.withTransaction {
        update(s"DROP TRIGGER paused_by_test ON $table")
        update("DROP FUNCTION paused_by_test()")
      }
  }

  /**
   * Two of the context repository's assets, the first of a higher ID but earlier in the table's physical order, so a sequential
   * scan reaches them in the opposite order of an ID-ordered read; assets are imported until two are so
   */
  def assetsOutOfIdOrder(): (Asset, Asset) = {
    val assets = scala.collection.mutable.ListBuffer[Asset]()
    def pair: Option[(String, String)] = {
      val physical = testApp.txManager.asReadOnly {
        query(
          "SELECT id FROM asset WHERE id = ANY(?) ORDER BY ctid",
          RequestContext.getConn.createArrayOf("varchar", assets.map(_.persistedId).toArray[AnyRef])).map(_("id").toString)
      }
      physical.combinations(2).collectFirst { case List(first, second) if first > second => (first, second) }
    }
    while (pair.isEmpty) {
      require(assets.size < 20, "No two assets out of ID order")
      assets += testContext.persistAsset()
    }
    val (first, second) = pair.get
    (assets.find(_.persistedId == first).get, assets.find(_.persistedId == second).get)
  }

  /** Has the database refuse `event` (`DELETE`, `UPDATE OF <column>`) on the row of `table` with the ID until the cleanup runs */
  protected def refuse(event: String, table: String, rowId: String): () => Unit = {
    val (create, drop) = testApp.dataSourceType match {
      case Const.DbEngineName.POSTGRES =>
        (
          List(
            "CREATE FUNCTION refused_by_test() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'Refused by the test'; END $$ " +
              "LANGUAGE plpgsql",
            s"CREATE TRIGGER refused_by_test BEFORE $event ON $table FOR EACH ROW WHEN (OLD.id = '$rowId') " +
              "EXECUTE FUNCTION refused_by_test()"
          ),
          List(s"DROP TRIGGER refused_by_test ON $table", "DROP FUNCTION refused_by_test()"))
      case _ =>
        (
          List(
            s"CREATE TRIGGER refused_by_test BEFORE $event ON $table WHEN OLD.id = '$rowId' " +
              "BEGIN SELECT RAISE(ABORT, 'Refused by the test'); END"),
          List("DROP TRIGGER refused_by_test"))
    }

    testApp.txManager.withTransaction(create.foreach(update(_)))
    () => testApp.txManager.withTransaction(drop.foreach(update(_)))
  }

  /** Runs `f` with the Setting saved as `value`, and saves its default back afterwards, so the other suites see the seed */
  def withSetting[T](name: String, value: String)(f: => T): T = {
    testApp.settings.save(Map(name -> value))
    try f
    finally testApp.settings.save(Map(name -> testApp.settings.current.default(name)))
  }

  /**
   * A Location group's path key as the engine spells it (`SearchDialect.pathKey`): the sort keys of its category's name and its
   * own, or its own alone, in the engine's hex
   */
  def pathKeyOf(names: String*): String = {
    val hex = if (testApp.dataSourceType == Const.DbEngineName.POSTGRES) HexFormat.of() else HexFormat.of().withUpperCase()
    names.map(name => hex.formatHex(SortKey.of(name).bytes)).mkString
  }

  /** Runs `f` with the JVM's default locale set to `locale`, and sets the previous one back afterwards */
  def withDefaultLocale[T](locale: Locale)(f: => T): T = {
    val previous = Locale.getDefault
    Locale.setDefault(locale)
    try f
    finally Locale.setDefault(previous)
  }

  /**
   * A name of `length` UTF-16 units, as a form counts it, that is three times as long in NFC: U+FB2C, a composition exclusion, is
   * three code points once normalized
   */
  def lengthenedByNfc(length: Int): String = "\uFB2C" * length

  /** A name of `length` characters whose name key is 33 UTF-8 bytes a character: NFKC spells U+FDFA out as 18 code points */
  def longKeyName(length: Int): String = "\uFDFA" * length

  /** Runs `f`, which must refuse a name with a `ValidationException` saying `message`, its own and the name field's (`field`) */
  def refusesName(field: String, message: String)(f: => Any): Unit = {
    val ex = intercept[ValidationException](f)
    ex.errors.get(field) shouldBe Some(message)
    ex.message shouldBe message
  }

  /** The context repository's six stored stats by dimension, without the totals, which are summed on read */
  def storedStats: Map[String, Long] =
    testApp.service.stats.getStats.stats
      .filterNot(stat => stat.dimension == Stats.TOTAL_ASSETS || stat.dimension == Stats.TOTAL_BYTES)
      .map(stat => stat.dimension -> stat.dimVal)
      .toMap
}
