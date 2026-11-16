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

  // The stylesheet bundle, named by its content hash (`cssBundle` in build.mill). Only this build's name is served, whatever
  // else the classpath holds under the prefix
  @cachedForever()
  @cask.get("/assets/:name")
  def assetRoutes(name: String): cask.Response.Raw =
    if s"/assets/$name" == Assets.appCss then
      cask.model.StaticResource(s"assets/$name", getClass.getClassLoader, Seq("Content-Type" -> "text/css; charset=utf-8"))
    else cask.Response("Not found", 404, Seq("Content-Type" -> "text/plain"))

  initialize()
