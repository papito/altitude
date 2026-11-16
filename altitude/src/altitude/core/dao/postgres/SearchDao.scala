package altitude.core.dao.postgres

import com.typesafe.config.Config

import altitude.core.RequestContext
import altitude.core.dao.sql.Db
import altitude.core.dao.sql.search.PostgresSearchDialect
import altitude.core.dao.sql.search.SearchDialect
import altitude.core.dao.sql.search.SearchQueries
import altitude.core.util.DescribeRanking
import altitude.core.util.SearchQuery

class SearchDao(override val config: Config) extends altitude.core.dao.jdbc.SearchDao(config) with PostgresOverrides:
  override protected val searchDialect: SearchDialect = PostgresSearchDialect

  /** Exact for the searches that leave few assets to rank, through the vector index for the rest */
  override def rankingFor(query: SearchQuery, ranking: DescribeRanking): DescribeRanking =
    ranking.copy(throughIndex = !ranksExactly(query))

  /**
   * Counts the view's matches that carry an embedding, up to one past `SearchQuery.describeExactLimit`. Few are ranked exactly,
   * since the index would walk far past them to find them; many go through the index, which reaches enough soon. A description
   * with no filter is counted too: the index holds every repository's embeddings, so a small Library beside a large one, or one
   * mostly in triage or the trash, would walk the whole index for its few rows.
   */
  private def ranksExactly(query: SearchQuery): Boolean =
    import dialect.*

    val limit = query.describeExactLimit
    val statement = SearchQueries.embeddedCount(searchDialect, query, RequestContext.getRepository.persistedId, limit)
    val embedded = Db.read(dialect)(_.runSql[Int](statement)).head
    val exactly = embedded <= limit
    logger.debug(
      s"A description over ${if exactly then embedded else s"more than $limit"} matching assets with an embedding is ranked " +
        (if exactly then "exactly" else "through the vector index"))
    exactly
