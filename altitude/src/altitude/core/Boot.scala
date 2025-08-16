package altitude.core

import altitude.core.routes.api.HealthRoutes
import altitude.core.routes.web.IndexRoutes
import org.bytedeco.javacpp.Loader
import org.bytedeco.opencv.opencv_java

object Boot extends cask.Main:
  /**cask.MainRoutes
   * Mission-critical code to load the OpenCV native library.
   *
   * OpenCV for Java has two competing APIs, which is confusing enough (org.opencv, org.bytedeco),
   * and every example under the sun directs to do this in order to have native lib linking errors
   * go away: System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
   *
   * But it doesn't work. While we are using the org.opencv API, the native lib is loaded by the org.bytedeco API.
   *
   * https://stackoverflow.com/a/58064096/53687
   */
  Loader.load(classOf[opencv_java])

  override def allRoutes: Seq[cask.Routes] = Seq(
    new HealthRoutes,
    new IndexRoutes(),
  )
