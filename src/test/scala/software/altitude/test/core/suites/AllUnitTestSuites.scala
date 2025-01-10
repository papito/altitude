package software.altitude.test.core.suites

import org.scalatest.Suites
import software.altitude.test.core.unit.ApiValidatorTests
import software.altitude.test.core.unit.CoreModelTests
import software.altitude.test.core.unit.DataScrubberTests
import software.altitude.test.core.unit.FolderModelTests
import software.altitude.test.core.unit.SearchQueryModelTests
import software.altitude.test.core.unit.SearchSqlQueryTests
import software.altitude.test.core.unit.SqlQueryTests

abstract class AllUnitTestSuites extends Suites (
  new CoreModelTests,
  new FolderModelTests,
  new SqlQueryTests,
  new SearchSqlQueryTests,
  new ApiValidatorTests,
  new DataScrubberTests,
  new SearchQueryModelTests,

)
