package altitude.core.dao.postgres

import com.typesafe.config.Config

import altitude.core.dao.sql.search.PostgresSearchDialect
import altitude.core.dao.sql.search.SearchDialect

class SearchDao(override val config: Config) extends altitude.core.dao.jdbc.SearchDao(config) with PostgresOverrides:
  override protected val searchDialect: SearchDialect = PostgresSearchDialect
