package altitude.core.dao.postgres

import com.typesafe.config.Config

import altitude.core.RequestContext
import altitude.core.dao.sql.Db
import altitude.core.dao.sql.search.PostgresSearchDialect
import altitude.core.dao.sql.search.SearchDialect
import altitude.core.dao.sql.search.SearchQueries
import altitude.core.util.SearchQuery

class SearchDao(override val config: Config) extends altitude.core.dao.jdbc.SearchDao(config) with PostgresOverrides:
  override protected val searchDialect: SearchDialect = PostgresSearchDialect

  /**
   * The choice follows how many assets the search's filters leave to rank. Few are ranked one by one, each embedding compared
   * with the description's, since the vector index would walk far past them to find the ones the filters pass; many are ranked
   * through the index, which reaches enough of them soon. What separates the two is `SearchQuery.describeExactLimit`
   * (`Const.Search.DESCRIBE_EXACT_LIMIT`): the matches that carry an embedding are counted up to one past it, in the statement of
   * a capped count, and at most that many are ranked exactly. A search that narrows its view by nothing is not counted: the index
   * holds the whole view, and a description alone reads it as it always has.
   */
  override protected def ranksExactly(query: SearchQuery): Boolean =
    import dialect.*

    if !query.narrowsView then
      logger.debug("A description with no filter is ranked through the vector index")
      false
    else
      val limit = query.describeExactLimit
      val statement = SearchQueries.embeddedCount(searchDialect, query, RequestContext.getRepository.persistedId, limit)
      val embedded = Db.read(dialect)(_.runSql[Int](statement)).head
      val exactly = embedded <= limit
      logger.debug(
        s"A description within filters that leave ${if exactly then embedded else s"more than $limit"} assets with an " +
          s"embedding is ranked ${if exactly then "exactly" else "through the vector index"}")
      exactly
