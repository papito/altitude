package software.altitude.test.core.suites

import software.altitude.core.Altitude
import software.altitude.core.{Const => C}

object WebSuite {
  val app = new Altitude(Map("datasource" -> C.DatasourceType.POSTGRES))
}

/*
 The web suite does NOT run against all DB engines. It only runs against Postgres.
 Since we are testing the web layer, we don't need to test against all DB engines.

 In addition, the default app config will choose one of the DB engines, so the test
 client will run on one and the server will run on another type of DB causing major havoc.
 */
class WebSuite
  extends AllWebTests(config = Map("datasource" -> C.DatasourceType.POSTGRES))
  with WebSuiteSetup {
}
