package software.altitude.test.core.suites

import software.altitude.core.Altitude
import software.altitude.core.{Const => C}

object SqliteSuiteBundle {
  val app = new Altitude(Map("datasource" -> C.DatasourceType.SQLITE))
}

class SqliteSuiteBundle
  extends AllIntegrationTestSuites(config = Map("datasource" -> C.DatasourceType.SQLITE))
  with SqliteBundleSetup
