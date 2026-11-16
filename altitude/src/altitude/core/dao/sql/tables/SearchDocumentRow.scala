package altitude.core.dao.sql.tables

import scalasql.Table

/**
 * The `search_document` table: one row per asset, whose `body` is the words of its file name and user metadata values. The
 * full-text index is each engine's own, outside this row, and its SQL lives in [[altitude.core.dao.sql.search.SearchDialect]]:
 * PostgreSQL's generated `tsv` column, and SQLite's `search_document_fts` keyed by the table's declared `id` (so `VACUUM` cannot
 * renumber it).
 */
case class SearchDocumentRow[T[_]](repositoryId: T[String], assetId: T[String], body: T[String])

object SearchDocumentRow extends Table[SearchDocumentRow]:
  override def tableName: String = "search_document"
