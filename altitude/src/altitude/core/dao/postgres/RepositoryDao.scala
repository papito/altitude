package altitude.core.dao.postgres

import altitude.core.FieldConst
import altitude.core.dao.jdbc
import com.typesafe.config.Config

class RepositoryDao(override val config: Config) extends jdbc.RepositoryDao(config) with PostgresOverrides:
  override protected def columnsForSelect: List[String] = List(
    "*",
    s"(repository.${FieldConst.Repository.FILES_STORE_CONFIG}#>>'{}')::text as ${FieldConst.Repository.FILES_STORE_CONFIG}"
  )
