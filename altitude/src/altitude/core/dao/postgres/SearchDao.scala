package altitude.core.dao.postgres

import com.typesafe.config.Config

import altitude.core.FieldConst
import altitude.core.RequestContext
import altitude.core.dao.sql.search.PostgresSearchDialect
import altitude.core.dao.sql.search.SearchDialect
import altitude.core.models.SceneEmbedding
import altitude.core.models.SceneModel

class SearchDao(override val config: Config) extends altitude.core.dao.jdbc.SearchDao(config) with PostgresOverrides:
  override protected val searchDialect: SearchDialect = PostgresSearchDialect

  /**
   * The candidates come from `asset_describe`, the HNSW index over the SigLIP 2 Scene embeddings at half precision, which the
   * inner order is written to match. The repository and the flags are filtered as the index is read: the transaction is set up
   * for a vector search of `limit` rows (`TransactionManager.withVectorSearch`), so the scan goes on until that many rows pass
   * them, or the index ends. Each candidate's distance is the exact one, at full precision, and the cutoff is applied to it
   * outside: inside, a description with nothing near enough would send the index looking for rows that are not there. An asset
   * with no embedding is not in the index, and where the planner scans the table instead, over a few rows, its distance is NULL,
   * which the cutoff drops. The search is approximate; over a handful of assets it is exhaustive.
   *
   * Binds: the vector, the repository, the vector, the limit, the cutoff.
   */
  override protected def describeRanked(
      embedding: SceneEmbedding,
      maxDistance: Double,
      limit: Int,
      triaged: Boolean): (String, List[Any]) =
    val column = s"asset.${SceneModel.Siglip2.column}"
    val halfPrecision = s"halfvec(${SceneModel.Siglip2.dimensions})"
    val vector = vectorBind(Some(embedding))

    val sql = s"""
      SELECT nearest.id, nearest.distance
        FROM (SELECT asset.${FieldConst.ID} AS id, $column <=> $vectorPlaceholder AS distance
                FROM asset
               WHERE asset.${FieldConst.REPO_ID} = ?
                 AND ${describeFlags(triaged)}
               ORDER BY $column::$halfPrecision <=> ?::$halfPrecision
               LIMIT ?) AS nearest
       WHERE nearest.distance <= ?
    """
    (sql, List(vector, RequestContext.getRepository.persistedId, vector, limit, maxDistance))
