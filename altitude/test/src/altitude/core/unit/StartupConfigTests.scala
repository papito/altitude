package altitude.core.unit

import altitude.test.TestFocus
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.apache.commons.io.FileUtils
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers.*
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters.*

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.Environment
import altitude.core.util.ImportParallelism

/**
 * The config a dev or prod start reads (`Altitude.loadConfig`: the `ALTITUDE_` variables over the config file over
 * `reference.conf`) and what the start makes of it before anything opens: the Data directory, the PostgreSQL password, and the
 * disk SQLite is on. No test reads the process's own environment: each passes the variables it means.
 */
@DoNotDiscover class StartupConfigTests extends funsuite.AnyFunSuite with TestFocus {

  private val PROD = Environment.Name.PROD
  private val DEV = Environment.Name.DEV
  private val noVariables = Map.empty[String, String]

  /** Runs `f` over a scratch folder standing for the jar's folder, deleted afterwards */
  private def withRoot[T](f: Path => T): T = {
    val root = Files.createTempDirectory("altitude-config-test")
    try f(root)
    finally FileUtils.deleteDirectory(root.toFile)
  }

  private def write(file: Path, text: String): Path = Files.writeString(file, text)

  /** The WARN messages `ImportParallelism` logs while the block runs */
  private def parallelismWarnings(block: => Unit): Seq[String] = {
    val logger = LoggerFactory.getLogger(ImportParallelism.getClass).asInstanceOf[Logger]
    val appender = ListAppender[ILoggingEvent]()
    val level = logger.getLevel
    appender.start()
    logger.addAppender(appender)
    logger.setLevel(Level.WARN)
    logger.setAdditive(false)
    try block
    finally {
      logger.setAdditive(true)
      logger.detachAppender(appender)
      logger.setLevel(level)
    }
    appender.list.asScala.toSeq.filter(_.getLevel == Level.WARN).map(_.getFormattedMessage)
  }

  test("Prod reads the config file ALTITUDE_CONFIG names") {

    /**
     * Setup:
     *
     * The jar's folder with an `application.conf` setting one port, and a file elsewhere setting another, which `ALTITUDE_CONFIG`
     * names by its absolute path.
     *
     * Assertions:
     *
     * The named file's port is read and the folder's own `application.conf` is not.
     *
     * Edge cases:
     *
     * A relative `ALTITUDE_CONFIG` is under the jar's folder, as every relative path of the config is. An empty one counts as
     * unset, so the folder's `application.conf` is read.
     */
    withRoot {
      root =>
        write(root.resolve("application.conf"), "server.port=9001")
        val elsewhere = Files.createDirectory(root.resolve("elsewhere"))
        val named = write(elsewhere.resolve("mine.conf"), "server.port=9002")

        val absolute = Altitude.loadConfig(PROD, Map(Environment.Variable.CONFIG -> named.toString), root.toString)
        absolute.getInt(Const.Conf.SERVER_PORT) shouldBe 9002

        val relative = Altitude.loadConfig(PROD, Map(Environment.Variable.CONFIG -> "elsewhere/mine.conf"), root.toString)
        relative.getInt(Const.Conf.SERVER_PORT) shouldBe 9002

        val empty = Altitude.loadConfig(PROD, Map(Environment.Variable.CONFIG -> ""), root.toString)
        empty.getInt(Const.Conf.SERVER_PORT) shouldBe 9001
    }
  }

  test("A config file ALTITUDE_CONFIG names that does not exist fails the start") {

    /**
     * Setup:
     *
     * The jar's folder with an `application.conf`, and `ALTITUDE_CONFIG` naming a file that does not exist, then a folder.
     *
     * Assertions:
     *
     * Loading the config throws, naming the variable and the missing file, rather than falling back to the folder's own file or
     * to the defaults.
     */
    withRoot {
      root =>
        write(root.resolve("application.conf"), "server.port=9001")
        val missing = root.resolve("missing.conf")

        val failure = the[IllegalStateException] thrownBy
          Altitude.loadConfig(PROD, Map(Environment.Variable.CONFIG -> missing.toString), root.toString)
        failure.getMessage should include(Environment.Variable.CONFIG)
        failure.getMessage should include(missing.toString)

        an[IllegalStateException] should be thrownBy
          Altitude.loadConfig(PROD, Map(Environment.Variable.CONFIG -> root.toString), root.toString)
    }
  }

  test("With no ALTITUDE_CONFIG, prod reads application.conf in the jar's folder, and the defaults when there is none") {

    /**
     * Setup:
     *
     * Two folders standing for the jar's: one with an `application.conf` that sets the host, one empty. Neither is the working
     * directory.
     *
     * Assertions:
     *
     * The first folder's file is read wherever the process was started from. The empty folder gives the defaults of
     * `reference.conf`, with no failure.
     *
     * Edge cases:
     *
     * Prod does not read `application-dev.conf`.
     */
    withRoot {
      root =>
        write(root.resolve("application.conf"), "server.host=10.0.0.5")
        write(root.resolve("application-dev.conf"), "server.host=dev.example\nserver.port=9003")

        root.toAbsolutePath should not be Path.of("").toAbsolutePath
        val config = Altitude.loadConfig(PROD, noVariables, root.toString)
        config.getString(Const.Conf.SERVER_HOST) shouldBe "10.0.0.5"
        config.getInt(Const.Conf.SERVER_PORT) shouldBe 8080
    }

    withRoot {
      root =>
        val defaults = Altitude.loadConfig(PROD, noVariables, root.toString)
        val reference = ConfigFactory.defaultReference()
        Seq(Const.Conf.SERVER_HOST, Const.Conf.FS_DATA_DIR, Const.Conf.DB_ENGINE, Const.Conf.POSTGRES_URL).foreach {
          key => defaults.getString(key) shouldBe reference.getString(key)
        }
        defaults.getString(Const.Conf.SERVER_HOST) shouldBe "localhost"
        defaults.getInt(Const.Conf.SERVER_PORT) shouldBe 8080
        defaults.getBoolean(Const.Conf.SERVER_SECURE_COOKIES) shouldBe true
    }
  }

  test("Dev reads application-dev.conf in its root, whatever ALTITUDE_CONFIG says") {

    /**
     * Setup:
     *
     * A root folder with an `application-dev.conf`, an `application.conf` and a third file that `ALTITUDE_CONFIG` names, each
     * setting another port.
     *
     * Assertions:
     *
     * Dev reads `application-dev.conf` alone.
     *
     * Edge cases:
     *
     * With no `application-dev.conf`, dev runs on the defaults.
     */
    withRoot {
      root =>
        write(root.resolve("application.conf"), "server.port=9001")
        val named = write(root.resolve("mine.conf"), "server.port=9002")
        val variables = Map(Environment.Variable.CONFIG -> named.toString)

        Altitude.loadConfig(DEV, variables, root.toString).getInt(Const.Conf.SERVER_PORT) shouldBe 8080

        write(root.resolve("application-dev.conf"), "server.port=9003")
        Altitude.loadConfig(DEV, variables, root.toString).getInt(Const.Conf.SERVER_PORT) shouldBe 9003
    }
  }

  test("A variable wins over a key of the config file, and a key of the file over reference.conf") {

    /**
     * Setup:
     *
     * A config file that sets the host, the port and the database engine, and the variables `ALTITUDE_HOST` and
     * `ALTITUDE_DB_ENGINE`.
     *
     * Assertions:
     *
     * The host and the engine are the variables', the port is the file's, and a key neither sets keeps the value of
     * `reference.conf`.
     *
     * Edge cases:
     *
     * Without the variables the file's host and engine are read. The variables themselves are no keys of the config.
     */
    withRoot {
      root =>
        write(root.resolve("application.conf"), "server.host=localhost\nserver.port=9001\ndb.engine=sqlite")
        val variables = Map("ALTITUDE_HOST" -> "0.0.0.0", "ALTITUDE_DB_ENGINE" -> "postgres")

        val config = Altitude.loadConfig(PROD, variables, root.toString)
        config.getString(Const.Conf.SERVER_HOST) shouldBe "0.0.0.0"
        config.getString(Const.Conf.DB_ENGINE) shouldBe "postgres"
        config.getInt(Const.Conf.SERVER_PORT) shouldBe 9001
        config.getString(Const.Conf.FS_DATA_DIR) shouldBe ConfigFactory.defaultReference().getString(Const.Conf.FS_DATA_DIR)
        config.getBoolean(Const.Conf.SERVER_SECURE_COOKIES) shouldBe true

        val ofFile = Altitude.loadConfig(PROD, noVariables, root.toString)
        ofFile.getString(Const.Conf.SERVER_HOST) shouldBe "localhost"
        ofFile.getString(Const.Conf.DB_ENGINE) shouldBe "sqlite"

        variables.keys.foreach(name => config.hasPath(name) shouldBe false)
    }
  }

  test("Each variable of the configuration sets its key") {

    /**
     * Setup:
     *
     * The ten variables that set a config key, each given a value unlike the key's default, with no config file.
     *
     * Assertions:
     *
     * Each key reads its variable's value, the port, the flag and the cap in their own types.
     *
     * Edge cases:
     *
     * `ALTITUDE_ENV` and `ALTITUDE_CONFIG`, which are read in code, set no key, and neither does a variable the configuration
     * does not name.
     */
    val variables = Map(
      "ALTITUDE_HOST" -> "0.0.0.0",
      "ALTITUDE_PORT" -> "9090",
      "ALTITUDE_SECURE_COOKIES" -> "false",
      "ALTITUDE_DATA_DIR" -> "/data",
      "ALTITUDE_DB_ENGINE" -> "postgres",
      "ALTITUDE_DB_POSTGRES_URL" -> "jdbc:postgresql://postgres/photos",
      "ALTITUDE_DB_POSTGRES_USER" -> "photos",
      "ALTITUDE_DB_POSTGRES_PASSWORD" -> "s3cret",
      "ALTITUDE_IMPORT_PARALLELISM_MAX" -> "3",
      "ALTITUDE_MODELS_DIR" -> "/models",
      "ALTITUDE_ENV" -> "prod",
      "ALTITUDE_UNKNOWN" -> "ignored"
    )

    withRoot {
      root =>
        val config = Altitude.loadConfig(PROD, variables, root.toString)

        config.getString(Const.Conf.SERVER_HOST) shouldBe "0.0.0.0"
        config.getInt(Const.Conf.SERVER_PORT) shouldBe 9090
        config.getBoolean(Const.Conf.SERVER_SECURE_COOKIES) shouldBe false
        config.getString(Const.Conf.FS_DATA_DIR) shouldBe "/data"
        config.getString(Const.Conf.DB_ENGINE) shouldBe "postgres"
        config.getString(Const.Conf.POSTGRES_URL) shouldBe "jdbc:postgresql://postgres/photos"
        config.getString(Const.Conf.POSTGRES_USER) shouldBe "photos"
        config.getString(Const.Conf.POSTGRES_PASSWORD) shouldBe "s3cret"
        config.getInt(Const.Conf.IMPORT_PARALLELISM_MAX) shouldBe 3
        ImportParallelism.configuredCap(config) shouldBe 3
        config.getString(Const.Conf.MODELS_DIR) shouldBe "/models"

        // Nothing but the keys above differs from a start with no variable at all. The Models directory is left out of the
        // comparison: reference.conf reads its variable from the process itself, which may set it for the test run.
        val plain = Altitude.loadConfig(PROD, noVariables, root.toString)
        val changed = config.entrySet.asScala.filterNot(entry => plain.getValue(entry.getKey) == entry.getValue).map(_.getKey)
        changed.toSet - Const.Conf.MODELS_DIR shouldBe Set(
          Const.Conf.SERVER_HOST,
          Const.Conf.SERVER_PORT,
          Const.Conf.SERVER_SECURE_COOKIES,
          Const.Conf.FS_DATA_DIR,
          Const.Conf.DB_ENGINE,
          Const.Conf.POSTGRES_URL,
          Const.Conf.POSTGRES_USER,
          Const.Conf.POSTGRES_PASSWORD,
          Const.Conf.IMPORT_PARALLELISM_MAX
        )
        config.entrySet.size shouldBe plain.entrySet.size
    }
  }

  test("A variable that is not set leaves the file's key as the file has it") {

    /**
     * Setup:
     *
     * A config file with a leftover number at `import.parallelism`, the path the cap's own key lies under, first with no variable
     * and then with `ALTITUDE_IMPORT_PARALLELISM_MAX` set.
     *
     * Assertions:
     *
     * With no variable the leftover is still seen as one: no cap applies and the WARN about it is logged, as with no environment
     * layer at all. With the variable the cap is the variable's.
     *
     * Edge cases:
     *
     * A substitution in the file is resolved with the rest of the config, a key's own earlier value included.
     */
    withRoot {
      root =>
        write(
          root.resolve("application.conf"),
          """import.parallelism=4
            |db.postgres.options=${db.postgres.options}" -c statement_timeout=0"
            |""".stripMargin
        )

        val config = Altitude.loadConfig(PROD, noVariables, root.toString)
        var cap = -1
        val warned = parallelismWarnings { cap = ImportParallelism.configuredCap(config) }
        cap shouldBe 0
        warned should have size 1
        warned.head should include(Const.Conf.IMPORT_PARALLELISM_MAX)

        val capped = Altitude.loadConfig(PROD, Map("ALTITUDE_IMPORT_PARALLELISM_MAX" -> "6"), root.toString)
        ImportParallelism.configuredCap(capped) shouldBe 6

        val options = ConfigFactory.defaultReference().getString(Const.Conf.POSTGRES_OPTIONS)
        config.getString(Const.Conf.POSTGRES_OPTIONS) shouldBe s"$options -c statement_timeout=0"
    }
  }

  test("A config file that cannot be parsed fails the start") {

    /**
     * Setup:
     *
     * An `application.conf` in the jar's folder that is not HOCON.
     *
     * Assertions:
     *
     * Loading the config throws a parse error naming the file, rather than running on the defaults.
     */
    withRoot {
      root =>
        val file = write(root.resolve("application.conf"), "server.port=[")

        val failure = the[ConfigException] thrownBy Altitude.loadConfig(PROD, noVariables, root.toString)
        failure.getMessage should include(file.getFileName.toString)
    }
  }

  test("An absolute fs.data.dir is the Data directory as it is, and a relative one is under the jar's folder") {

    /**
     * Setup:
     *
     * A root path standing for the jar's folder, and the Data directory configured as a relative path, as the default, and as an
     * absolute path, the last also through `ALTITUDE_DATA_DIR`.
     *
     * Assertions:
     *
     * The relative paths resolve under the root path; the absolute one is used unchanged, wherever the root path is.
     */
    val root = new File(File.separator + "opt", "altitude").getAbsolutePath
    val absolute = new File(File.separator + "srv", "photos").getAbsolutePath
    def configOf(dir: String) = ConfigFactory.parseMap(Map(Const.Conf.FS_DATA_DIR -> dir).asJava)

    Altitude.dataPathOf(configOf("data"), root) shouldBe new File(root, "data").getPath
    Altitude.dataPathOf(configOf("var/altitude"), root) shouldBe new File(new File(root, "var"), "altitude").getPath
    Altitude.dataPathOf(ConfigFactory.defaultReference(), root) shouldBe new File(root, "data").getPath

    Altitude.dataPathOf(configOf(absolute), root) shouldBe absolute

    withRoot {
      jarFolder =>
        val config = Altitude.loadConfig(PROD, Map("ALTITUDE_DATA_DIR" -> absolute), jarFolder.toString)
        Altitude.dataPathOf(config, jarFolder.toString) shouldBe absolute
    }
  }

  test("PostgreSQL with an empty password fails the start, naming ALTITUDE_DB_POSTGRES_PASSWORD") {

    /**
     * Setup:
     *
     * The config of a start on PostgreSQL whose password variable is set but empty, as compose passes it when the `.env` file
     * sets none.
     *
     * Assertions:
     *
     * The check throws, and its message names the variable and the config key.
     *
     * Edge cases:
     *
     * A password that is set passes. On SQLite an empty PostgreSQL password is no failure: it is never used.
     */
    withRoot {
      root =>
        val onPostgres = Map("ALTITUDE_DB_ENGINE" -> "postgres")

        val empty = Altitude.loadConfig(PROD, onPostgres + ("ALTITUDE_DB_POSTGRES_PASSWORD" -> ""), root.toString)
        val failure = the[IllegalStateException] thrownBy Altitude.requirePostgresPassword(empty)
        failure.getMessage should include("ALTITUDE_DB_POSTGRES_PASSWORD")
        failure.getMessage should include(Const.Conf.POSTGRES_PASSWORD)

        val set = Altitude.loadConfig(PROD, onPostgres + ("ALTITUDE_DB_POSTGRES_PASSWORD" -> "s3cret"), root.toString)
        noException should be thrownBy Altitude.requirePostgresPassword(set)

        val onSqlite =
          Altitude.loadConfig(PROD, Map("ALTITUDE_DB_ENGINE" -> "sqlite", "ALTITUDE_DB_POSTGRES_PASSWORD" -> ""), root.toString)
        noException should be thrownBy Altitude.requirePostgresPassword(onSqlite)
    }
  }

  test("SQLite on a network share or a Docker Desktop host folder is warned about") {

    /**
     * Setup:
     *
     * The file store types of NFS, SMB, 9p, virtiofs and FUSE mounts, and those of local disks, each as the type of the SQLite
     * database's folder.
     *
     * Assertions:
     *
     * Each network or host-folder type has a warning that names the folder and the type; a local disk has none.
     *
     * Edge cases:
     *
     * Every `fuse` type warns, whatever follows the prefix, and a type is recognised in any case. A type that merely contains one
     * of the names does not warn.
     */
    val dir = Path.of(File.separator + "data", "db")

    Seq("nfs", "nfs4", "cifs", "smb3", "9p", "virtiofs", "fuse", "fuse.sshfs", "fuse.grpcfuse", "fuseblk", "NFS4", "Fuse.osxfs")
      .foreach {
        fileStoreType =>
          withClue(s"[$fileStoreType]: ") {
            val warning = Altitude.sqliteDiskWarning(dir, fileStoreType)
            warning should not be empty
            warning.get should include(dir.toString)
            warning.get should include(fileStoreType)
          }
      }

    Seq("ext4", "xfs", "btrfs", "zfs", "overlay", "tmpfs", "apfs", "NTFS", "nfsd", "ext4-nfs", "").foreach {
      fileStoreType =>
        withClue(s"[$fileStoreType]: ") {
          Altitude.sqliteDiskWarning(dir, fileStoreType) shouldBe None
        }
    }
  }
}
