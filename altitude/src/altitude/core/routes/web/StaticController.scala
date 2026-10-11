package altitude.core.routes.web

import org.slf4j.Logger

import altitude.core.Assets
import altitude.core.routes.decorators.cachedForever
import altitude.core.routes.decorators.revalidated

class StaticController(using logger: Logger) extends cask.Routes:
  // Vendored libraries, fonts and the JS modules, under names that stay when their content changes
  @revalidated()
  @cask.staticResources("/static/")
  def staticFileRoutes() = "static"

  // The stylesheet bundle, under a name that changes with its content (see `cssBundle` in build.mill). Only this build's name
  // is served: any other under the prefix is not this build's bundle, whatever the classpath holds there
  @cachedForever()
  @cask.get("/assets/:name")
  def assetRoutes(name: String): cask.Response.Raw =
    if s"/assets/$name" == Assets.appCss then
      cask.model.StaticResource(s"assets/$name", getClass.getClassLoader, Seq("Content-Type" -> "text/css; charset=utf-8"))
    else cask.Response("Not found", 404, Seq("Content-Type" -> "text/plain"))

  initialize()
