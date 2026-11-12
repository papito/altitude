package altitude.core.suites

import org.scalatest.Suites

import altitude.core.Altitude
import altitude.core.integration.*

abstract class AllIntegrationTestSuites(val testApp: Altitude)
  extends Suites(
    new SystemServiceTests(testApp),
    new StartupTests(testApp),
    new SettingsServiceTests(testApp),
    new TransactionManagerTests(testApp),
    new SqlExplainLogTests(testApp),
    new AssetQueryTests(testApp),
    new AssetServiceTests(testApp),
    new AssetDateStorageTests(testApp),
    new BaseDaoTests(testApp),
    new RepositoryIsolationTests(testApp),
    new MetadataParserTests(testApp),
    new SearchServiceTests(testApp),
    new SearchGroupingTests(testApp),
    new SearchCursorTests(testApp),
    new SearchMapTests(testApp),
    new RepositoryServiceTests(testApp),
    new AssetImportServiceTests(testApp),
    new SequenceServiceTests(testApp),
    new CurationServiceTests(testApp),
    new FolderServiceTests(testApp),
    new AlbumServiceTests(testApp),
    new LocationServiceTests(testApp),
    new FileStoreServiceTests(testApp),
    new StatsServiceTests(testApp),
    new UserServiceTests(testApp),
    new AccountManagementTests(testApp),
    new LoginTokenTests(testApp),
    new AuditServiceTests(testApp),
    new LibraryServiceTests(testApp),
    new LibraryServicePruneTests(testApp),
    new LibraryServiceRecycleTests(testApp),
    new LibraryServiceRestoreTests(testApp),
    new AssetPermissionTests(testApp),
    new UserMetadataServiceTests(testApp),
    new FaceDetectionTests(testApp),
    new FaceRecognitionServiceTests(testApp),
    new PersonServiceTests(testApp),
    new FaceCorrectionTests(testApp),
    new ImportServiceTests(testApp),
    new ImportPipelineServiceTests(testApp),
    new WatchFolderServiceTests(testApp),
    new PurgePipelineServiceTests(testApp)
  )
