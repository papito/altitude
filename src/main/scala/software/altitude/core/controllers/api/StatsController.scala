package software.altitude.core.controllers.api

import org.scalatra.Ok
import play.api.libs.json.JsNumber
import play.api.libs.json.JsObject
import play.api.libs.json.Json
import software.altitude.core.Api
import software.altitude.core.FieldConst
import software.altitude.core.controllers.BaseApiController

class StatsController extends BaseApiController {

  get("/") {
    Ok(Json.obj(
      Api.Field.Stats.STATS -> JsObject(
        app.service.stats.getStats.stats.map(stat =>
          stat.dimension -> JsNumber(stat.dimVal))
      )))
  }

  get(s"/:${FieldConst.Stat.DIMENSION}") {
    val dimension = params.get(FieldConst.Stat.DIMENSION).get.toLowerCase
    val dimVal = app.service.stats.getStats.getStatValue(dimension)

    Ok(Json.obj(
      FieldConst.Stat.DIM_VAL -> dimVal
    ))
  }
}
