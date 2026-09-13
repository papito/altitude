package altitude.core.suites

import org.scalatest.Suites

import altitude.core.unit.ApiValidatorTests
import altitude.core.unit.CaptureDateResolverTests
import altitude.core.unit.CoreModelTests
import altitude.core.unit.DataScrubberTests
import altitude.core.unit.DynamicFilterTests
import altitude.core.unit.FolderModelTests
import altitude.core.unit.PersonModelTests
import altitude.core.unit.RowColumnTests
import altitude.core.unit.SearchQueryModelTests
import altitude.core.unit.SearchSqlTests
import altitude.core.unit.SessionControllerTests
import altitude.core.unit.SqlDialectTests
import altitude.core.unit.UrlServiceTests
import altitude.core.unit.UtilTests

abstract class AllUnitTestSuites
  extends Suites(
    new CoreModelTests,
    new CaptureDateResolverTests,
    new FolderModelTests,
    new SearchSqlTests,
    new RowColumnTests,
    new DynamicFilterTests,
    new SqlDialectTests,
    new ApiValidatorTests,
    new DataScrubberTests,
    new SearchQueryModelTests,
    new PersonModelTests,
    new SessionControllerTests,
    new UrlServiceTests,
    new UtilTests
  )
