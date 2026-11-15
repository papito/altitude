package altitude.core.dao.sqlite

import com.typesafe.config.Config

import altitude.core.dao.sql.search.SearchDialect
import altitude.core.dao.sql.search.SqliteSearchDialect

class SearchDao(override val config: Config) extends altitude.core.dao.jdbc.SearchDao(config) with SqliteOverrides:
  override protected val searchDialect: SearchDialect = SqliteSearchDialect
