package software.altitude.test.core.api

import software.altitude.core.Const
import software.altitude.test.core.integration.IntegrationTestCore

class ApiTestCore extends IntegrationTestCore {
  override def config: Map[String,Const.DatasourceType.Value] = Map("datasource" -> Const.DatasourceType.SQLITE)
}
