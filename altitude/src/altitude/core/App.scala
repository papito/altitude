package altitude.core
import altitude.core.routes.api.HealthController
import altitude.core.routes.decorators
import altitude.core.routes.web.{ContentViewController, ImportController, IndexController, SessionController, SetupController, StaticController}
import cask.router.Decorator
import org.bytedeco.javacpp.Loader
import org.bytedeco.opencv.opencv_java
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import altitude.core.routes.web.partial.{AlbumActionController, AssetActionController, FolderActionController, NavController, PeopleActionController, SearchResultsController, SetupFormController, TrashActionController, ViewSettingsActionController}

object App extends cask.Main:
  /**
   * Load the OpenCV native library.
   *
   * OpenCV for Java has two competing APIs, which is confusing enough (org.opencv, org.bytedeco), and every example under the sun
   * directs to do this in order to have native lib linking errors go away:
   *
   * System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
   *
   * But it doesn't work. While we are using the org.opencv API, the native lib is loaded by the org.bytedeco API.
   *
   * https://stackoverflow.com/a/58064096/53687
   */
  Loader.load(classOf[opencv_java])

  val altitude: Altitude = new Altitude()
  
  altitude.runMigrations()
  // Check if the instance is in setup mode, and cache the value of isInitialized in memory for quick access.
  altitude.setIsInitializedState()

  given logger: Logger = LoggerFactory.getLogger(getClass)

  override def mainDecorators: Seq[Decorator[?, ?, ?, ?]] =
    Seq(new cask.decorators.compress(), decorators.requestResponseLogger(), decorators.repoContext())

  override def allRoutes: Seq[cask.Routes] = Seq(
    new HealthController,
    new IndexController,
    new StaticController,
    new SessionController,
    new SetupController,
    new ImportController,
    new ContentViewController,

    // partials
    new SetupFormController,
    new NavController,
    new AlbumActionController,
    new AssetActionController,
    new FolderActionController,
    new PeopleActionController,
    new SearchResultsController,
    new TrashActionController,
    new ViewSettingsActionController,
  )
