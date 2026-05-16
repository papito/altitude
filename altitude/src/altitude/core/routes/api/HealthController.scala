package altitude.core.routes.api

import org.slf4j.Logger
import ujson.Obj

class HealthController(using logger: Logger) extends cask.Routes:
  private val prefix = "api"

  @cask.get(s"/$prefix/health")
  def health(): Obj =
    Obj("status" -> "ok")

  initialize()
