package altitude.core.suites

import altitude.core.Altitude
import altitude.core.Const as C

/** The test database of an app under test: wiped to nothing, or reset to the current schema with the Settings seeded */
object TestDatabase {

  /** Drops every table, index and trigger, so the database is as a fresh install finds it */
  def wipe(testApp: Altitude): Unit = testApp.dataSourceType match {
    case C.DbEngineName.SQLITE =>
      val sql =
        """
          PRAGMA writable_schema = 1;
          delete from sqlite_master where type in ('table', 'index', 'trigger');
          PRAGMA writable_schema = 0;
          VACUUM;
          PRAGMA INTEGRITY_CHECK;
        """.stripMargin

      val conn = testApp.txManager.connection(readOnly = false)
      // disables transaction for this connection (cannot use VACUUM in a transaction)
      conn.setAutoCommit(true)

      val stmt = conn.createStatement()

      try {
        stmt.executeUpdate(sql)
      } finally {
        stmt.close()
        conn.close()
      }

    case C.DbEngineName.POSTGRES =>
      val conn = testApp.txManager.connection(readOnly = false)
      val stmt = conn.createStatement()

      try {
        stmt.executeUpdate("DROP SCHEMA IF EXISTS \"public\" CASCADE; CREATE SCHEMA \"public\";")
        conn.commit()
      } finally {
        stmt.close()
        conn.close()
      }
  }

  /** Wipes and migrates the database and reloads the Settings, so what follows sees the current schema and the seeds */
  def reset(testApp: Altitude): Unit = {
    wipe(testApp)
    testApp.migrationService.migrate()
    testApp.settings.load()
  }
}
