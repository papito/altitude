package altitude.core
import altitude.core.routes.api.HealthController
import altitude.core.routes.decorators
import altitude.core.routes.web.SessionController
import altitude.core.routes.web.WebController
import altitude.core.routes.web.htmx.SetupController
import cask.router.Decorator
import org.bytedeco.javacpp.Loader
import org.bytedeco.opencv.opencv_java
import org.slf4j.Logger
import org.slf4j.LoggerFactory

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
  altitude.setIsInitializedState()

  given logger: Logger = LoggerFactory.getLogger(getClass)

  override def mainDecorators: Seq[Decorator[?, ?, ?, ?]] =
    Seq(new cask.decorators.compress(), decorators.requestResponseLogger())

  override def allRoutes: Seq[cask.Routes] = Seq(
    new HealthController,
    new WebController(),
    new SessionController(),
    new SetupController()
  )
