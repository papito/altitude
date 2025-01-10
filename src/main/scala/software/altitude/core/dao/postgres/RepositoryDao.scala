package software.altitude.core.dao.postgres

import com.typesafe.config.Config

import software.altitude.core.FieldConst
import software.altitude.core.dao.jdbc

class RepositoryDao(override val config: Config) extends jdbc.RepositoryDao(config) with PostgresOverrides {
  override protected def columnsForSelect: List[String] = List(
    "*",
    s"(repository.${FieldConst.Repository.FILES_STORE_CONFIG}#>>'{}')::text as ${FieldConst.Repository.FILES_STORE_CONFIG}"
  )
}
