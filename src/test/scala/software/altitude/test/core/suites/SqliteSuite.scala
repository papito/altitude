package software.altitude.test.core.suites

import software.altitude.core.Altitude
import software.altitude.core.{Const => C}

object SqliteSuite {
  val app = new Altitude(Map("datasource" -> C.DatasourceType.SQLITE))
}

class SqliteSuite
  extends AllIntegrationTests(config = Map("datasource" -> C.DatasourceType.SQLITE))
  with SqliteSuiteSetup
