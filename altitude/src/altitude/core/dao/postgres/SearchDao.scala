package altitude.core.dao.postgres

import com.typesafe.config.Config
import scalasql.Sc
import scalasql.core.Expr
import scalasql.query.Select

import altitude.core.RequestContext
import altitude.core.dao.sql.Db
import altitude.core.dao.sql.search.PostgresSearchDialect
import altitude.core.dao.sql.search.RankedRows
import altitude.core.dao.sql.search.SearchDialect
import altitude.core.dao.sql.search.SearchQueries
import altitude.core.dao.sql.tables.AssetRow
import altitude.core.util.DescribeRanking
import altitude.core.util.SearchQuery

class SearchDao(override val config: Config) extends altitude.core.dao.jdbc.SearchDao(config) with PostgresOverrides:
  override protected val searchDialect: SearchDialect = PostgresSearchDialect

  /** The ranked rows of a page: exact for the searches that leave few assets to rank, through the vector index for the rest */
  override protected def rankedRows(
      query: SearchQuery,
      ranking: DescribeRanking): Select[AssetRow[Expr], AssetRow[Sc]] => RankedRows =
    if ranksExactly(query) then PostgresSearchDialect.ranked(_, ranking) else PostgresSearchDialect.rankedByIndex(_, ranking)

  /**
   * The choice follows how many assets the search leaves to rank: its view's (the repository, outside the trash and, unless asked
   * for, outside triage) that pass its filters and carry an embedding. Few are ranked one by one, each embedding compared with
   * the description's, since the vector index would walk far past them to find the ones the search matches; many are ranked
   * through the index, which reaches enough of them soon. What separates the two is `SearchQuery.describeExactLimit`
   * (`Const.Search.DESCRIBE_EXACT_LIMIT`): the matches that carry an embedding are counted up to one past it, in the statement of
   * a capped count, and at most that many are ranked exactly. A description with no filter is counted too, since the index holds
   * every repository's embeddings and the view is a filter of its scan as any other is: a small Library beside a large one, or
   * one mostly in triage or in the trash, would have the scan walk the whole index for its few rows.
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
