package software.altitude.core.controllers.api

import org.scalatra.Ok
import play.api.libs.json.JsNumber
import play.api.libs.json.JsObject
import play.api.libs.json.Json
import software.altitude.core.controllers.BaseApiController
import software.altitude.core.models.Field
import software.altitude.core.{Const => C}

class StatsController extends BaseApiController {

  get("/") {
    Ok(Json.obj(
      C.Api.Stats.STATS -> JsObject(
        app.service.stats.getStats.stats.map(stat =>
          stat.dimension -> JsNumber(stat.dimVal))
      )))
  }

  get(s"/:${Field.Stat.DIMENSION}") {
    val dimension = params.get(Field.Stat.DIMENSION).get.toLowerCase
    val dimVal = app.service.stats.getStats.getStatValue(dimension)

    Ok(Json.obj(
      Field.Stat.DIM_VAL -> dimVal
    ))
  }
}
