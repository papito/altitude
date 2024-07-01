package software.altitude.core.dao.postgres

import software.altitude.core.Configuration
import software.altitude.core.dao.jdbc
import software.altitude.core.{Const => C}

class RepositoryDao(override val config: Configuration) extends jdbc.RepositoryDao(config) with PostgresOverrides {
  override protected def columnsForSelect: List[String] = List(
    "*",
    s"(repository.${C.Repository.FILES_STORE_CONFIG}#>>'{}')::text as ${C.Repository.FILES_STORE_CONFIG}",
  )
}
