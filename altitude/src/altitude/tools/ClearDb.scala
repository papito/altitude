package altitude.tools

import altitude.core.Const
import altitude.core.Environment
import com.typesafe.config.ConfigFactory
import java.io.File
import java.sql.DriverManager
import java.util.Properties
import org.apache.commons.io.FileUtils

@main def clearDb(): Unit =
  if Environment.CURRENT != Environment.Name.DEV then
    System.err.println(
      s"ERROR: clear-db is only allowed in DEV (current ENV=${Environment.CURRENT}). Aborting."
    )
    sys.exit(1)

  val config = ConfigFactory
    .parseFile(new File("application-dev.conf"))
    .withFallback(ConfigFactory.defaultReference())

  val dbEngine = config.getString(Const.Conf.DB_ENGINE)

  dbEngine match
    case Const.DbEngineName.SQLITE =>
      val dataDir        = config.getString(Const.Conf.FS_DATA_DIR)
      val relDbPath      = config.getString(Const.Conf.REL_SQLITE_DB_PATH)
      val dbDir          = new File(dataDir, new File(relDbPath).getParent)

      if dbDir.exists() && dbDir.isDirectory then
        println(s"Clearing SQLite database files in: ${dbDir.getCanonicalPath}")
        FileUtils.cleanDirectory(dbDir)
        println("Done.")
      else
        println(s"SQLite database directory not found: ${dbDir.getPath}. Nothing to clear.")

    case Const.DbEngineName.POSTGRES =>
      val url      = config.getString(Const.Conf.POSTGRES_URL)
      val user     = config.getString(Const.Conf.POSTGRES_USER)
      val password = config.getString(Const.Conf.POSTGRES_PASSWORD)

      println(s"Clearing Postgres database at: $url")

      val props = new Properties
      props.setProperty("user", user)
      props.setProperty("password", password)

      val conn = DriverManager.getConnection(url, props)
      try
        conn.setAutoCommit(false)
        val stmt = conn.createStatement()
        try
          stmt.executeUpdate("""DROP SCHEMA IF EXISTS "public" CASCADE""")
          stmt.executeUpdate("""CREATE SCHEMA "public"""")
          conn.commit()
          println("Done.")
        finally
          stmt.close()
      finally
        conn.close()

    case other =>
      System.err.println(s"ERROR: Unknown db.engine value: '$other'. Aborting.")
      sys.exit(1)

