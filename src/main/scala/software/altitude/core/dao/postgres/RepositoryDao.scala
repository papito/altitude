package software.altitude.core.dao.postgres

import com.typesafe.config.Config
import software.altitude.core.dao.jdbc
import software.altitude.core.models.Field

class RepositoryDao(override val config: Config) extends jdbc.RepositoryDao(config) with PostgresOverrides {
  override protected def columnsForSelect: List[String] = List(
    "*",
    s"(repository.${Field.Repository.FILES_STORE_CONFIG}#>>'{}')::text as ${Field.Repository.FILES_STORE_CONFIG}",
  )
}
