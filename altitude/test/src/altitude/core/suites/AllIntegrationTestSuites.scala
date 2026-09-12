package altitude.core.suites

import org.scalatest.Suites

import altitude.core.Altitude
import altitude.core.integration.*

abstract class AllIntegrationTestSuites(val testApp: Altitude)
  extends Suites(
    new SystemServiceTests(testApp),
    new AssetQueryTests(testApp),
    new AssetServiceTests(testApp),
    new AssetDateStorageTests(testApp),
    new MetadataParserTests(testApp),
    new SearchServiceTests(testApp),
    new SearchGroupingTests(testApp),
    new SearchCursorTests(testApp),
    new RepositoryServiceTests(testApp),
    new AssetImportServiceTests(testApp),
    new FolderServiceTests(testApp),
    new AlbumServiceTests(testApp),
    new FileStoreServiceTests(testApp),
    new StatsServiceTests(testApp),
    new UserServiceTests(testApp),
    new LibraryServiceTests(testApp),
    new LibraryServicePruneTests(testApp),
    new LibraryServiceRecycleTests(testApp),
    new LibraryServiceRestoreTests(testApp),
    new UserMetadataServiceTests(testApp),
    new FaceDetectionTests(testApp),
    new FaceRecognitionServiceTests(testApp),
    new PersonServiceTests(testApp),
    new ImportPipelineServiceTests(testApp),
    new PurgePipelineServiceTests(testApp)
  )
