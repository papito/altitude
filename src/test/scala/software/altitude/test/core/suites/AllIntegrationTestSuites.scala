package software.altitude.test.core.suites

import org.scalatest.Suites
import software.altitude.test.core.integration._
import software.altitude.test.core.unit._

abstract class AllIntegrationTestSuites(val config: Map[String, Any]) extends Suites (
  new SystemServiceTests(config),
  new AssetQueryTests(config),
  new SearchQueryModelTests,
  new AssetServiceTests(config),
  new MetadataParserTests(config),
  new SearchServiceTests(config),
  new RepositoryServiceTests(config),
  new AssetImportServiceTests(config),
  new FolderServiceTests(config),
  new FileStoreServiceTests(config),
  new StatsServiceTests(config),
  new UserServiceTests(config),
  new LibraryServiceTests(config),
  new MetadataServiceTests(config),
)
