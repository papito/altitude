package altitude.core.dao.sql.tables

import scalasql.Table

/**
 * The `search_document` table, with only the columns both engines have.
 *
 * The engines disagree about the rest of it: PostgreSQL keeps a `metadata_values` column and the `tsv` vector the GIN index is
 * built on, while SQLite's is an fts4 virtual table whose text lives in `body` alone. The one column that is not shared and is
 * still needed - PostgreSQL's `tsv` - is written as a raw fragment by [[altitude.core.dao.sql.search.PostgresSearchDialect]].
 */
case class SearchDocumentRow[T[_]](repositoryId: T[String], assetId: T[String], body: T[String])

object SearchDocumentRow extends Table[SearchDocumentRow]:
  override def tableName: String = "search_document"
