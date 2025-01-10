package software.altitude.test.core.suites

import org.scalatest.Suites
import software.altitude.core.Altitude
import software.altitude.test.core.integration._

abstract class AllIntegrationTestSuites(val testApp: Altitude) extends Suites (
  new SystemServiceTests(testApp),
  new AssetQueryTests(testApp),
  new AssetServiceTests(testApp),
  new MetadataParserTests(testApp),
  new SearchServiceTests(testApp),
  new RepositoryServiceTests(testApp),
  new AssetImportServiceTests(testApp),
  new FolderServiceTests(testApp),
  new FileStoreServiceTests(testApp),
  new StatsServiceTests(testApp),
  new UserServiceTests(testApp),
  new LibraryServiceTests(testApp),
  new UserMetadataServiceTests(testApp),
  new FaceDetectionTests(testApp),
  new PersonServiceTests(testApp),
  new FaceRecognitionServiceTests(testApp),
  new ImportPipelineServiceTests(testApp),
)
