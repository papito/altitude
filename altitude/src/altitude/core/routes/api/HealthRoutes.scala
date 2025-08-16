package altitude.core.routes.api

import ujson.Obj

class HealthRoutes extends cask.Routes:
  private val prefix = "api"

  @cask.get(s"/$prefix/health")
  def health(): Obj =
    Obj("status" -> "ok")

  initialize()
