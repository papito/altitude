package altitude.core.routes.web

import org.slf4j.Logger

import altitude.core.Assets
import altitude.core.routes.decorators.immutable

class StaticController(using logger: Logger) extends cask.Routes:
  @cask.staticResources("/static/")
  def staticFileRoutes() = "static"

  // The stylesheet bundle, under a name that changes with its content (see `cssBundle` in build.mill). Only this build's name
  // is served: any other under the prefix is not this build's bundle, whatever the classpath holds there
  @immutable()
  @cask.get("/assets/:name")
  def assetRoutes(name: String): cask.Response.Raw =
    if s"/assets/$name" == Assets.appCss then
      cask.model.StaticResource(s"assets/$name", getClass.getClassLoader, Seq("Content-Type" -> "text/css; charset=utf-8"))
    else cask.Response("Not found", 404, Seq("Content-Type" -> "text/plain"))

  initialize()
