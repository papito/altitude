package software.altitude.test.core.suites


import software.altitude.core.Altitude
import software.altitude.core.{Const => C}

object PostgresSuiteBundle {
  val app = new Altitude(Map("datasource" -> C.DatasourceType.POSTGRES))
}

class PostgresSuiteBundle
  extends AllIntegrationTestSuites(config = Map("datasource" -> C.DatasourceType.POSTGRES))
  with PostgresBundleSetup {
}
