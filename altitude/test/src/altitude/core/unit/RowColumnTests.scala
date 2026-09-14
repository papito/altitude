package altitude.core.unit

import altitude.test.TestFocus
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite
import org.scalatest.matchers.should.Matchers.shouldBe
import scalasql.Table
import scalasql.core.DbApi
import scalasql.dialects.Dialect

import scala.io.Source

import altitude.core.dao.sql.Db
import altitude.core.dao.sql.dialects.AltitudePostgresDialect
import altitude.core.dao.sql.dialects.AltitudeSqliteDialect
import altitude.core.dao.sql.tables._

/**
 * What the row classes select, checked against both schemas.
 *
 * Column names are never spelled out in a row class: they come from the field name through the configured name mapper. A renamed
 * field, or a column that exists on only one engine, would otherwise surface as a SQL error deep inside an integration run - or,
 * for the update and filter paths, as a silently unresolvable column name.
 */
@DoNotDiscover class RowColumnTests extends funsuite.AnyFunSuite with TestFocus {

  /** Every table with a rendered `SELECT` per engine. The rendering is the point: it is what the database actually sees. */
  private def rendered(dialect: Dialect): List[(Table.Base, String)] = {
    import dialect._

    List(
      AccountRow -> DbApi.renderSql(AccountRow.select, Db.config, dialect),
      AlbumRow -> DbApi.renderSql(AlbumRow.select, Db.config, dialect),
      AlbumAssetRow -> DbApi.renderSql(AlbumAssetRow.select, Db.config, dialect),
      AssetRow -> DbApi.renderSql(AssetRow.select, Db.config, dialect),
      FaceRow -> DbApi.renderSql(FaceRow.select, Db.config, dialect),
      FolderRow -> DbApi.renderSql(FolderRow.select, Db.config, dialect),
      LocationRow -> DbApi.renderSql(LocationRow.select, Db.config, dialect),
      LocationAssetRow -> DbApi.renderSql(LocationAssetRow.select, Db.config, dialect),
      MetadataFieldRow -> DbApi.renderSql(MetadataFieldRow.select, Db.config, dialect),
      MetadataParameterRow -> DbApi.renderSql(MetadataParameterRow.select, Db.config, dialect),
      PersonRow -> DbApi.renderSql(PersonRow.select, Db.config, dialect),
      RepositoryRow -> DbApi.renderSql(RepositoryRow.select, Db.config, dialect),
      SearchDocumentRow -> DbApi.renderSql(SearchDocumentRow.select, Db.config, dialect),
      StatRow -> DbApi.renderSql(StatRow.select, Db.config, dialect),
      SystemRow -> DbApi.renderSql(SystemRow.select, Db.config, dialect),
      UserTokenRow -> DbApi.renderSql(UserTokenRow.select, Db.config, dialect)
    )
  }

  private val engines = List("postgres" -> AltitudePostgresDialect, "sqlite" -> AltitudeSqliteDialect)

  test("Every row class selects only columns both schemas have") {
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

  test("The names the filter and update paths resolve are the names the rendered SQL uses") {
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
    val selected = selectedColumns(rendered(AltitudeSqliteDialect).toMap.apply(AssetRow)).toSet

    // The columns `makeModel` reads, and which `toModel` therefore has to read too
    val modelColumns = Set(
      "id",
      "user_id",
      "filename",
      "checksum",
      "media_type",
      "media_subtype",
      "mime_type",
      "width",
      "height",
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
      "created_at",
      "updated_at"
    )

    modelColumns.diff(selected) shouldBe Set()
  }

  test("Columns render unquoted, so an expression index can still match them") {
    val sql = rendered(AltitudePostgresDialect).toMap.apply(AssetRow)

    sql.contains("\"") shouldBe false
    sql.contains("asset0.original_created_at") shouldBe true
  }

  /** The columns of a rendered `SELECT`, as the table aliases them */
  private def selectedColumns(sql: String): List[String] =
    "[a-z_]+[0-9]+\\.([a-z_0-9]+) AS ".r.findAllMatchIn(sql).map(_.group(1)).toList

  /**
   * `CREATE TABLE` column names per table, with PostgreSQL's inherited `_core` columns folded in.
   *
   * SQLite's `search_document` is an fts4 virtual table, declared on one line and inheriting nothing, so it is read separately.
   */
  private def schemaColumns(engine: String): Map[String, List[String]] = {
    val source = Source.fromInputStream(getClass.getResourceAsStream(s"/migrations/$engine/all.sql"))
    val ddl =
      try source.mkString
      finally source.close()

    val tables = "(?s)CREATE TABLE (\\w+) \\((.*?)\\n\\)([^;]*);".r
      .findAllMatchIn(ddl)
      .map(m => m.group(1) -> (columnNames(m.group(2)), m.group(3).contains("INHERITS")))
      .toMap

    val virtualTables = "CREATE VIRTUAL TABLE (\\w+) USING \\w+ \\(([^)]*)\\)".r
      .findAllMatchIn(ddl)
      .map(m => m.group(1) -> m.group(2).split(",").map(_.trim).toList)
      .toMap

    val core = tables.get("_core").map(_._1).getOrElse(List())
    tables.map { case (name, (columns, inherits)) => name -> (if (inherits) core ++ columns else columns) } ++ virtualTables
  }

  private def columnNames(body: String): List[String] =
    body
      .split("\n")
      .map(_.replaceAll("--.*", "").trim)
      .filter(_.nonEmpty)
      .map(_.takeWhile(c => c.isLetterOrDigit || c == '_'))
      .filter(_.nonEmpty)
      .filterNot(word => Set("FOREIGN", "PRIMARY", "UNIQUE", "CHECK", "CONSTRAINT").contains(word.toUpperCase))
      .toList
}
