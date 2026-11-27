package altitude.core.unit

import altitude.test.TestContext
import altitude.test.TestFocus
import java.nio.file.Files
import java.nio.file.Path
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers.*
import scalasql.Table
import scalasql.core.DbApi
import scalasql.dialects.Dialect

import scala.io.Source

import altitude.core.FieldConst
import altitude.core.dao.sql.Db
import altitude.core.dao.sql.dialects.AltitudePostgresDialect
import altitude.core.dao.sql.dialects.AltitudeSqliteDialect
import altitude.core.dao.sql.tables._

/**
 * What the row classes select, checked against both schemas. Row classes take column names from their field names through the
 * name mapper, so a renamed field or a column on one engine only would otherwise surface deep in an integration run, or as an
 * unresolvable name on the update and filter paths.
 */
@DoNotDiscover class RowColumnTests extends funsuite.AnyFunSuite with TestFocus {

  /** Every table with its rendered `SELECT`, which is what the database actually sees */
  private def rendered(dialect: Dialect): List[(Table.Base, String)] = {
    import dialect._

    List(
      AccountRow -> DbApi.renderSql(AccountRow.select, Db.config, dialect),
      AlbumRow -> DbApi.renderSql(AlbumRow.select, Db.config, dialect),
      AlbumAssetRow -> DbApi.renderSql(AlbumAssetRow.select, Db.config, dialect),
      AssetRow -> DbApi.renderSql(AssetRow.select, Db.config, dialect),
      AssetEditRow -> DbApi.renderSql(AssetEditRow.select, Db.config, dialect),
      AuditEventRow -> DbApi.renderSql(AuditEventRow.select, Db.config, dialect),
      FaceRow -> DbApi.renderSql(FaceRow.select, Db.config, dialect),
      FolderRow -> DbApi.renderSql(FolderRow.select, Db.config, dialect),
      ImportRow -> DbApi.renderSql(ImportRow.select, Db.config, dialect),
      ImportFileRow -> DbApi.renderSql(ImportFileRow.select, Db.config, dialect),
      LocationRow -> DbApi.renderSql(LocationRow.select, Db.config, dialect),
      LocationAssetRow -> DbApi.renderSql(LocationAssetRow.select, Db.config, dialect),
      MetadataFieldRow -> DbApi.renderSql(MetadataFieldRow.select, Db.config, dialect),
      MetadataParameterRow -> DbApi.renderSql(MetadataParameterRow.select, Db.config, dialect),
      PersonRow -> DbApi.renderSql(PersonRow.select, Db.config, dialect),
      RepositoryRow -> DbApi.renderSql(RepositoryRow.select, Db.config, dialect),
      RepositoryGrantRow -> DbApi.renderSql(RepositoryGrantRow.select, Db.config, dialect),
      SearchDocumentRow -> DbApi.renderSql(SearchDocumentRow.select, Db.config, dialect),
      SettingRow -> DbApi.renderSql(SettingRow.select, Db.config, dialect),
      StatRow -> DbApi.renderSql(StatRow.select, Db.config, dialect),
      SystemRow -> DbApi.renderSql(SystemRow.select, Db.config, dialect),
      WatchFolderRow -> DbApi.renderSql(WatchFolderRow.select, Db.config, dialect),
      WatchFolderFileRow -> DbApi.renderSql(WatchFolderFileRow.select, Db.config, dialect)
    )
  }

  private val engines = List("postgres" -> AltitudePostgresDialect, "sqlite" -> AltitudeSqliteDialect)

  test("Every row class selects only columns both schemas have") {

    /**
     * Setup:
     *
     * The rendered `SELECT` of each of the 23 row classes on both engines, and the `CREATE TABLE` columns parsed from each
     * engine's `all.sql`, with PostgreSQL's inherited `_core` columns folded in.
     *
     * Assertions:
     *
     * Every table exists in both schemas, and each rendered statement selects at least one column and none the schema lacks.
     */
    for {
      (engine, dialect) <- engines
      schema = schemaColumns(engine)
      (table, sql) <- rendered(dialect)
    } {
      val name = Table.name(table)
      val declared = schema.getOrElse(name, fail(s"No table [$name] in the $engine schema"))
      val selected = selectedColumns(sql)

      withClue(s"$engine.$name (rendered: $sql): ") {
        selected.nonEmpty shouldBe true
        selected.diff(declared) shouldBe List()
      }
    }
  }

  test("Both schemas declare the same tables with the same columns, but where an engine has one of its own") {

    /**
     * Setup:
     *
     * The `CREATE TABLE` columns of each engine's `all.sql`, with PostgreSQL's inherited `_core` columns folded in.
     *
     * Assertions:
     *
     * Every table but the engines' own is in both schemas, and with the same set of columns, so a write or read of a column means
     * one thing on either.
     *
     * Edge cases:
     *
     * Only these are an engine's own: PostgreSQL's `_core`, the parent the other tables inherit `created_at` from, and
     * `search_document.tsv`, the generated text vector its full-text index holds; SQLite's `person_label`, the table its
     * unnamed-person numbers are drawn from, PostgreSQL drawing them from a sequence, and `search_document.id`, the integer key
     * its FTS5 index's external content needs.
     */
    val (postgres, sqlite) = (schemaColumns("postgres"), schemaColumns("sqlite"))
    val ownTables = Map("postgres" -> Set("_core"), "sqlite" -> Set("person_label"))
    val ownColumns = Map("postgres" -> Set("search_document.tsv"), "sqlite" -> Set("search_document.id"))

    postgres.keySet -- ownTables("postgres") shouldBe sqlite.keySet -- ownTables("sqlite")
    for (table <- postgres.keySet.intersect(sqlite.keySet)) withClue(s"$table: ") {
      def columns(engine: String, schema: Map[String, List[String]]) =
        schema(table).map(column => s"$table.$column").toSet -- ownColumns(engine)
      columns("postgres", postgres) shouldBe columns("sqlite", sqlite)
    }
  }

  test("The names the filter and update paths resolve are the names the rendered SQL uses") {

    /**
     * Setup:
     *
     * Each row class's field labels through the column name mapper, beside its rendered `SELECT` on both engines.
     *
     * Assertions:
     *
     * The mapped names are exactly the columns the statement selects.
     */
    for {
      (engine, dialect) <- engines
      (table, sql) <- rendered(dialect)
    } {
      // `Columns.byName` maps field names itself; if a table ever declares a column-name override, the two would diverge
      val resolvable = Table.labels(table).map(Db.config.columnNameMapper).toSet

      withClue(s"$engine.${Table.name(table)}: ") {
        selectedColumns(sql).toSet shouldBe resolvable
      }
    }
  }

  test("The asset row covers every column its model is built from") {

    /**
     * Setup:
     *
     * The asset row's rendered SQLite `SELECT`, and a hand-kept list of the columns `AssetDao.makeModel` reads.
     *
     * Assertions:
     *
     * The row selects every column on the list.
     */
    val selected = selectedColumns(rendered(AltitudeSqliteDialect).toMap.apply(AssetRow)).toSet

    // The columns `makeModel` reads, so `toModel` has to read them too
    val modelColumns = Set(
      "id",
      "owner_user_id",
      "original_owner",
      "filename",
      "checksum",
      "media_type",
      "media_subtype",
      "mime_type",
      "width",
      "height",
      "duration_ms",
      "size_bytes",
      "extracted_metadata",
      "public_metadata",
      "user_metadata",
      "folder_id",
      "is_recycled",
      "is_triaged",
      "is_pipeline_processed",
      "original_created_at",
      "original_created_at_source",
      "latitude",
      "longitude",
      "sharpness",
      "luminance",
      "clipped_highlights",
      "crushed_shadows",
      "image_quality",
      "perceptual_hash",
      "sequence_id",
      "edit_rev",
      "created_at"
    )

    modelColumns.diff(selected) shouldBe Set()
  }

  test("The asset's column list keeps, leaves out or nulls the two bulky metadata columns, and nothing else") {

    /**
     * Setup:
     *
     * The asset row's column list for a hand-written select, with the two bulky metadata columns kept, left out, and read as
     * NULL.
     *
     * Assertions:
     *
     * Kept, it is every label of the row in declared order as `asset.<column>`. Left out, it lacks the two. As NULL, it keeps
     * every position with `NULL AS <column>` for the two, so a typed read still reads the row back by position.
     *
     * Edge cases:
     *
     * `public_metadata` is in all three, and no Scene embedding is in any, since the row declares none.
     */
    val labels = Table.labels(AssetRow).map(Db.config.columnNameMapper)
    val bulky = Set("user_metadata", "extracted_metadata")
    bulky.subsetOf(labels.toSet) shouldBe true
    def entries(list: String): List[String] = list.split(", ").toList

    entries(AssetRow.columnList()) shouldBe labels.map(name => s"asset.$name").toList
    entries(AssetRow.columnList(BulkyMetadata.Omitted)) shouldBe labels.filterNot(bulky).map(name => s"asset.$name").toList
    entries(AssetRow.columnList(BulkyMetadata.AsNull)) shouldBe
      labels.map(name => if (bulky(name)) s"NULL AS $name" else s"asset.$name").toList

    BulkyMetadata.values.foreach {
      form =>
        val list = entries(AssetRow.columnList(form))
        list.contains("asset.public_metadata") shouldBe true
        list.exists(_.contains("embedding")) shouldBe false
    }
  }

  test("Columns render unquoted, so an expression index can still match them") {

    /**
     * Setup:
     *
     * The asset row's rendered PostgreSQL `SELECT`.
     *
     * Assertions:
     *
     * No identifier is quoted, and a column reads as the bare `asset0.original_created_at`.
     */
    val sql = rendered(AltitudePostgresDialect).toMap.apply(AssetRow)

    sql.contains("\"") shouldBe false
    sql.contains("asset0.original_created_at") shouldBe true
  }

  test("Each column is nullable on both engines or on neither, as its row class types it") {

    /**
     * Setup:
     *
     * The columns of every table both schemas declare, each NOT NULL or not as its `CREATE TABLE` says (a primary key column is
     * NOT NULL), and each row class's fields as its source declares them, an `Option` or not.
     *
     * Assertions:
     *
     * A column of both schemas is NOT NULL on both or on neither. A row field that is no `Option` is NOT NULL on both engines,
     * and an `Option` field is nullable on both, so no read meets a NULL its row cannot hold and no column holds a NULL its model
     * never has.
     *
     * Edge cases:
     *
     * `created_at` is NOT NULL with a default on both engines and an `Option` in every row, whose models are built before the
     * database stamps them.
     */
    val postgres = schemaTables("postgres")
    val sqlite = schemaTables("sqlite")

    for {
      (table, pgColumns) <- postgres
      sqliteColumns <- sqlite.get(table).toList
      column <- pgColumns
      other <- sqliteColumns.find(_.name == column.name)
    } withClue(s"$table.${column.name}: ")(column.notNull shouldBe other.notNull)

    // The source is read by a pattern, so it must find every field the row selects, and no other
    for ((table, sql) <- rendered(AltitudeSqliteDialect))
      withClue(s"${Table.name(table)}: ")(rowFields(Table.name(table)).map(_._1) shouldBe selectedColumns(sql))

    for {
      (table, fields) <- rowFields
      (field, isOption) <- fields
      engine <- List("postgres", "sqlite")
    } {
      val column = schemaTables(engine)(table).find(_.name == field).getOrElse(fail(s"No column $table.$field in $engine"))
      val expected = !isOption || field == FieldConst.CREATED_AT
      withClue(s"$engine $table.$field: ")(column.notNull shouldBe expected)
    }
  }

  test("Both schemas name the same indexes on the same tables, but where an engine's index has no twin") {

    /**
     * Setup:
     *
     * Every `CREATE INDEX` of both schemas, by name, with its table and whether it is unique.
     *
     * Assertions:
     *
     * Each index is on a table a row class reads, and is on both engines under one name, on the same table and equally unique, so
     * a plan or a violation told by an index name means one thing on either.
     *
     * Edge cases:
     *
     * PostgreSQL's vector indexes (`asset_describe`, `face_03`) and its full-text index (`search_document_02`) have no twin,
     * since SQLite scans the vectors and keeps the text in an FTS5 table; SQLite's `face_04` carries the person an asset's Faces
     * are probed for, which PostgreSQL's `face_01` includes.
     */
    val postgres = schemaIndexes("postgres")
    val sqlite = schemaIndexes("sqlite")
    val rowTables = rendered(AltitudeSqliteDialect).map((table, _) => Table.name(table)).toSet

    (postgres ++ sqlite).foreach { case (name, (table, _)) => withClue(s"$name: ")(rowTables should contain(table)) }
    postgres.keySet.diff(sqlite.keySet) shouldBe Set("asset_describe", "face_03", "search_document_02")
    sqlite.keySet.diff(postgres.keySet) shouldBe Set("face_04")
    postgres.keySet.intersect(sqlite.keySet).foreach(name => withClue(s"$name: ")(postgres(name) shouldBe sqlite(name)))
  }

  test("Every column that references an account leads an index, so an account delete seeks its rows") {

    /**
     * Setup:
     *
     * Every column of both schemas declared `REFERENCES account (id)`.
     *
     * Assertions:
     *
     * Each leads an index of its table or its table's primary key, so the delete action or key check of an account delete, and
     * the writes that pass an account's rows on, seek the rows rather than scan the table under the Admins' row locks.
     */
    for (engine <- List("postgres", "sqlite")) {
      val ddl = schemaText(engine)
      val tables = "(?s)CREATE TABLE (\\w+) \\((.*?)\\n\\)".r.findAllMatchIn(ddl).map(m => m.group(1) -> m.group(2)).toList
      val referencing = for {
        (table, body) <- tables
        column <- "(?m)^\\s*(\\w+) [^\\n]*REFERENCES account \\(id\\)".r.findAllMatchIn(body).map(_.group(1)).toList
      } yield (table, column)

      referencing should not be empty
      referencing.foreach {
        case (table, column) =>
          val led = s"(?s)CREATE (UNIQUE )?INDEX \\w+ ON $table \\($column[,)]".r.findFirstIn(ddl).isDefined ||
            tables.toMap.get(table).exists(body => s"PRIMARY KEY \\($column[,)]".r.findFirstIn(body).isDefined)
          withClue(s"$engine $table.$column: ")(led shouldBe true)
      }
    }
  }

  test("Every PostgreSQL text column an index holds or a list orders by is COLLATE \"C\"") {

    /**
     * Setup:
     *
     * PostgreSQL's `all.sql`: each text column (`TEXT` or `VARCHAR`) that a btree index, a primary key or a unique constraint
     * holds, and each that a list or a lookup orders or compares by its name (`*_key`, `*_sort`, `path`, `rel_path`, `dimension`,
     * `setting.name`).
     *
     * Assertions:
     *
     * Each declares `COLLATE "C"`, so it compares by its bytes as SQLite does, and no locale upgrade can reorder an index under
     * it; so does each text expression a btree index holds (a JSON key's text, `->>`), which a statement then spells the same to
     * use the index. A `*_sort` column, a name's `SortKey`, is no text: `BYTEA` on PostgreSQL and `BLOB` on SQLite, both compared
     * as `memcmp` compares.
     *
     * Edge cases:
     *
     * A vector or full-text index (`hnsw`, `gin`) and an index's included columns compare nothing.
     */
    val ddl = schemaText("postgres")
    val tables = schemaTables("postgres")
    def isText(column: Column): Boolean = "^\\w+ (TEXT|VARCHAR)\\b".r.findFirstIn(column.declaration).isDefined

    val items = for {
      m <- "CREATE (UNIQUE )?INDEX \\w+ ON (\\w+)(?: USING (\\w+))? \\(".r.findAllMatchIn(ddl).toList
      if !Option(m.group(3)).exists(Set("hnsw", "gin"))
      item <- topLevelItems(ddl, m.end - 1)
    } yield (m.group(2), item)
    val (columnItems, expressions) = items.partition((_, item) => "\\w+".r.matches(item))

    val textExpressions = expressions.filter((_, item) => item.contains("->>"))
    textExpressions should not be empty
    for ((table, expression) <- textExpressions)
      withClue(s"$table $expression: ")(expression should include("COLLATE \"C\""))

    val indexed = columnItems ++ (for {
      body <- "(?s)CREATE TABLE (\\w+) \\((.*?)\\n\\)".r.findAllMatchIn(ddl).map(m => m.group(1) -> m.group(2)).toList
      constraint <- "(?m)^\\s*(?:PRIMARY KEY|UNIQUE) \\(([^)]*)\\)".r.findAllMatchIn(body._2).toList
      column <- constraint.group(1).split(",").map(_.trim).toList
    } yield (body._1, column)) ++ (for {
      (table, columns) <- tables.toList
      column <- columns
      if column.declaration.contains("PRIMARY KEY") || "\\bUNIQUE\\b".r.findFirstIn(column.declaration).isDefined
    } yield (table, column.name))

    val keyNamed = Set("path", "rel_path", "dimension")
    val ordered = for {
      (table, columns) <- tables.toList
      column <- columns
      if column.name.endsWith("_key") || column.name.endsWith("_sort") || keyNamed(column.name) ||
        (table == "setting" && column.name == "name")
    } yield (table, column.name)

    val checked = (indexed ++ ordered).distinct.flatMap((table, name) => tables(table).find(_.name == name).map(table -> _))
    checked.filter((_, column) => isText(column)) should not be empty
    for ((table, column) <- checked if isText(column))
      withClue(s"$table.${column.name}: ")(column.declaration should include("COLLATE \"C\""))

    for {
      (engine, binary) <- List("postgres" -> "BYTEA", "sqlite" -> "BLOB")
      (table, columns) <- schemaTables(engine)
      column <- columns if column.name.endsWith("_sort")
    } withClue(s"$engine $table.${column.name}: ")(column.declaration should startWith(s"${column.name.toUpperCase} $binary "))
  }

  test("The scoped tables a test compares repositories by are every table that carries repository_id") {

    /**
     * Setup:
     *
     * `TestContext.SCOPED_TABLES`, and the tables of each schema with a `repository_id` column.
     *
     * Assertions:
     *
     * The list is those tables on both engines, but `repository_grant`, the accounts' grants in a Library rather than its
     * content, so a table added with `repository_id` is one `RepositoryIsolationTests` checks.
     */
    for (engine <- List("postgres", "sqlite")) {
      val scoped = schemaTables(engine).collect {
        case (table, columns) if columns.exists(_.name == FieldConst.REPO_ID) => table
      }.toSet - "repository_grant"

      withClue(s"$engine: ")(TestContext.SCOPED_TABLES.toSet shouldBe scoped)
    }
    TestContext.SCOPED_TABLES.distinct shouldBe TestContext.SCOPED_TABLES
  }

  /** The columns of a rendered `SELECT`, as the table aliases them */
  private def selectedColumns(sql: String): List[String] =
    "[a-z_]+[0-9]+\\.([a-z_0-9]+) AS ".r.findAllMatchIn(sql).map(_.group(1)).toList

  /** A column of a `CREATE TABLE`: NOT NULL when it says so or is the primary key, with its declaration in upper case */
  private case class Column(name: String, notNull: Boolean, declaration: String)

  // Not `IS NOT NULL`, which a CHECK on the line may hold
  private val NOT_NULL = "(?<!IS )\\bNOT NULL\\b".r

  /** `CREATE TABLE` column names per table, with PostgreSQL's inherited `_core` columns folded in */
  private def schemaColumns(engine: String): Map[String, List[String]] =
    schemaTables(engine).map((table, columns) => table -> columns.map(_.name))

  /** `CREATE TABLE` columns per table, with PostgreSQL's inherited `_core` columns folded in */
  private def schemaTables(engine: String): Map[String, List[Column]] = {
    val tables = "(?s)CREATE TABLE (\\w+) \\((.*?)\\n\\)([^;]*);".r
      .findAllMatchIn(schemaText(engine))
      .map(m => m.group(1) -> (columnsOf(m.group(2)), m.group(3).contains("INHERITS")))
      .toMap

    val core = tables.get("_core").map(_._1).getOrElse(List())
    tables.map { case (name, (columns, inherits)) => name -> (if (inherits) core ++ columns else columns) }
  }

  /** `CREATE INDEX` names, each with its table and whether it is unique */
  private def schemaIndexes(engine: String): Map[String, (String, Boolean)] =
    "CREATE (UNIQUE )?INDEX (\\w+) ON (\\w+)".r
      .findAllMatchIn(schemaText(engine))
      .map(m => m.group(2) -> (m.group(3), m.group(1) != null))
      .toMap

  /** The comma-separated items of the parenthesized list opening at `open`, each trimmed, nested parentheses kept whole */
  private def topLevelItems(text: String, open: Int): List[String] = {
    var depth = 0
    var i = open
    val items = List.newBuilder[String]
    var start = open + 1
    while ({
      text(i) match {
        case '(' => depth += 1
        case ')' => depth -= 1
        case ',' if depth == 1 =>
          items += text.substring(start, i).trim
          start = i + 1
        case _ =>
      }
      i += 1
      depth > 0
    }) ()
    items += text.substring(start, i - 1).trim
    items.result()
  }

  private def schemaText(engine: String): String = {
    val source = Source.fromInputStream(getClass.getResourceAsStream(s"/migrations/$engine/all.sql"))
    try source.mkString
    finally source.close()
  }

  private def columnsOf(body: String): List[Column] =
    body
      .split("\n")
      .map(_.replaceAll("--.*", "").trim)
      .filter(_.nonEmpty)
      .map(line => (line.takeWhile(c => c.isLetterOrDigit || c == '_'), line.toUpperCase))
      .filter(_._1.nonEmpty)
      .filterNot((word, _) => Set("FOREIGN", "PRIMARY", "UNIQUE", "CHECK", "CONSTRAINT").contains(word.toUpperCase))
      .map((name, line) => Column(name, NOT_NULL.findFirstIn(line).isDefined || line.contains("PRIMARY KEY"), line))
      .toList

  /**
   * Each row class's fields by its table, as column names, each `true` when the field is an `Option`: read from the row class's
   * source, since a field's type argument is erased at runtime
   */
  private lazy val rowFields: Map[String, List[(String, Boolean)]] = {
    val tablesDir = Path.of(sys.env("ALTITUDE_SOURCE_DIR")).resolve("altitude/core/dao/sql/tables")
    val classes =
      rendered(AltitudeSqliteDialect).map((table, _) => table.getClass.getSimpleName.stripSuffix("$") -> Table.name(table))

    classes.map {
      (className, tableName) =>
        val source = Files.readString(tablesDir.resolve(s"$className.scala"))
        val body = s"(?s)case class $className\\[T\\[_\\]\\]\\((.*?)\\)\\n".r
          .findFirstMatchIn(source)
          .getOrElse(fail(s"No case class $className in its source"))
          .group(1)
        tableName -> "(\\w+): T\\[(Option\\[)?".r
          .findAllMatchIn(body)
          .map(m => Db.config.columnNameMapper(m.group(1)) -> (m.group(2) != null))
          .toList
    }.toMap
  }
}
