package software.altitude.test.core.suites

import org.scalatest.Suites
import software.altitude.test.core.unit.{ApiValidatorTests, CoreModelTests, DataScrubberTests, FolderModelTests, PersonModelTests, SearchQueryModelTests, SearchSqlQueryTests, SqlQueryTests, UrlServiceTests}

abstract class AllUnitTestSuites extends Suites (
  new CoreModelTests,
  new FolderModelTests,
  new SqlQueryTests,
  new SearchSqlQueryTests,
  new ApiValidatorTests,
  new DataScrubberTests,
  new SearchQueryModelTests,
  new PersonModelTests,
  new UrlServiceTests
)
