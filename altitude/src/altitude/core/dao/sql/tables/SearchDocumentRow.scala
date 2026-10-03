package altitude.core.dao.sql.tables

import scalasql.Table

/**
 * The `search_document` table: one row per asset, whose `body` is the words of the asset's file name and user metadata values.
 *
 * The full-text index over `body` is each engine's own and is not part of this row: PostgreSQL's generated `tsv` column, and
 * SQLite's `search_document_fts` table, keyed by the SQLite table's own `id` (a declared key, so `VACUUM` cannot renumber it).
 * Both are written as raw fragments by the engine's [[altitude.core.dao.sql.search.SearchDialect]].
 */
case class SearchDocumentRow[T[_]](repositoryId: T[String], assetId: T[String], body: T[String])

object SearchDocumentRow extends Table[SearchDocumentRow]:
  override def tableName: String = "search_document"
