package altitude.core.benchmarks

import java.nio.file.{ Files, Path }
import java.sql.DriverManager
import java.time.{ LocalDate, LocalDateTime, OffsetDateTime }

import altitude.core.{ Const, RequestContext }
import altitude.core.dao.jdbc.querybuilder.SearchQueryBuilder
import altitude.core.models.Repository
import altitude.core.util.*

/** Reproducible benchmark of the real grouped SQL: an in-memory SQLite DB or an isolated schema in the PostgreSQL test DB. */
object CaptureDateGroupingBenchmark {
  private val output = Path.of("/tmp/capture-date-benchmark")

  def main(args: Array[String]): Unit = {
    Files.createDirectories(output)
    RequestContext.repository.value = Some(
      Repository(
        id = Some("repo"),
        name = "benchmark",
        ownerAccountId = "owner",
        rootFolderId = "root",
        fileStoreConfig = Map.empty,
        fileStoreType = Const.StorageEngineName.FS))
    val engines = if (args.isEmpty) List("sqlite", "postgres") else args.toList
    for (engine <- engines; size <- List(100000, 1000000)) run(engine, size)
  }

  private def run(engine: String, size: Int): Unit = {
    val sqlite = engine == "sqlite"
    val conn =
      if (sqlite) DriverManager.getConnection("jdbc:sqlite::memory:")
      else DriverManager.getConnection("jdbc:postgresql://localhost:5433/altitude-test", "altitude-test", "testdba")
    val schema = s"capture_benchmark_${System.nanoTime()}"
    def execute(sql: String): Unit = {
      val stmt = conn.createStatement();
      try stmt.execute(sql)
      finally stmt.close()
    }
    def day(field: String): String = if (sqlite) s"date(asset.$field)"
    else if (field == "created_at") "(asset.created_at AT TIME ZONE 'UTC')::date"
    else "asset.original_created_at::date"
    try {
      if (sqlite) execute("PRAGMA temp_store = MEMORY")
      else { execute(s"CREATE SCHEMA $schema"); execute(s"SET search_path TO $schema") }
      val timestamp = if (sqlite) "TEXT" else "TIMESTAMP WITHOUT TIME ZONE"
      val instant = if (sqlite) "TEXT" else "TIMESTAMP WITH TIME ZONE"
      execute(s"""CREATE TABLE asset(id TEXT PRIMARY KEY, repository_id TEXT, is_recycled BOOLEAN, is_pipeline_processed BOOLEAN,
        original_created_at $timestamp, created_at $instant, filename TEXT, size_bytes INT)""")
      val taken =
        if (sqlite) "datetime('2020-01-01', '+' || ((i*37)%3650) || ' days', '+' || (i%86400) || ' seconds')"
        else "timestamp '2020-01-01' + ((i*37)%3650) * interval '1 day' + (i%86400) * interval '1 second'"
      val imported =
        if (sqlite) "datetime('2025-01-01', '+' || ((i/1000)%365) || ' days', '+' || (i%86400) || ' seconds')"
        else "timestamptz '2025-01-01 00:00:00+00' + ((i/1000)%365) * interval '1 day' + (i%86400) * interval '1 second'"
      val seq =
        if (sqlite) s"WITH RECURSIVE seq(i) AS (VALUES(0) UNION ALL SELECT i+1 FROM seq WHERE i+1 < $size)"
        else s"WITH seq AS (SELECT generate_series(0, ${size - 1}) AS i)"
      val id = if (sqlite) "printf('%036d', i)" else "lpad(i::text, 36, '0')"
      val filename =
        if (sqlite) "printf('IMG_%06d.jpg', (i*7919)%100003)"
        else "'IMG_' || lpad(((i::bigint*7919)%100003)::text, 6, '0') || '.jpg'"
      execute(
        s"$seq INSERT INTO asset SELECT $id, 'repo', false, true, CASE WHEN i%10<3 THEN NULL ELSE $taken END, $imported, $filename, 1000 FROM seq")
      execute("CREATE INDEX asset_02 ON asset(repository_id, is_recycled, is_pipeline_processed)")
      for (field <- List("original_created_at", "created_at")) {
        val index = if (field == "created_at") "asset_search_date_imported" else "asset_search_date_taken"
        // The production index shape, including its timestamp but no ID or filename suffix.
        execute(
          s"CREATE INDEX $index ON asset(repository_id, is_recycled, is_pipeline_processed, (${day(field).replace("asset.", "")}), $field)")
      }
      execute(if (sqlite) "ANALYZE" else "VACUUM ANALYZE asset")
      val builder: SearchQueryBuilder =
        if (sqlite) new altitude.core.dao.sqlite.querybuilder.AssetSearchQueryBuilder(List("*"))
        else new altitude.core.dao.postgres.querybuilder.AssetSearchQueryBuilder(List("*"))
      val lines = collection.mutable.ListBuffer("engine,size,grouping,direction,sort,scenario,median_ms,rows")
      for (by <- GroupBy.values.toList; direction <- SortDirection.values.toList; field <- List(by.field, "filename")) {
        val sortDirection = if (field == "filename") SortDirection.ASC else direction
        val order = s"${day(by.field)} $direction, asset.$field $sortDirection, asset.id ASC"
        val nullCount = if (by == GroupBy.DateTaken) size * 3 / 10 else 0
        val datedCount = size - nullCount
        val leads = if (sqlite) direction == SortDirection.ASC else direction == SortDirection.DESC
        val deep = (if (leads) nullCount else 0) + datedCount * 8 / 10
        val scenarios = List("first" -> -1, "deep" -> deep) ++
          (if (nullCount == 0) Nil else List("inside_null" -> ((if (leads) 0 else datedCount) + nullCount / 2))) ++
          (if (nullCount > 0 && !leads) List("transition" -> (datedCount - 21)) else Nil)
        for ((scenario, anchorOffset) <- scenarios) {
          val cursor = Option.when(anchorOffset >= 0) {
            val stmt = conn.createStatement()
            try {
              val rs = stmt.executeQuery(
                s"SELECT id, ${day(by.field)} AS day, asset.$field AS sort_value FROM asset ORDER BY $order LIMIT 1 OFFSET $anchorOffset")
              require(rs.next())
              val anchorDay = Option(rs.getString("day")).map(LocalDate.parse)
              val value = Option(rs.getObject("sort_value")) match {
                case None => SortValue.Null
                case Some(_) if field == "filename" || sqlite => SortValue.Text(rs.getString("sort_value"))
                case Some(_) if field == "created_at" => SortValue.UtcInstant(rs.getObject("sort_value", classOf[OffsetDateTime]))
                case Some(_) => SortValue.LocalTimestamp(rs.getObject("sort_value", classOf[LocalDateTime]))
              }
              SearchCursor(anchorDay, value, rs.getString("id"), "benchmark")
            } finally stmt.close()
          }
          val query = new SearchQuery(
            params = Map("is_recycled" -> false),
            rpp = 50,
            searchSort = List(SearchSort(field, sortDirection)),
            grouping = Some(SearchGrouping(by, direction)),
            cursor = cursor)
          val sql = builder.buildGroupedSearchSql(query)
          val stmt = conn.prepareStatement(sql.sqlAsString)
          try {
            sql.bindValues.zipWithIndex.foreach { case (v, i) => stmt.setObject(i + 1, v.asInstanceOf[Object]) }
            def read(): List[String] = {
              val rs = stmt.executeQuery()
              val ids = collection.mutable.ListBuffer.empty[String]
              try {
                while (rs.next()) {
                  ids += rs.getString("id"); require(rs.getInt("day_total") > 0)
                  if (cursor.isEmpty) require(rs.getInt("total") == size)
                }
              } finally rs.close()
              ids.toList
            }
            val found = read()
            val reference = conn.createStatement()
            try {
              val rs = reference.executeQuery(s"SELECT id FROM asset ORDER BY $order LIMIT 50 OFFSET ${anchorOffset + 1}")
              val expected = collection.mutable.ListBuffer.empty[String]
              while (rs.next()) expected += rs.getString("id")
              require(found == expected.toList, s"Incorrect page: $engine/$size/$by/$direction/$field/$scenario")
            } finally reference.close()
            val times = (1 to 5).map {
              _ =>
                val start = System.nanoTime(); read(); (System.nanoTime() - start) / 1e6
            }.sorted
            val key = s"$engine-$size-${by.apiValue}-$direction-$field-$scenario"
            val line = f"$engine,$size,${by.apiValue},$direction,$field,$scenario,${times(2)}%.3f,${found.size}"
            lines += line
            println(line)
            val explain =
              conn.prepareStatement((if (sqlite) "EXPLAIN QUERY PLAN " else "EXPLAIN (ANALYZE, BUFFERS) ") + sql.sqlAsString)
            try {
              sql.bindValues.zipWithIndex.foreach { case (v, i) => explain.setObject(i + 1, v.asInstanceOf[Object]) }
              val rs = explain.executeQuery()
              val plan = new StringBuilder(sql.sqlAsStringCompact + "\n" + sql.bindValues + "\n")
              while (rs.next()) plan.append(rs.getString(if (sqlite) 4 else 1)).append('\n')
              Files.writeString(output.resolve(key + ".txt"), plan.toString)
            } finally explain.close()
          } finally stmt.close()
        }
      }
      Files.writeString(output.resolve(s"$engine-$size.csv"), lines.mkString("\n") + "\n")
    } finally {
      if (!sqlite) execute(s"DROP SCHEMA $schema CASCADE")
      conn.close()
    }
  }
}
