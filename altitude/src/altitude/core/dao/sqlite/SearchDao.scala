package altitude.core.dao.sqlite

import com.typesafe.config.Config

import altitude.core.dao.sql.search.SearchDialect
import altitude.core.dao.sql.search.SqliteSearchDialect
import altitude.core.util.SearchQuery

class SearchDao(override val config: Config) extends altitude.core.dao.jdbc.SearchDao(config) with SqliteOverrides:
  override protected val searchDialect: SearchDialect = SqliteSearchDialect

  // sqlite-vector's scan compares every embedding, whatever the filters leave: it is the one way SQLite ranks
  override protected def ranksExactly(query: SearchQuery): Boolean = true
