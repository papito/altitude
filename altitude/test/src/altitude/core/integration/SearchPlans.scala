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
 * Reads the engine's plan for a statement, for the suites that check which index a search or a lookup reads.
 *
 * Over the handful of rows a test holds, every index on the repository costs the same, so the planner's choice is a tie.
 * [[atScale]] gives it a library's worth of rows to choose by, and takes them back.
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
   * The plan of a hand-written statement with its bind values. It is read in a write transaction so a `FOR UPDATE` statement can
   * be explained, and PostgreSQL is told to scan only when nothing else answers.
   */
  protected def lookupPlanOf(sql: String, values: List[Any]): String =
    testApp.txManager.withTransaction {
      if (isPostgres) {
        update("SET LOCAL enable_seqscan = off")
        query(s"EXPLAIN $sql", values*).map(_("QUERY PLAN").toString).mkString("\n")
      } else query(s"EXPLAIN QUERY PLAN $sql", values*).map(_("detail").toString).mkString("\n")
    }

  /**
   * Runs `read` over the rows `seed` adds, with the tables analyzed, in one transaction that is rolled back: later suites share
   * the schema, so neither the rows nor their statistics may outlive the test. A rollback keeps PostgreSQL's in-place row-count
   * estimates, so its tables are analyzed again afterwards.
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
   * Adds `copies` copies of the asset, spread over `folders` new folders under the repository's root, each created a minute
   * before the last. Each copy has its own `id`, `checksum`, `folder_id` and `created_at`.
   */
  protected def seedCopies(templateId: String, copies: Int, folders: Int): Unit = {
    val (copyId, checksum, createdAt) =
      if (isPostgres)
        ("lpad(g.n::text, 36, '0')", "lpad((1000000 + g.n)::text, 64, '0')", "created_at - g.n * interval '1 minute'")
      else ("printf('%036d', g.n)", "printf('%064d', 1000000 + g.n)", "datetime(created_at, '-' || g.n || ' minutes')")
    // Padded to 36 characters, as every ID is
    def folderId(n: String): String =
      if (isPostgres) s"'seeded-folder-' || lpad(($n)::text, 22, '0')" else s"'seeded-folder-' || printf('%022d', $n)"

    update(
      s"""WITH RECURSIVE g (n) AS (SELECT 0 UNION ALL SELECT n + 1 FROM g WHERE n < ${folders - 1})
         |INSERT INTO folder (id, repository_id, name, name_key, parent_id)
         |SELECT ${folderId("g.n")}, asset.repository_id, 'seeded ' || g.n, 'seeded ' || g.n, repository.root_folder_id
         |  FROM asset JOIN repository ON repository.id = asset.repository_id CROSS JOIN g
         | WHERE asset.id = ?""".stripMargin,
      templateId
    )

    update(
      s"""WITH RECURSIVE g (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM g WHERE n < $copies)
         |INSERT INTO asset (id, repository_id, owner_user_id, original_owner, checksum, media_type, media_subtype, mime_type,
         |                   width, height, area_size,
         |                   folder_id, filename, size_bytes, is_triaged, is_recycled, is_purged, is_pipeline_processed,
         |                   original_created_at, created_at)
         |SELECT $copyId, repository_id, owner_user_id, original_owner, $checksum, media_type, media_subtype, mime_type,
         |       width, height, area_size,
         |       ${folderId(s"g.n % $folders")}, filename, size_bytes, is_triaged, is_recycled, is_purged, is_pipeline_processed,
         |       original_created_at, $createdAt
         |  FROM asset CROSS JOIN g
         | WHERE asset.id = ?""".stripMargin,
      templateId
    )
  }

  /**
   * Deletes the context repository's rows [[seedCopies]] added (the copies, whose IDs have no dash, then the folders), for a test
   * that commits its seed: the copies' and folders' IDs are the same in every test, so a row left behind fails a later seed.
   */
  protected def removeCopies(): Unit = {
    val repositoryId = RequestContext.getRepository.persistedId
    testApp.txManager.withTransaction {
      update("DELETE FROM asset WHERE repository_id = ? AND id NOT LIKE '%-%'", repositoryId)
      update("DELETE FROM folder WHERE repository_id = ? AND id LIKE 'seeded-folder-%'", repositoryId)
    }
  }

  /**
   * The plan of a statement an index alone should answer, read inside [[atScale]]. PostgreSQL is told each table's pages are
   * all-visible, as vacuuming would leave them, since an index-only scan of unvacuumed rows is costed as a table read too. It is
   * also told to scan or bitmap only when nothing else answers, since over narrow seeded rows a full read costs less. SQLite
   * picks a covering index by itself.
   */
  protected def indexOnlyPlanOf(statement: SqlStr, tables: String*): String = {
    if (isPostgres) {
      tables.foreach(table => update("UPDATE pg_class SET relallvisible = relpages WHERE relname = ?", table))
      List("seqscan", "bitmapscan").foreach(path => update(s"SET LOCAL enable_$path = off"))
    }
    planOf(statement)
  }

  /**
   * The plan of a lookup that only an index can serve. PostgreSQL is told to scan only when nothing else answers; SQLite has no
   * statistics to weigh a scan against. Over a handful of rows PostgreSQL ranks the indexes that lead with the repository by
   * size, so a lookup on a wide key, such as the checksum, runs inside [[atScale]] for the selective column to decide.
   */
  protected def lookupPlanOf(statement: SqlStr): String =
    if (isPostgres)
      testApp.txManager.withTransaction {
        update("SET LOCAL enable_seqscan = off")
        planOf(statement)
      }
    else planOf(statement)
}
