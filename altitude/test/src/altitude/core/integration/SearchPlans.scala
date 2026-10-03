package altitude.core.integration

import scalasql.core.SqlStr
import scalasql.core.SqlStr.SqlStringSyntax

import altitude.core.Const
import altitude.core.RequestContext
import altitude.core.dao.sql.Db
import altitude.core.dao.sql.search.PostgresSearchDialect
import altitude.core.dao.sql.search.SearchDialect
import altitude.core.dao.sql.search.SqliteSearchDialect

/**
 * Reading the engine's plan for a statement, for the suites that check which index a search or a lookup reads.
 *
 * A planner costs a plan by the table's statistics, and over the handful of rows a test holds every index on the repository costs
 * the same, so its choice would be a tie. [[atScale]] gives it a library's worth of rows to choose by, and takes them back.
 */
trait SearchPlans { self: IntegrationTestCore =>

  protected def isPostgres: Boolean = testApp.dataSourceType == Const.DbEngineName.POSTGRES

  protected def searchDialect: SearchDialect = if (isPostgres) PostgresSearchDialect else SqliteSearchDialect

  /** The engine's plan for `statement` as text: the lines of Postgres' EXPLAIN, or the details of SQLite's EXPLAIN QUERY PLAN */
  protected def planOf(statement: SqlStr): String = {
    val engine = searchDialect
    import engine.dialect.*

    testApp.txManager.asReadOnly {
      if (isPostgres) Db.read(engine.dialect)(_.runSql[String](sql"EXPLAIN $statement")).mkString("\n")
      else Db.read(engine.dialect)(_.runSql[(Int, Int, Int, String)](sql"EXPLAIN QUERY PLAN $statement")).map(_._4).mkString("\n")
    }
  }

  /**
   * `read` over the rows `seed` adds, with the tables analyzed, all in one transaction that is rolled back: the schema is shared
   * by every suite that follows, so neither the rows nor their statistics may outlive the test. PostgreSQL writes a table's
   * row-count estimate in place, which a rollback keeps, so its tables are analyzed again over the rows that are left.
   */
  protected def atScale[T](seed: => Unit)(read: => T): T =
    try
      testApp.txManager.withTransaction {
        seed
        update("ANALYZE")
        try read
        finally RequestContext.getConn.rollback()
      }
    finally if (isPostgres) testApp.txManager.withTransaction(update("ANALYZE"))

  /**
   * `copies` copies of the asset, spread over `folders` folders that need not exist, each imported a minute before the last: a
   * library for the planner to choose the asset's indexes by. `id`, `checksum`, `folder_id` and `created_at` are the copy's own.
   */
  protected def seedCopies(templateId: String, copies: Int, folders: Int): Unit = {
    val (copyId, createdAt) =
      if (isPostgres) ("lpad(g.n::text, 36, '0')", "created_at - g.n * interval '1 minute'")
      else ("printf('%036d', g.n)", "datetime(created_at, '-' || g.n || ' minutes')")

    update(
      s"""WITH RECURSIVE g (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM g WHERE n < $copies)
         |INSERT INTO asset (id, repository_id, user_id, checksum, media_type, media_subtype, mime_type, width, height, area_size,
         |                   folder_id, filename, size_bytes, is_triaged, is_recycled, is_purged, is_pipeline_processed,
         |                   original_created_at, created_at)
         |SELECT $copyId, repository_id, user_id, 1000000 + g.n, media_type, media_subtype, mime_type, width, height, area_size,
         |       'folder-' || (g.n % $folders), filename, size_bytes, is_triaged, is_recycled, is_purged, is_pipeline_processed,
         |       original_created_at, $createdAt
         |  FROM asset CROSS JOIN g
         | WHERE asset.id = ?""".stripMargin,
      templateId
    )
  }

  /**
   * The plan of a lookup that only an index can serve, so that the index named is the one the engine reads at any scale. SQLite
   * has no statistics to weigh a scan against; PostgreSQL is told to scan only when nothing else answers.
   */
  protected def lookupPlanOf(statement: SqlStr): String =
    if (isPostgres)
      testApp.txManager.withTransaction {
        update("SET LOCAL enable_seqscan = off")
        planOf(statement)
      }
    else planOf(statement)
}
