package altitude.tools

import java.io.File
import java.sql.DriverManager
import java.util.Properties
import org.apache.commons.io.FileUtils

import altitude.core.Altitude
import altitude.core.Const
import altitude.core.Environment

@main def clearDb(): Unit =
  if !Environment.isDev then
    System.err.println(
      s"ERROR: clear-db is only allowed in dev (the current environment is ${Environment.CURRENT}; " +
        s"${Environment.Variable.ENV} sets it). Aborting."
    )
    sys.exit(1)

  // `application-dev.conf` over the defaults, and no `ALTITUDE_` variable: what is dropped here is never steered by a shell
  // left pointing at another instance (its database URL, its Data directory). A dev server started with such variables keeps
  // its data elsewhere, and this does not clear it.
  val config = Altitude.loadConfig(variables = Map.empty)
  val dataDir = Altitude.dataPathOf(config)
  val dbEngine = config.getString(Const.Conf.DB_ENGINE)

  dbEngine match
    case Const.DbEngineName.SQLITE =>
      val relDbPath = config.getString(Const.Conf.REL_SQLITE_DB_PATH)
      clearDir(new File(dataDir, new File(relDbPath).getParent), "SQLite database files")

    case Const.DbEngineName.POSTGRES =>
      val url = config.getString(Const.Conf.POSTGRES_URL)
      val user = config.getString(Const.Conf.POSTGRES_USER)
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
        finally stmt.close()
      finally conn.close()

    case other =>
      System.err.println(s"ERROR: Unknown db.engine value: '$other'. Aborting.")
      sys.exit(1)

  // Stored files are keyed by repository and asset IDs that die with the database. Cleared after it, so a failed database
  // clear leaves both intact.
  clearDir(new File(dataDir, Const.DataStore.REPOSITORIES), "repository files")

/** Empties a directory, keeping the directory itself */
private def clearDir(dir: File, label: String): Unit =
  if dir.isDirectory then
    println(s"Clearing $label in: ${dir.getCanonicalPath}")
    FileUtils.cleanDirectory(dir)
    println("Done.")
  else println(s"Directory not found: ${dir.getPath}. Nothing to clear.")
