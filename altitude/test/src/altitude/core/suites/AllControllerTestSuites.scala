package altitude.core.suites

import org.scalatest.Suites

import altitude.core.controller.AlbumActionControllerTests
import altitude.core.controller.AlbumControllerTests
import altitude.core.controller.AssetControllerTests
import altitude.core.controller.ContentViewControllerTests
import altitude.core.controller.FolderActionControllerTests
import altitude.core.controller.FolderControllerTests
import altitude.core.controller.HealthControllerTests
import altitude.core.controller.IndexControllerTests
import altitude.core.controller.LoginControllerTests
import altitude.core.controller.PeopleActionControllerTests

abstract class AllControllerTestSuites
  extends Suites(
    new HealthControllerTests(),
    new IndexControllerTests(),
    new AssetControllerTests(),
    new LoginControllerTests(),
    new ContentViewControllerTests(),
    new FolderActionControllerTests(),
    new FolderControllerTests(),
    new AlbumActionControllerTests(),
    new AlbumControllerTests(),
    new PeopleActionControllerTests()
  )
