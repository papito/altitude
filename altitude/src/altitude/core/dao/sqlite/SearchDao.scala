package altitude.core.dao.sqlite

import com.typesafe.config.Config

import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.dao.sql.search.SearchDialect
import altitude.core.dao.sql.search.SqliteSearchDialect
import altitude.core.models.SceneEmbedding
import altitude.core.models.SceneModel

class SearchDao(override val config: Config) extends altitude.core.dao.jdbc.SearchDao(config) with SqliteOverrides:
  override protected val searchDialect: SearchDialect = SqliteSearchDialect

  /**
   * `vector_full_scan` is an exact scan of the SigLIP 2 Scene embeddings of the `asset` table, every repository's rows, which
   * sqlite-vector hands out with their cosine distance, filtered and ordered afterwards; a row with no embedding is not among
   * them. The column is registered with the extension on every connection as it is opened
   * (`TransactionManager.setUpSqliteConnection`), and the description's vector is bound as the column stores one, its raw float32
   * values.
   *
   * Binds: the vector, the repository, the cutoff, the limit.
   */
  override protected def describeRanked(
      embedding: SceneEmbedding,
      maxDistance: Double,
      limit: Int,
      triaged: Boolean): (String, List[Any]) =
    val sql = s"""
      SELECT asset.${FieldConst.ID} AS id, scanned.distance AS distance
        FROM vector_full_scan('asset', '${SceneModel.Siglip2.column}', $vectorPlaceholder) AS scanned
        JOIN asset ON asset.rowid = scanned.rowid
       WHERE asset.${FieldConst.REPO_ID} = ?
         AND ${describeFlags(triaged)}
         AND scanned.distance <= ?
       ORDER BY scanned.distance, asset.${FieldConst.ID}
       LIMIT ?
    """
    (sql, List(vectorBind(Some(embedding)), RequestContext.getRepository.persistedId, maxDistance, limit))
