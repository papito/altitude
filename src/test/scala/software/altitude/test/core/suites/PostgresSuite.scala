package software.altitude.test.core.suites


import software.altitude.core.Altitude
import software.altitude.core.{Const => C}

object PostgresSuite {
  val app = new Altitude(Map("datasource" -> C.DatasourceType.POSTGRES))
}

class PostgresSuite
  extends AllTests(config = Map("datasource" -> C.DatasourceType.POSTGRES))
  with PostgresSuiteSetup
